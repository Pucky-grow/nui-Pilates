// pages/login/login.js —— 登录页(微信登录 + 教练/员工登录 + 完善资料弹层)
const http = require('../../utils/request');
const transport = require('../../utils/transport');

Page({
  data: {
    username: '', password: '', agreed: false, showStaffLogin: false, wechatLoggingIn: false, loggingIn: false,
    showProfile: false, studioLedger: false, profileAvatar: '', profileNickname: ''
  },
  onLoad() {
    // 已登录且未超时则直接进入; 超时/无效则清除残留登录态
    const adminToken=wx.getStorageSync('adminToken');
    if(adminToken){wx.reLaunch({url:'/admin/home/home'});return;}
    if (http.isLoginValid()) {
      this.enterApp();
    } else {
      http.clearAuth();
      http.get('/api/capabilities', null, { silent: true, skipAuth: true })
        .then(c => this.setData({ studioLedger: c.studioLedger === true })).catch(() => {});
    }
  },
  onUsername(e) { this.setData({ username: e.detail.value }); },
  onPassword(e) { this.setData({ password: e.detail.value }); },
  toggleAgree() { this.setData({ agreed: !this.data.agreed }); },
  toggleStaffLogin() { this.setData({ showStaffLogin: !this.data.showStaffLogin }); },
  openClaim() {
    if (this.requireAgree()) wx.navigateTo({ url: '/pages/offline-claim/offline-claim' });
  },
  openAgreement() { wx.navigateTo({ url: '/pages/agreement/agreement' }); },
  openPrivacy() { wx.navigateTo({ url: '/pages/privacy/privacy' }); },
  noop() {},

  // 微信一键登录(学员)
  wechatLogin() {
    if (!this.requireAgree()) return;
    if (this.data.wechatLoggingIn || this.data.loggingIn) return;
    this.setData({ wechatLoggingIn: true });
    http.clearAuth();
    return http.login().then((data) => {
      // 新用户或缺少昵称: 弹完善资料(不阻塞预约, 可跳过)
      const nickname = (data && data.nickname) || '';
      if (data && (data.new || !nickname || nickname === '优仪会员')) {
        this.setData({
          showProfile: true,
          profileNickname: nickname === '优仪会员' ? '' : nickname
        });
      } else {
        this.enterApp();
      }
    }).catch((error) => {
      if (!error || error.code !== 'AUTH_CONTEXT_CHANGED') {
        // 上游异常可能包含请求和身份信息，只展示固定提示。
        wx.showToast({ title: '微信登录暂未成功，请稍后重试', icon: 'none' });
      }
    }).finally(() => this.setData({ wechatLoggingIn: false }));
  },

  // 教练/员工账号密码登录
  accountLogin() {
    if (!this.requireAgree()) return;
    const { username, password } = this.data;
    if (!username || !password) {
      wx.showToast({ title: '请输入账号和密码', icon: 'none' });
      return;
    }
    if(this.data.loggingIn)return;
    try { transport.settings(); } catch (error) { wx.showToast({title:error.message,icon:'none'}); return; }
    this.setData({loggingIn:true});
    http.clearAuth();
    const context = http.captureAuthContext();
    const page=this;
    transport.request({
      path: '/api/auth/admin-login',
      method: 'POST',
      data: { username, password },
      header: { 'Content-Type': 'application/json' },
      success(res) {
        if (!http.isAuthContextCurrent(context)) return;
        const b = res.data;
        if (b && b.code === 0 && b.data && typeof b.data.token === 'string' && b.data.token) {
          const d = b.data;
          const roles = d.roles || [];
          if (roles.includes('ADMIN') || roles.includes('STORE_MANAGER')) {
            // 管理员/店长 → 工作台(按权限显示入口)
            wx.setStorageSync('adminToken', d.token);
            wx.setStorageSync('adminLoginAt', Date.now());
            wx.setStorageSync('adminName', d.name);
            wx.setStorageSync('adminRoles', roles);
            wx.setStorageSync('adminPerms', d.permissions || []);
            wx.reLaunch({ url: '/admin/home/home' });
          } else if (roles.includes('COACH')) {
            // 教练 → 教练工作台
            wx.setStorageSync('coachToken', d.token);
            wx.setStorageSync('coachName', d.name);
            wx.navigateTo({ url: '/coach-end/dashboard/dashboard' });
          } else if (roles.includes('STAFF')) {
            wx.showToast({ title: '前台请使用 Web 管理后台', icon: 'none' });
          } else {
            wx.showToast({ title: '当前账号无小程序管理权限', icon: 'none' });
          }
        } else {
          wx.showToast({ title: (b && b.message) || '登录失败', icon: 'none' });
        }
      },
      fail() { if (http.isAuthContextCurrent(context)) wx.showToast({ title: '服务连接失败，请检查测试地址或网络', icon: 'none' }); },
      complete() { page.setData({loggingIn:false,password:''}); }
    });
  },

  // 完善资料: 点头像换微信头像
  onChooseAvatar(e) {
    const tempUrl = e.detail && e.detail.avatarUrl;
    if (!tempUrl) return;
    wx.showLoading({ title: '上传中', mask: true });
    http.upload('/api/upload', tempUrl).then((res) => {
      wx.hideLoading();
      const preview = res.previewUrl || res.url;
      this.setData({ profileAvatar: /^https?:\/\//.test(preview) ? preview : getApp().globalData.baseUrl + preview });
    }).catch(() => wx.hideLoading());
  },
  onNicknameInput(e) { this.setData({ profileNickname: e.detail.value }); },
  saveProfile() {
    const payload = {};
    if (this.data.profileAvatar) payload.avatarUrl = this.data.profileAvatar;
    const nickname = (this.data.profileNickname || '').trim();
    if (nickname) payload.nickname = nickname;
    const done = () => this.enterApp();
    if (Object.keys(payload).length === 0) { done(); return; }
    http.put('/api/auth/profile', payload).then(done).catch(done);
  },
  skipProfile() { this.enterApp(); },

  // 登录前必须勾选同意协议
  requireAgree() {
    if (!this.data.agreed) {
      wx.showToast({ title: '请先阅读并同意用户协议和隐私政策', icon: 'none' });
      return false;
    }
    return true;
  },

  enterApp() {
    setTimeout(() => wx.switchTab({ url: '/pages/index/index' }), 0);
  }
});
