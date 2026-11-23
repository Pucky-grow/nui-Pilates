// utils/request.js —— 统一请求封装(自动注入 token, 统一错误处理)
const transport = require('./transport');

// 登录保持时长: 2 小时, 超时自动退出重新登录
const LOGIN_TTL = 2 * 60 * 60 * 1000;
let loginFlight = null;

function captureAuthContext() {
  const value = getApp().globalData;
  return {
    epoch: Number.isSafeInteger(value.authEpoch) ? value.authEpoch : 0,
    target: JSON.stringify([value.environment || '', value.transport || 'http', value.baseUrl || '',
      value.cloudEnvironment || '', value.cloudService || '', !!value.useMockLogin, value.mockCode || ''])
  };
}

function isAuthContextCurrent(context) {
  const current = captureAuthContext();
  return context.epoch === current.epoch && context.target === current.target;
}

function authChanged() {
  return { code: 'AUTH_CONTEXT_CHANGED', message: '登录状态或服务环境已变化，请重试' };
}

function clearAuth() {
  const app = getApp();
  if (app) {
    const epoch = Number.isSafeInteger(app.globalData.authEpoch) ? app.globalData.authEpoch : 0;
    Object.assign(app.globalData, { authEpoch: epoch + 1, token: '', userType: '', userInfo: null });
  }
  loginFlight = null;
  ['token', 'roles', 'coachId', 'userType', 'coachToken', 'coachName', 'loginAt', 'coachLoginAt', 'coachRoles', 'adminToken', 'adminRoles', 'adminPerms', 'adminName', 'adminLoginAt']
    .forEach(k => wx.removeStorageSync(k));
}

// 登录态是否有效(有 token 且未超过 2 小时)
function isLoginValid() {
  const token = wx.getStorageSync('token');
  const loginAt = wx.getStorageSync('loginAt');
  return !!token && !!loginAt && (Date.now() - loginAt < LOGIN_TTL);
}

// 校验登录态: 有效返回 true; 否则清除并跳登录页, 返回 false
function ensureLogin() {
  if (isLoginValid()) return true;
  clearAuth();
  // 延迟到下一事件循环, 避免在页面生命周期早期同步导航导致框架报错
  setTimeout(() => wx.reLaunch({ url: '/pages/login/login' }), 0);
  return false;
}

function request(method, path, data, opts = {}) {
  return new Promise((resolve, reject) => {
    const app = getApp();
    try { transport.settings(); } catch (error) { reject(error); return; }
    const context = captureAuthContext();
    const token = wx.getStorageSync('token') || '';
    // 过滤 undefined/null/空字符串, 避免 wx.request 序列化为 "undefined" 导致后端参数类型转换失败
    const cleanData = {};
    if (data) {
      Object.keys(data).forEach(k => {
        const v = data[k];
        if (v !== undefined && v !== null && v !== '') {
          cleanData[k] = v;
        }
      });
    }
    transport.request({
      path,
      method,
      data: cleanData,
      header: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: 'Bearer ' + token } : {})
      },
      success(res) {
        if (!isAuthContextCurrent(context)) { reject(authChanged()); return; }
        const body = res.data;
        if (body && body.code === 0) {
          resolve(body.data);
        } else if (body && body.code === 40100) {
          // 登录过期, 静默重新登录(小程序端)
          if (!opts.skipAuth) {
            clearAuth();
            login().then(() => request(method, path, data, { ...opts, skipAuth: true })
              .then(resolve).catch(reject));
          } else {
            reject(body);
          }
        } else {
          const msg = (body && body.message) || '网络开小差了, 请稍后再试';
          if (!opts.silent) {
            wx.showToast({ title: msg, icon: 'none' });
          }
          reject(body || { message: msg });
        }
      },
      fail(err) {
        if (!isAuthContextCurrent(context)) { reject(authChanged()); return; }
        const msg = /127\.0\.0\.1|localhost/.test(app.globalData.baseUrl) ? '本地服务连接失败，真机需配置可访问的测试地址' : '网络异常, 请检查网络';
        if (!opts.silent) {
          wx.showToast({ title: msg, icon: 'none' });
        }
        reject({ message: err && err.message || msg, errMsg: err && err.errMsg });
      }
    });
  });
}

function requestWechatCode(context, resolve, reject) {
  wx.login({
    success(res) {
      if (!isAuthContextCurrent(context)) { reject(authChanged()); return; }
      const { useMockLogin, mockCode } = getApp().globalData;
      const code = useMockLogin ? mockCode : res.code;
      if (code) resolve(code);
      else reject({ message: '微信未返回登录凭证，请重试' });
    },
    fail() { reject(isAuthContextCurrent(context) ? { message: '微信登录未完成，请重试' } : authChanged()); }
  });
}

function wechatCode(context = captureAuthContext()) {
  return new Promise((resolve, reject) => requestWechatCode(context, resolve, reject));
}

// 微信登录
function login(options = {}) {
  const context = captureAuthContext();
  if (loginFlight && isAuthContextCurrent(loginFlight.context)) return loginFlight.promise;
  if (!options.force && isLoginValid()) return Promise.resolve({
    token: wx.getStorageSync('token'), roles: wx.getStorageSync('roles') || [], coachId: wx.getStorageSync('coachId')
  });
  const promise = new Promise((resolve, reject) => {
    const app = getApp();
    try { transport.settings(); } catch (error) { reject(error); return; }
    requestWechatCode(context, code => {
      request('POST', '/api/auth/wx-login', { code }, { silent: true, skipAuth: true }).then(data => {
        if (!isAuthContextCurrent(context)) { reject(authChanged()); return; }
        if (!data || typeof data.token !== 'string' || !data.token) {
          reject({ message: '登录服务响应异常，请稍后重试' }); return;
        }
        wx.setStorageSync('token', data.token);
        wx.setStorageSync('userType', data.userType);
        wx.setStorageSync('roles', data.roles || []);
        wx.setStorageSync('coachId', data.coachId || null);
        wx.setStorageSync('loginAt', Date.now());
        app.globalData.token = data.token;
        app.globalData.userType = data.userType;
        resolve(data);
      }).catch(reject);
    }, reject);
  });
  const flight = { context, promise };
  loginFlight = flight;
  const complete = () => { if (loginFlight === flight) loginFlight = null; };
  promise.then(complete, complete);
  return promise;
}

// 文件上传(头像等), 走 wx.uploadFile 并携带 token
function upload(path, filePath) {
  return new Promise((resolve, reject) => {
    const app = getApp();
    try { transport.settings(); } catch (error) { reject(error); return; }
    const context = captureAuthContext();
    const token = wx.getStorageSync('token') || '';
    transport.upload({
      path,
      filePath,
      name: 'file',
      header: token ? { Authorization: 'Bearer ' + token } : {},
      success(res) {
        if (!isAuthContextCurrent(context)) { reject(authChanged()); return; }
        let body = null;
        try { body = JSON.parse(res.data); } catch (e) { /* ignore */ }
        if (body && body.code === 0) {
          resolve(body.data);
        } else if (body && body.code === 40100) {
          clearAuth();
          setTimeout(() => wx.reLaunch({ url: '/pages/login/login' }), 0);
          reject(body);
        } else {
          const msg = (body && body.message) || '上传失败, 请重试';
          wx.showToast({ title: msg, icon: 'none' });
          reject(body || { message: msg });
        }
      },
      fail(err) {
        if (!isAuthContextCurrent(context)) { reject(authChanged()); return; }
        const msg = err && err.message || (/127\.0\.0\.1|localhost/.test(app.globalData.baseUrl) ? '本地服务连接失败，真机需配置可访问的测试地址' : '网络异常, 请检查网络');
        wx.showToast({ title: msg, icon: 'none' });
        reject({ message: msg, errMsg: err && err.errMsg });
      }
    });
  });
}

module.exports = {
  request,
  upload,
  login,
  wechatCode,
  ensureLogin,
  isLoginValid,
  clearAuth,
  captureAuthContext,
  isAuthContextCurrent,
  get: (path, data, opts) => request('GET', path, data, opts),
  post: (path, data, opts) => request('POST', path, data, opts),
  put: (path, data, opts) => request('PUT', path, data, opts),
  del: (path, data, opts) => request('DELETE', path, data, opts)
};
