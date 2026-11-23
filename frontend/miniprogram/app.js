const { resolveEnvironment } = require('./config/environment');
let version = 'release';
try { version = wx.getAccountInfoSync().miniProgram.envVersion; } catch (_) { }
const ENV = resolveEnvironment(version);
App({
  globalData: { baseUrl: ENV.baseUrl, environment: ENV.name, environmentError: ENV.error || '',
    transport: ENV.transport, cloudEnvironment: ENV.cloudEnvironment || '', cloudService: ENV.cloudService || '',
    token: '', userType: '', userInfo: null, useMockLogin: ENV.useMockLogin, mockCode: ENV.mockCode || '' },
  onLaunch() {
    const identity = [ENV.name, ENV.transport, ENV.baseUrl, ENV.cloudEnvironment || '', ENV.cloudService || ''].join(':');
    if (wx.getStorageSync('studioEnvironment') !== identity) {
      require('./utils/request').clearAuth();
      wx.setStorageSync('studioEnvironment', identity);
    }
    this.globalData.token = wx.getStorageSync('token') || '';
    this.globalData.userType = wx.getStorageSync('userType') || '';
  }
});
