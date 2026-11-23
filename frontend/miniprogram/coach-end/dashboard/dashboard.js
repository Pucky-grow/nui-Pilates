// coach-end/dashboard/dashboard.js —— 教练工作台
const api = require('../api');
const { formatDate } = require('../../utils/util');

Page({
  data: { today: [], pending: [], coachName: '', loggedIn: false, stats: null },
  onShow() {
    if (!api.getToken()) {
      this.setData({ loggedIn: false });
      return;
    }
    this.setData({ loggedIn: true, coachName: wx.getStorageSync('coachName') || '教练' });
    this.setData({ canHandleLateFees: false });
    if (wx.getStorageSync('coachToken')) {
      api.get('/api/studio/late-fees/access').then(value => this.setData({ canHandleLateFees: value })).catch(() => {});
    }
    api.get('/api/coach-end/dashboard').then(s => this.setData({ stats: s }));
    api.get('/api/coach-end/today').then(list => this.setData({ today: list }));
    api.get('/api/coach-end/pending-records').then(list => this.setData({ pending: list }));
  },
  onLogin(e) {
    const { username, password } = e.detail.value;
    api.login(username, password).then(() => { wx.showToast({ title: '登录成功', icon: 'success' }); this.onShow(); });
  },
  goStudents(e) {
    wx.navigateTo({ url: '/coach-end/students/students?scheduleId=' + e.currentTarget.dataset.id });
  },
  goLateFees() { wx.navigateTo({ url: '/admin/late-fees/late-fees?coach=1' }); },
  goSchedule() { wx.navigateTo({ url: '/coach-end/schedule/schedule' }); },
  goLeave() { wx.navigateTo({ url: '/coach-end/leave/leave' }); },
  goChange() { wx.navigateTo({ url: '/coach-end/change-request/change-request' }); },
  goCourses() { wx.navigateTo({ url: '/coach-end/courses/courses' }); },
  bindWechat() {
    api.bindWechat().then(() => wx.showToast({ title: '绑定成功, 可用微信一键登录', icon: 'success' }));
  },
  unbindWechat() {
    api.unbindWechat().then(() => wx.showToast({ title: '已解绑', icon: 'success' }));
  }
});
