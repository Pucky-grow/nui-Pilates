// pages/profile/profile.js —— 个人中心
const api = require('../../services/api');
const http = require('../../utils/request');

Page({
  data: {
    phoneEnabled: false, studioLedger: false, profile: null, points: 0, unread: 0, isCoach: false, identity: '',
    showNickname: false, nicknameInput: '', nicknameLen: 0,
    showPhone: false, phoneInput: '', codeInput: '', countdown: 0, sentCode: ''
  },
  onShow() {
    if (!http.ensureLogin()) return;
    http.get('/api/capabilities').then(c => this.setData({ phoneEnabled: c.phoneBinding, studioLedger: c.studioLedger === true })).catch(() => this.setData({ phoneEnabled: false, studioLedger: false }));
    this.loadProfile();
    this.loadPoints();
    this.loadUnread();
    this.checkCoach();
    this.computeIdentity();
  },
  onUnload() {
    if (this.timer) clearInterval(this.timer);
  },
  noop() {},
  loadProfile() { api.myProfile().then(p => this.setData({ profile: p })); },
  loadPoints() { api.myPoints().then(d => this.setData({ points: d.balance })); },
  loadUnread() { api.unreadCount().then(d => this.setData({ unread: d.count })); },
  checkCoach() {
    const roles = wx.getStorageSync('roles') || [];
    const coachToken = wx.getStorageSync('coachToken');
    this.setData({ isCoach: roles.includes('COACH') || !!coachToken });
  },
  computeIdentity() {
    const roles = wx.getStorageSync('roles') || [];
    const coachToken = wx.getStorageSync('coachToken');
    const coachName = wx.getStorageSync('coachName');
    const nickname = (this.data.profile && this.data.profile.nickname) || '未登录';
    let identity;
    if (coachToken) identity = '教练：' + (coachName || '教练账号');
    else if (roles.includes('COACH')) identity = '教练：' + nickname;
    else identity = '学员：' + nickname;
    this.setData({ identity });
  },
  logout() {
    wx.showModal({
      title: '退出登录', content: '确定退出当前账号吗？',
      success(r) {
        if (r.confirm) { http.clearAuth(); wx.reLaunch({ url: '/pages/login/login' }); }
      }
    });
  },
  switchAccount() {
    http.clearAuth();
    wx.reLaunch({ url: '/pages/login/login' });
  },

  // ===== 昵称弹窗 =====
  editNickname() {
    const cur = (this.data.profile && this.data.profile.nickname) || '';
    this.setData({ showNickname: true, nicknameInput: cur, nicknameLen: cur.length });
  },
  onNicknameInput(e) {
    const v = e.detail.value;
    this.setData({ nicknameInput: v, nicknameLen: v.length });
  },
  cancelNickname() { this.setData({ showNickname: false }); },
  saveNickname() {
    const nickname = (this.data.nicknameInput || '').trim();
    if (!nickname) return;
    api.updateProfile({ nickname }).then(() => {
      wx.showToast({ title: '昵称修改成功', icon: 'success' });
      this.setData({ showNickname: false });
      this.loadProfile();
    });
  },

  // ===== 头像 =====
  onChooseAvatar(e) {
    const tempUrl = e.detail && e.detail.avatarUrl;
    if (!tempUrl) { wx.showToast({ title: '已取消选择', icon: 'none' }); return; }
    this.uploadAvatar(tempUrl);
  },
  onChooseAlbum() {
    const self = this;
    wx.chooseImage({
      count: 1, sizeType: ['compressed'],
      success(res) {
        if (res.tempFilePaths && res.tempFilePaths[0]) self.uploadAvatar(res.tempFilePaths[0]);
      }
    });
  },
  uploadAvatar(tempUrl) {
    wx.showLoading({ title: '上传中', mask: true });
    http.upload('/api/upload', tempUrl).then((res) => {
      wx.hideLoading();
      const preview = res.previewUrl || res.url;
      const fullUrl = /^https?:\/\//.test(preview) ? preview : getApp().globalData.baseUrl + preview;
      api.updateProfile({ avatarUrl: fullUrl }).then(() => {
        wx.showToast({ title: '头像更新成功', icon: 'success' });
        this.loadProfile();
      });
    }).catch(() => wx.hideLoading());
  },

  // ===== 手机号 =====
  openBindPhone() { wx.showToast({ title: '手机号服务暂未开放', icon: 'none' }); },
  bindWechatPhone(e) {
    if (!this.data.phoneEnabled || !e.detail.code) return;
    http.post('/api/auth/bind-phone', { code: e.detail.code }).then(() => this.loadProfile()).catch(() => {});
  },
  // ===== 跳转 =====
  goLedger() { wx.navigateTo({ url: '/pages/studio-ledger/studio-ledger' }); },
  goBookings() { wx.switchTab({ url: '/pages/booking/booking' }); },
  goPackages() { wx.navigateTo({ url: '/pages/package-list/package-list' }); },
  goOrders() { wx.navigateTo({ url: '/pages/order-list/order-list' }); },
  goTraining() { wx.navigateTo({ url: '/pages/training/training' }); },
  goAssessment() { wx.navigateTo({ url: '/pages/assessment/assessment' }); },
  goReviews() { wx.navigateTo({ url: '/pages/review/review' }); },
  goCoupons() { wx.navigateTo({ url: '/pages/coupon/coupon' }); },
  goPoints() { wx.navigateTo({ url: '/pages/points/points' }); },
  goMessage() { wx.navigateTo({ url: '/pages/message/message' }); },
  goFeedback() { wx.navigateTo({ url: '/pages/feedback/feedback' }); },
  goInvite() { wx.navigateTo({ url: '/pages/invite/invite' }); },
  goOfflineClaim() { wx.navigateTo({ url: '/pages/offline-claim/offline-claim?from=profile' }); },
  goCoachEnd() { wx.navigateTo({ url: '/coach-end/dashboard/dashboard' }); }
});
