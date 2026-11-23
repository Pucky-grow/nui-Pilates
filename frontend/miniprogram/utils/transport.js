// HTTP 与微信云托管共用传输层；身份和业务错误仍由各端封装处理。
let initializedCloud = null;
let initializedEnvironment = '';
const MAX_NATIVE_FILE_BYTES = 500 * 1024;
const MAX_NATIVE_BODY_BYTES = 512 * 1024;
let uploadSequence = 0;

function settings() {
  const value = getApp().globalData;
  const mode = value.transport || 'http';
  if (mode === 'cloudrun') {
    if (!/^[a-zA-Z0-9_-]+$/.test(value.cloudEnvironment || '') || !/^[a-zA-Z0-9_-]+$/.test(value.cloudService || '')) {
      throw { message: value.environmentError || '请先配置云托管环境和服务' };
    }
  } else if (mode !== 'http' || !value.baseUrl) {
    throw { message: value.environmentError || '服务地址未配置' };
  }
  return { ...value, transport: mode };
}

function checkPath(path) {
  if (typeof path !== 'string' || !/^\/(?!\/)/.test(path) || /[\\\r\n#]/.test(path)) {
    throw { message: '服务请求路径无效' };
  }
}

function fail(options, error) {
  options.fail && options.fail(error);
  options.complete && options.complete(error);
}

function queryPath(path, data) {
  if (!data) return path;
  const query = [];
  Object.keys(data).forEach(key => {
    const value = data[key];
    if (value === undefined || value === null || value === '') return;
    if (!['string', 'number', 'boolean'].includes(typeof value)) throw { message: '查询参数必须是文字、数字或布尔值' };
    query.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(value)));
  });
  return query.length ? path + (path.includes('?') ? '&' : '?') + query.join('&') : path;
}

function request(options) {
  let config;
  try { config = settings(); checkPath(options.path); } catch (error) { fail(options, error); return; }
  const { path, ...rest } = options;
  if (config.transport === 'http') return wx.request({ ...rest, url: config.baseUrl + path });
  try {
    if (!wx.cloud || typeof wx.cloud.callContainer !== 'function' || typeof wx.cloud.init !== 'function') {
      throw { message: '当前微信版本不支持云托管，请升级微信后重试' };
    }
    const method = (options.method || 'GET').toUpperCase();
    const noBody = method === 'GET' || method === 'HEAD';
    const nativePath = noBody ? queryPath(path, options.data) : path;
    if (initializedCloud !== wx.cloud || initializedEnvironment !== config.cloudEnvironment) {
      wx.cloud.init({ env: config.cloudEnvironment, traceUser: false });
      initializedCloud = wx.cloud;
      initializedEnvironment = config.cloudEnvironment;
    }
    const header = {};
    Object.keys(options.header || {}).forEach(key => {
      // 微信身份头只能由平台注入，调用方不能自行伪造。
      if (!/^x-wx-/i.test(key)) header[key] = options.header[key];
    });
    return wx.cloud.callContainer({
      ...rest,
      config: { env: config.cloudEnvironment },
      path: nativePath,
      method,
      data: noBody ? undefined : options.data,
      header: { ...header, 'X-WX-SERVICE': config.cloudService },
      dataType: 'json',
      timeout: options.timeout || 15000,
      followRedirect: false,
      success(response) {
        // 只有业务成功与 HTTP 成功同时成立才可接受会话或写入结果。
        if (response.statusCode && (response.statusCode < 200 || response.statusCode >= 300)
            && response.data && response.data.code === 0) {
          options.fail && options.fail({ message: '云托管服务响应异常，请稍后重试' });
          return;
        }
        options.success && options.success(response);
      }
    });
  } catch (_) {
    // SDK 异常可能携带请求/身份细节，只输出固定文案。
    fail(options, { message: '云托管请求未能发起，请检查微信版本和服务配置' });
  }
}

function uploadFields(options) {
  if (options.path !== '/api/upload' || (options.name && options.name !== 'file')) {
    throw { message: '图片上传接口未配置' };
  }
  const form = options.formData || {};
  if (Object.keys(form).some(key => !['purpose', 'ownerId'].includes(key))) throw { message: '图片上传参数无效' };
  const purpose = form.purpose === undefined ? 'AVATAR' : form.purpose;
  if (!['AVATAR', 'BODY_RECORD', 'PUBLIC_ASSET'].includes(purpose)) throw { message: '图片上传用途无效' };
  const fields = { purpose };
  if (form.ownerId !== undefined && form.ownerId !== null && form.ownerId !== '') {
    const ownerId = String(form.ownerId);
    if (!/^[1-9][0-9]{0,14}$/.test(ownerId)) throw { message: '会员编号无效' };
    fields.ownerId = ownerId;
  }
  return fields;
}

function imageType(bytes) {
  if (bytes.length >= 8 && [137, 80, 78, 71, 13, 10, 26, 10].every((value, index) => bytes[index] === value)) return { extension: 'png', type: 'image/png' };
  if (bytes.length >= 3 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) return { extension: 'jpg', type: 'image/jpeg' };
  if (bytes.length >= 6 && [71, 73, 70, 56].every((value, index) => bytes[index] === value)
      && [55, 57].includes(bytes[4]) && bytes[5] === 97) return { extension: 'gif', type: 'image/gif' };
  throw { message: '请选择 JPG、PNG 或 GIF 图片' };
}

function ascii(value) {
  return Uint8Array.from(value, character => character.charCodeAt(0));
}

function containsBytes(bytes, pattern) {
  for (let offset = 0; offset <= bytes.length - pattern.length; offset++) {
    let index = 0;
    while (index < pattern.length && bytes[offset + index] === pattern[index]) index++;
    if (index === pattern.length) return true;
  }
  return false;
}

function multipart(bytes, fields) {
  const type = imageType(bytes);
  let boundary;
  for (let attempt = 0; attempt < 3; attempt++) {
    boundary = 'YoyiImage' + Date.now().toString(36) + (++uploadSequence).toString(36) + Math.random().toString(36).slice(2, 12);
    if (!containsBytes(bytes, ascii(boundary))) break;
    boundary = '';
  }
  if (!boundary) throw { message: '图片内容无法上传，请重新选择图片' };
  let prefix = '';
  Object.keys(fields).forEach(key => {
    prefix += '--' + boundary + '\r\nContent-Disposition: form-data; name="' + key + '"\r\n\r\n' + fields[key] + '\r\n';
  });
  prefix += '--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="upload.' + type.extension + '"\r\nContent-Type: ' + type.type + '\r\n\r\n';
  const head = ascii(prefix), tail = ascii('\r\n--' + boundary + '--\r\n');
  const length = head.length + bytes.length + tail.length;
  if (length > MAX_NATIVE_BODY_BYTES) throw { message: '图片过大，请压缩至500KB以内' };
  const data = new Uint8Array(length);
  data.set(head); data.set(bytes, head.length); data.set(tail, head.length + bytes.length);
  return { data: data.buffer, contentType: 'multipart/form-data; boundary=' + boundary };
}

function nativeUpload(options, config) {
  let fields, fs, authorization;
  let finished = false, completed = false;
  const epoch = config.authEpoch || 0;
  const stale = () => ({ code: 'UPLOAD_CONTEXT_CHANGED', message: '登录状态或服务环境已变化，请重新选择图片' });
  function sameContext() {
    let current;
    try { current = settings(); } catch (_) { return false; }
    return (current.authEpoch || 0) === epoch
      && ['environment', 'transport', 'baseUrl', 'cloudEnvironment', 'cloudService'].every(key => (current[key] || '') === (config[key] || ''));
  }
  function completeOnce(value) {
    if (completed) return;
    completed = true;
    options.complete && options.complete(value);
  }
  function rejectOnce(error) {
    if (finished) return;
    finished = true;
    try { options.fail && options.fail(error); } finally { completeOnce(error); }
  }
  try {
    fields = uploadFields(options);
    authorization = Object.entries(options.header || {}).find(([key]) => key.toLowerCase() === 'authorization');
    if (!authorization || !/^Bearer [^\r\n ]+$/.test(authorization[1])) throw { code: 40100, message: '请先登录后上传图片' };
    if (!options.filePath || typeof options.filePath !== 'string' || typeof wx.getFileSystemManager !== 'function') {
      throw { message: '当前微信暂不能读取图片，请升级微信或跳过头像' };
    }
    fs = wx.getFileSystemManager();
    if (!fs || typeof fs.stat !== 'function' || typeof fs.readFile !== 'function') throw { message: '当前微信暂不能读取图片，请升级微信或跳过头像' };
  } catch (error) { rejectOnce(error); return; }
  const readFailure = () => rejectOnce(sameContext() ? { message: '图片读取失败，请重新选择图片' } : stale());
  try {
    fs.stat({
      path: options.filePath, recursive: false,
      success(info) {
        if (finished) return;
        if (!sameContext()) { rejectOnce(stale()); return; }
        const size = info && info.stats && info.stats.size;
        if (!Number.isSafeInteger(size) || size < 1 || size > MAX_NATIVE_FILE_BYTES) {
          rejectOnce({ message: '图片不能为空，请压缩至500KB以内' }); return;
        }
        try {
          fs.readFile({
            filePath: options.filePath, position: 0, length: size,
            success(result) {
              if (finished) return;
              if (!sameContext()) { rejectOnce(stale()); return; }
              let body;
              try {
                if (!result || Object.prototype.toString.call(result.data) !== '[object ArrayBuffer]') throw { message: '图片读取失败，请重新选择图片' };
                const bytes = new Uint8Array(result.data);
                if (bytes.length !== size || bytes.length > MAX_NATIVE_FILE_BYTES) throw { message: '图片内容发生变化，请重新选择图片' };
                body = multipart(bytes, fields);
              } catch (error) { rejectOnce(error); return; }
              if (!sameContext()) { rejectOnce(stale()); return; }
              request({
                path: options.path, method: 'POST', data: body.data,
                header: { Authorization: authorization[1], 'Content-Type': body.contentType },
                timeout: options.timeout || 30000,
                success(response) {
                  if (finished) return;
                  if (!sameContext()) { rejectOnce(stale()); return; }
                  // 保持与 wx.uploadFile 一致的 JSON 文本响应，业务封装继续校验用途/权限错误。
                  let result;
                  try { result = { ...response, data: typeof response.data === 'string' ? response.data : JSON.stringify(response.data) }; }
                  catch (_) { rejectOnce({ message: '图片上传服务响应异常，请重试' }); return; }
                  finished = true;
                  try { options.success && options.success(result); } finally { completeOnce(result); }
                },
                fail: error => rejectOnce(sameContext() ? error : stale()),
                complete() {
                  if (!finished) rejectOnce(sameContext() ? { message: '图片上传未完成，请重试' } : stale());
                }
              });
            },
            fail: readFailure
          });
        } catch (_) { readFailure(); }
      },
      fail: readFailure
    });
  } catch (_) { readFailure(); }
}

function upload(options) {
  let config;
  try { config = settings(); checkPath(options.path); } catch (error) { fail(options, error); return; }
  if (config.transport === 'cloudrun') {
    return nativeUpload(options, config);
  }
  const { path, ...rest } = options;
  return wx.uploadFile({ ...rest, url: config.baseUrl + path });
}

module.exports = { settings, request, upload };
