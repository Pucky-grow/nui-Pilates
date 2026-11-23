const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const createTransport = require('./support/transport.cjs');

function harness(mode = 'cloudrun') {
  const calls = [], storage = new Map(), app = { globalData: { transport: mode,
    cloudEnvironment: 'isolated-test-environment', cloudService: 'isolated-service',
    baseUrl: mode === 'http' ? 'http://127.0.0.1:8090' : '', useMockLogin: false } };
  const wx = { cloud: { init: value => calls.push({ kind: 'init', value }), callContainer: value => calls.push({ kind: 'native', value }) },
    request: value => calls.push({ kind: 'http', value }), uploadFile: value => calls.push({ kind: 'upload', value }),
    getStorageSync: key => storage.get(key), setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key), showToast() {}, showLoading() {}, hideLoading() {}, reLaunch() {},
    login: value => value.success({ code: 'synthetic-one-use-code' }) };
  const transport = createTransport(wx, () => app);
  function load(relative) {
    const module = { exports: {} };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '..', relative), 'utf8'),
      { module, wx, getApp: () => app, Date, Promise, setTimeout,
        require: name => name.includes('transport') ? transport : { clearAuth() {} } });
    return module.exports;
  }
  return { app, wx, calls, storage, transport, load };
}

test('native GET preserves existing query and encodes filters without forwarding a request body', () => {
  const h = harness();
  h.transport.request({ path: '/api/courses?status=OPEN', method: 'GET', data: { keyword: '私教&x=1', page: 0, active: false, empty: '', missing: null }, header: { Authorization: 'Bearer synthetic-member', 'x-wx-openid': 'untrusted', 'X-WX-SERVICE': 'untrusted' } });
  const call = h.calls.find(x => x.kind === 'native').value;
  assert.equal(call.path, '/api/courses?status=OPEN&keyword=' + encodeURIComponent('私教&x=1') + '&page=0&active=false');
  assert.equal(call.data, undefined);
  assert.equal(call.config.env, 'isolated-test-environment');
  assert.equal(call.header['X-WX-SERVICE'], 'isolated-service');
  assert.equal(call.header.Authorization, 'Bearer synthetic-member');
  assert.equal(call.header['x-wx-openid'], undefined);
  assert.equal(call.followRedirect, false);
  assert.equal(h.calls.filter(x => x.kind === 'http').length, 0);
});

test('native mutations retain JSON empty values and initialize the SDK once', () => {
  const h = harness();
  const data = { nickname: '', cancel: false, count: 0 };
  h.transport.request({ path: '/api/auth/profile', method: 'PUT', data });
  h.transport.request({ path: '/api/courses', method: 'GET' });
  assert.equal(h.calls.find(x => x.kind === 'native').value.data, data);
  assert.equal(h.calls.filter(x => x.kind === 'init').length, 1);
});

test('incomplete native config, unsupported SDK and foreign paths never fall back to HTTP', () => {
  for (const scenario of ['environment', 'service', 'sdk', 'foreign', 'protocol-relative']) {
    const h = harness(); let failed = false, completed = 0;
    if (scenario === 'environment') h.app.globalData.cloudEnvironment = '';
    if (scenario === 'service') h.app.globalData.cloudService = '';
    if (scenario === 'sdk') delete h.wx.cloud;
    h.transport.request({ path: scenario === 'foreign' ? 'https://untrusted.example.test/api' : scenario === 'protocol-relative' ? '//untrusted.example.test/api' : '/api/courses',
      fail: () => { failed = true; }, complete: () => { completed++; } });
    assert.equal(failed, true, scenario); assert.equal(completed, 1, scenario);
    assert.equal(h.calls.length, 0, scenario);
  }
});

test('native file uploads reject without filesystem capability and never fall back to HTTP', async () => {
  const h = harness(); let result;
  h.transport.upload({ path: '/api/upload', filePath: 'synthetic.png', header: { Authorization: 'Bearer synthetic' }, fail: error => { result = error; } });
  assert.match(result.message, /不能读取图片/);
  assert.equal(h.calls.length, 0);
  for (const relative of ['utils/request.js', 'admin/api.js', 'coach-end/api.js']) {
    h.storage.set('token', 'synthetic-member'); h.storage.set('adminToken', 'synthetic-admin'); h.storage.set('coachToken', 'synthetic-coach');
    await assert.rejects(h.load(relative).upload('/api/upload', 'synthetic.png'), error => /不能读取图片/.test(error.message));
  }
  assert.equal(h.calls.length, 0);
});

test('HTTP local mode keeps its request and multipart upload APIs', () => {
  const h = harness('http');
  h.transport.request({ path: '/api/courses', method: 'GET', data: { page: 0 } });
  h.transport.upload({ path: '/api/upload', filePath: 'synthetic.png', name: 'file', formData: { purpose: 'PUBLIC_ASSET' } });
  assert.equal(h.calls[0].kind, 'http'); assert.equal(h.calls[0].value.url, 'http://127.0.0.1:8090/api/courses');
  assert.equal(h.calls[1].kind, 'upload'); assert.equal(h.calls[1].value.formData.purpose, 'PUBLIC_ASSET');
});

for (const [relative, key] of [['utils/request.js', 'token'], ['admin/api.js', 'adminToken'], ['coach-end/api.js', 'coachToken']]) {
  test(relative + ' routes native requests with only its own session', async () => {
    const h = harness();
    h.storage.set('token', 'synthetic-member'); h.storage.set('adminToken', 'synthetic-admin'); h.storage.set('coachToken', 'synthetic-coach');
    const pending = h.load(relative).get('/api/synthetic');
    const call = h.calls.find(x => x.kind === 'native').value;
    assert.equal(call.header.Authorization, 'Bearer ' + h.storage.get(key));
    call.success({ statusCode: 200, data: { code: 0, data: { ok: true } } }); call.complete && call.complete();
    assert.equal((await pending).ok, true);
  });
}

test('native member session expiry performs a fresh WeChat exchange and retries the original request once', async () => {
  const h = harness(); h.storage.set('token', 'synthetic-old'); h.storage.set('adminToken', 'synthetic-other-role');
  const http = h.load('utils/request.js');
  const pending = http.get('/api/my/bookings');
  h.calls.find(x => x.kind === 'native').value.success({ statusCode: 401, data: { code: 40100 } });
  let native = h.calls.filter(x => x.kind === 'native');
  assert.equal(native[1].value.path, '/api/auth/wx-login'); assert.equal(h.storage.has('adminToken'), false);
  native[1].value.success({ statusCode: 200, data: { code: 0, data: { token: 'synthetic-fresh', roles: ['MEMBER'] } } });
  await new Promise(resolve => setImmediate(resolve));
  native = h.calls.filter(x => x.kind === 'native');
  assert.equal(native.length, 3); assert.equal(native[2].value.header.Authorization, 'Bearer synthetic-fresh');
  native[2].value.success({ statusCode: 401, data: { code: 40100 } });
  await assert.rejects(pending);
  assert.equal(h.calls.filter(x => x.kind === 'native').length, 3);
});

test('accepted cloud test configuration enables native develop/trial and keeps release closed', () => {
  const { resolveEnvironment } = require('../config/environment');
  for (const version of ['develop', 'trial']) {
    const config = resolveEnvironment(version);
    assert.equal(config.transport, 'cloudrun');
    assert.equal(config.cloudEnvironment, 'uni-test-d5gwbyymua6bf2ada');
    assert.equal(config.cloudService, 'uni-test');
    assert.equal(config.useMockLogin, false);
    assert.equal(config.baseUrl, '');
    assert.ok(!config.error);
  }
  for (const version of ['release', 'unknown']) {
    const config = resolveEnvironment(version);
    assert.equal(config.baseUrl, ''); assert.equal(config.useMockLogin, false); assert.ok(config.error);
  }
});

test('native HTTP failure cannot be accepted as a successful session and preserves business rejection', () => {
  const h = harness(); let successful = 0, failed = 0, business;
  h.transport.request({ path: '/api/auth/wx-login', method: 'POST', success: response => { successful++; business = response.data; }, fail: () => { failed++; } });
  const call = h.calls.find(x => x.kind === 'native').value;
  call.success({ statusCode: 502, data: { code: 0, data: { token: 'must-not-accept' } } });
  assert.equal(successful, 0); assert.equal(failed, 1);
  call.success({ statusCode: 401, data: { code: 40100, message: 'synthetic-expired' } });
  assert.equal(business.code, 40100);
});

test('staff login page uses native transport and stores only the matching role identity', () => {
  const h = harness(); let definition;
  const module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../pages/login/login.js'), 'utf8'),
    { module, wx: h.wx, getApp: () => h.app, Page: value => { definition = value; }, setTimeout,
      require: name => name.includes('transport') ? h.transport : { clearAuth() {}, captureAuthContext() { return {}; }, isAuthContextCurrent() { return true; } } });
  const page = { ...definition, data: { agreed: true, username: 'synthetic-owner', password: 'synthetic-password' }, setData(value) { Object.assign(this.data, value); } };
  page.accountLogin();
  const call = h.calls.find(x => x.kind === 'native').value;
  assert.equal(call.path, '/api/auth/admin-login');
  assert.equal(call.data.username, 'synthetic-owner');
  call.success({ statusCode: 200, data: { code: 0, data: { token: 'synthetic-admin', roles: ['ADMIN'], permissions: [] } } });
  call.complete();
  assert.equal(h.storage.get('adminToken'), 'synthetic-admin');
  assert.equal(h.storage.has('token'), false); assert.equal(h.storage.has('coachToken'), false);
  assert.equal(page.data.password, ''); assert.equal(page.data.loggingIn, false);
});

test('native configuration candidate disables Mock and rejects incomplete service identity', () => {
  const original = fs.readFileSync(path.join(__dirname, '../config/environment.js'), 'utf8');
  for (const service of ['synthetic-service', '']) {
    const source = original.replace(/trial: \{[^\n]*\}/, "trial: { transport:'cloudrun', cloudEnvironment:'isolated-test', cloudService:'" + service + "', useMockLogin:true, mockCode:'must-not-use' }");
    const module = { exports: {} }; vm.runInNewContext(source, { module });
    const config = module.exports.resolveEnvironment('trial');
    assert.equal(config.useMockLogin, false); assert.equal(config.mockCode, '');
    assert.equal(Boolean(config.error), !service);
  }
});
