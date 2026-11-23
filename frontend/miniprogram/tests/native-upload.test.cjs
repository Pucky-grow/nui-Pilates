const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const createTransport = require('./support/transport.cjs');
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aGNsAAAAASUVORK5CYII=', 'base64');

function harness(options = {}) {
  const calls = [], reads = [], stats = [];
  const input = options.input || PNG;
  const app = { globalData: { transport: 'cloudrun', cloudEnvironment: 'isolated-test', cloudService: 'isolated-service' } };
  const filesystem = {
    stat(value) { stats.push(value); value.success({ stats: { size: options.size === undefined ? input.length : options.size } }); },
    readFile(value) { reads.push(value); value.success({ data: options.nonBinary ? 'must-not-send' : Uint8Array.from(input).buffer }); }
  };
  const wx = { getFileSystemManager: () => filesystem,
    cloud: { init() {}, callContainer: value => calls.push(value) },
    request: () => assert.fail('must not fall back to wx.request'),
    uploadFile: () => assert.fail('must not fall back to wx.uploadFile') };
  const transport = createTransport(wx, () => app);
  function start(extra = {}) {
    let success, failure, completed = 0;
    transport.upload({ path: '/api/upload', name: 'file', filePath: 'wxfile://synthetic-selected-image',
      header: { Authorization: 'Bearer synthetic-session', 'X-WX-OPENID': 'must-not-forward', 'Content-Type': 'untrusted/type' },
      success: value => { success = value; }, fail: value => { failure = value; }, complete: () => { completed++; }, ...extra });
    return { get success() { return success; }, get failure() { return failure; }, get completed() { return completed; } };
  }
  return { calls, reads, stats, app, wx, filesystem, transport, start };
}

test('native image upload emits a parser-valid multipart body with exact binary and safe fixed filename', async () => {
  const h = harness(), result = h.start({ formData: { purpose: 'BODY_RECORD', ownerId: 123 } });
  assert.equal(h.reads[0].length, PNG.length); assert.equal(h.reads[0].position, 0);
  assert.equal(h.calls.length, 1);
  const call = h.calls[0];
  assert.equal(Object.prototype.toString.call(call.data), '[object ArrayBuffer]');
  assert.equal(call.path, '/api/upload'); assert.equal(call.method, 'POST');
  assert.equal(call.header.Authorization, 'Bearer synthetic-session'); assert.equal(call.header['X-WX-OPENID'], undefined);
  assert.equal(call.header['X-WX-SERVICE'], 'isolated-service'); assert.equal(call.followRedirect, false);
  assert.ok(call.data.byteLength <= 512 * 1024);
  const body = Buffer.from(call.data);
  assert.equal(body.includes('wxfile://'), false);
  const form = await new Response(body, { headers: { 'Content-Type': call.header['Content-Type'] } }).formData();
  assert.equal(form.get('purpose'), 'BODY_RECORD'); assert.equal(form.get('ownerId'), '123');
  const file = form.get('file'); assert.equal(file.name, 'upload.png'); assert.equal(file.type, 'image/png');
  assert.deepEqual(Buffer.from(await file.arrayBuffer()), PNG);
  call.success({ statusCode: 200, data: { code: 0, data: { url: '/media/ref/synthetic', previewUrl: 'https://isolated.example.test/media/access/synthetic' } } });
  call.complete();
  assert.equal(JSON.parse(result.success.data).code, 0); assert.equal(result.completed, 1);
});

test('native multipart recognizes JPG and GIF headers without trusting the local path extension', async () => {
  for (const [input, name, mime] of [[Buffer.from([255, 216, 255, 0, 128, 255, 217]), 'upload.jpg', 'image/jpeg'],
    [Buffer.from('GIF89a' + '\u0000\u00ff', 'latin1'), 'upload.gif', 'image/gif']]) {
    const h = harness({ input }); h.start(); const call = h.calls[0];
    const form = await new Response(Buffer.from(call.data), { headers: { 'Content-Type': call.header['Content-Type'] } }).formData();
    assert.equal(form.get('file').name, name); assert.equal(form.get('file').type, mime);
    assert.deepEqual(Buffer.from(await form.get('file').arrayBuffer()), input);
    assert.equal(form.get('purpose'), 'AVATAR');
  }
});

test('missing authorization and invalid form fields stop before filesystem reads or network', () => {
  for (const extra of [{ header: {} }, { formData: { purpose: 'PUBLIC_ASSET\r\ninjected' } },
    { formData: { purpose: 'AVATAR', ownerId: '1\r\nX-Evil:1' } }, { formData: { purpose: 'AVATAR', unexpected: 'value' } },
    { name: 'file"; filename="injected' }, { path: '/api/files' }]) {
    const h = harness(), result = h.start(extra);
    assert.ok(result.failure); assert.equal(result.completed, 1);
    assert.equal(h.stats.length, 0); assert.equal(h.reads.length, 0); assert.equal(h.calls.length, 0);
  }
});

test('empty and oversized metadata is rejected before reading the file', () => {
  for (const size of [0, 500 * 1024 + 1, NaN, -1, 0.5]) {
    const h = harness({ size }), result = h.start();
    assert.ok(result.failure); assert.equal(h.reads.length, 0); assert.equal(h.calls.length, 0);
    assert.equal(result.completed, 1);
  }
});

test('500KiB image payload stays within the independent 512KiB multipart ceiling', () => {
  const input = Buffer.alloc(500 * 1024); PNG.copy(input);
  const h = harness({ input }); h.start({ formData: { purpose: 'PUBLIC_ASSET' } });
  assert.equal(h.calls.length, 1); assert.ok(h.calls[0].data.byteLength <= 512 * 1024);
});

test('unrecognized file, changed size and nonbinary read result never reach the network', () => {
  for (const options of [{ input: Buffer.from('<svg>must-not-send</svg>') }, { size: PNG.length - 1 }, { nonBinary: true }]) {
    const h = harness(options), result = h.start();
    assert.ok(result.failure); assert.equal(result.completed, 1); assert.equal(h.calls.length, 0);
  }
});

test('filesystem errors use fixed messages without leaking the selected path', () => {
  for (const stage of ['stat', 'readFile']) {
    const h = harness(); h.filesystem[stage] = value => value.fail({ errMsg: 'raw failure: wxfile://private-sensitive-path' });
    const result = h.start();
    assert.equal(result.failure.message, '图片读取失败，请重新选择图片');
    assert.equal(JSON.stringify(result.failure).includes('private-sensitive-path'), false);
    assert.equal(result.completed, 1); assert.equal(h.calls.length, 0);
  }
});

test('changing the target environment while reading rejects instead of sending the old session to the new environment', () => {
  const h = harness(); let read;
  h.filesystem.readFile = value => { read = value; };
  const result = h.start(); h.app.globalData.cloudEnvironment = 'different-environment';
  read.success({ data: Uint8Array.from(PNG).buffer });
  assert.equal(result.failure.code, 'UPLOAD_CONTEXT_CHANGED'); assert.equal(h.calls.length, 0);
});

test('changing accounts while file metadata is pending stops before reading or sending old credentials', () => {
  const h = harness(); let stat;
  h.filesystem.stat = value => { stat = value; };
  const result = h.start(); h.app.globalData.authEpoch = 1;
  stat.success({ stats: { size: PNG.length } });
  assert.equal(result.failure.code, 'UPLOAD_CONTEXT_CHANGED');
  assert.equal(h.reads.length, 0); assert.equal(h.calls.length, 0); assert.equal(result.completed, 1);
});

test('changing accounts while file bytes are pending prevents sending the captured old Bearer', () => {
  const h = harness(); h.app.globalData.authEpoch = 7; let read;
  h.filesystem.readFile = value => { read = value; };
  const result = h.start(); h.app.globalData.authEpoch++;
  read.success({ data: Uint8Array.from(PNG).buffer });
  assert.equal(result.failure.code, 'UPLOAD_CONTEXT_CHANGED');
  assert.equal(h.calls.length, 0); assert.equal(result.success, undefined); assert.equal(result.completed, 1);
});

test('late upload success after account or target changes is ignored and completes only once', () => {
  for (const field of ['authEpoch', 'environment', 'transport', 'baseUrl', 'cloudEnvironment', 'cloudService']) {
    const h = harness(), result = h.start(), call = h.calls[0];
    h.app.globalData[field] = field === 'authEpoch' ? 1 : 'different-context';
    const response = { statusCode: 200, data: { code: 0, data: { url: '/media/ref/must-not-accept', previewUrl: 'https://isolated.example.test/must-not-accept' } } };
    call.success(response); call.complete(response);
    // 即使平台再次投递完成/成功回调，也不能接受旧账号结果或重复结束并发加载状态。
    call.success(response); call.complete(response);
    assert.equal(result.failure.code, 'UPLOAD_CONTEXT_CHANGED', field);
    assert.equal(result.success, undefined, field); assert.equal(result.completed, 1, field);
    assert.equal(JSON.stringify(result.failure).includes('must-not-accept'), false, field);
  }
});

test('same auth epoch and context accepts one upload response with exactly one completion', () => {
  const h = harness(); h.app.globalData.authEpoch = 2;
  const result = h.start(), call = h.calls[0];
  const response = { statusCode: 200, data: { code: 0, data: { url: '/media/ref/synthetic' } } };
  call.success(response); call.complete(response); call.complete(response);
  assert.equal(JSON.parse(result.success.data).code, 0);
  assert.equal(result.failure, undefined); assert.equal(result.completed, 1);
});

test('late failure after account change also returns the fixed stale result without old request details', () => {
  const h = harness(), result = h.start(), call = h.calls[0];
  h.app.globalData.authEpoch = 1;
  call.fail({ errMsg: 'old-request-details-must-not-escape' }); call.complete();
  assert.equal(result.failure.code, 'UPLOAD_CONTEXT_CHANGED'); assert.equal(result.completed, 1);
  assert.equal(JSON.stringify(result.failure).includes('old-request-details'), false);
});

test('native upload returns business denial unchanged for existing authorization handlers', () => {
  const h = harness(), result = h.start({ formData: { purpose: 'PUBLIC_ASSET' } });
  h.calls[0].success({ statusCode: 403, data: { code: 40300, message: 'synthetic-forbidden' } });
  assert.equal(JSON.parse(result.success.data).code, 40300);
});

test('all three upload wrappers retain their own Bearer session and consume native JSON responses', async () => {
  for (const [relative, token] of [['utils/request.js', 'synthetic-member'], ['admin/api.js', 'synthetic-admin'], ['coach-end/api.js', 'synthetic-coach']]) {
    const h = harness();
    const tokens = { token: 'synthetic-member', adminToken: 'synthetic-admin', coachToken: 'synthetic-coach' };
    Object.assign(h.wx, { getStorageSync: key => tokens[key], showLoading() {}, hideLoading() {}, showToast() {} });
    const module = { exports: {} };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', relative), 'utf8'),
      { module, wx: h.wx, getApp: () => h.app, setTimeout, require: () => h.transport });
    const pending = module.exports.upload('/api/upload', 'wxfile://synthetic-selected-image');
    const call = h.calls[0]; assert.equal(call.header.Authorization, 'Bearer ' + token);
    call.success({ statusCode: 200, data: { code: 0, data: { url: '/media/ref/synthetic', previewUrl: 'https://isolated.example.test/synthetic' } } });
    call.complete && call.complete();
    const result = await pending;
    assert.equal(relative === 'utils/request.js' ? result.previewUrl : result, 'https://isolated.example.test/synthetic');
  }
});
