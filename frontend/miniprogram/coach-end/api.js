// coach-end/api.js —— 教练端接口
const app = getApp();
const transport = require('../utils/transport');

function getToken() {
  const stored = wx.getStorageSync('coachToken');
  if (stored) return stored;
  const roles = wx.getStorageSync('roles') || [];
  return roles.includes('COACH') ? (wx.getStorageSync('token') || '') : '';
}
function request(method, path, data, requestKey) {
  return new Promise((resolve, reject) => {
    try { transport.settings(); } catch (error) { reject(error); return; }
    const token = getToken();
    transport.request({
      path, method, data,
      header: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token, ...(requestKey ? { 'X-Studio-Command': requestKey } : {}) },
      success(res) {
        const b = res.data;
        if (b && b.code === 0) resolve(b.data);
        else if (b && b.code === 40100) {
          const wasMemberToken = !wx.getStorageSync('coachToken');
          ['coachToken', 'coachName', 'coachRoles', 'coachLoginAt'].forEach(k => wx.removeStorageSync(k));
          if (wasMemberToken) { require('../utils/request').clearAuth(); wx.reLaunch({ url: '/pages/login/login' }); }
          wx.showToast({ title: '登录已过期，请重新登录', icon: 'none' }); reject(b);
        }
        else { wx.showToast({ title: (b && b.message) || '请求失败', icon: 'none' }); reject(b); }
      },
      fail: reject
    });
  });
}

function login(username, password) {
  const auth = require('../utils/request');
  auth.clearAuth();
  const context = auth.captureAuthContext();
  return request('POST', '/api/auth/admin-login', { username, password }).then(data => {
    if (!auth.isAuthContextCurrent(context)) throw { code: 'AUTH_CONTEXT_CHANGED', message: '登录状态或服务环境已变化，请重试' };
    if (!(data.roles || []).includes('COACH') && !(data.roles || []).includes('ADMIN')) throw new Error('请使用教练账号');
    wx.setStorageSync('coachToken', data.token);
    wx.setStorageSync('coachLoginAt', Date.now());
    wx.setStorageSync('coachRoles', data.roles || []);
    wx.setStorageSync('coachName', data.name);
    return data;
  });
}

module.exports = {
  request, login, getToken,
  get: (p, d) => request('GET', p, d),
  post: (p, d, key) => request('POST', p, d, key),
  put: (p, d) => request('PUT', p, d),
  del: (p, d) => request('DELETE', p, d),
  // 文件上传(封面等), 走 wx.uploadFile 携带 coachToken
  upload: (path, filePath) => new Promise((resolve, reject) => {
    try { transport.settings(); } catch (error) { reject(error); return; }
    const token = getToken();
    transport.upload({
      path, filePath, name: 'file',
      formData: { purpose: 'PUBLIC_ASSET' },
      header: token ? { Authorization: 'Bearer ' + token } : {},
      success(res) {
        let body = null;
        try { body = JSON.parse(res.data); } catch (e) { /* ignore */ }
        if (body && body.code === 0) resolve(body.data.previewUrl || body.data.url);
        else { wx.showToast({ title: (body && body.message) || '上传失败', icon: 'none' }); reject(body); }
      },
      fail: reject
    });
  }),
  // 绑定微信: 用 wx.login 的 code 绑定当前教练账号, 之后可微信一键登录
  bindWechat: () => new Promise((resolve, reject) => {
    wx.login({ success: r => request('POST', '/api/coach-end/bind-wechat', { code: r.code }).then(resolve).catch(reject), fail: reject });
  }),
  unbindWechat: () => request('POST', '/api/coach-end/unbind-wechat')
};
