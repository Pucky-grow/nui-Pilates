const api = require('../../services/api');
const http = require('../../utils/request');
Page({
  data: { schedule: null, remark: '', submitting: false, loading: true, error: '', bookingMode: '', waitlistEnabled: false },
  onLoad(options) { this.scheduleId = options.scheduleId; },
  onShow() { return http.login().then(() => this.load()).catch(e => this.setData({ loading: false, error: (e && e.message) || '登录失败，请重试' })); },
  load(keepError = false) {
    this.setData({ loading: true, ...(keepError ? {} : { error: '' }) });
    return Promise.all([api.bookingRules(), api.scheduleDetail(this.scheduleId)])
      .then(([rules, schedule]) => this.setData({ schedule, bookingMode: rules.bookingMode, waitlistEnabled: rules.waitlistEnabled === true, loading: false }))
      .catch(e => this.setData({ loading: false, error: (e && e.message) || '排课加载失败，请重试' }));
  },
  retry() { return this.onShow(); },
  onRemark(e) { this.setData({ remark: e.detail.value }); },
  submit() {
    if (this.data.submitting || this.data.loading || !this.data.schedule || !this.data.schedule.bookable) return;
    this.setData({ submitting: true, error: '' });
    return api.createBooking({ scheduleId: Number(this.scheduleId), remark: this.data.remark })
      .then(d => { wx.showToast({ title: '预约成功', icon: 'success' }); wx.redirectTo({ url: '/pages/booking-detail/booking-detail?id=' + d.bookingId }); })
      .catch(e => { this.setData({ error: (e && e.message) || '预约失败，请重试' }); return this.load(true); })
      .finally(() => this.setData({ submitting: false }));
  },
  joinWaitlist() {
    if (!this.data.waitlistEnabled || this.data.loading || this.data.submitting || this.data.schedule?.bookingStatus !== 'FULL') return;
    this.setData({ submitting: true });
    return api.joinWaitlist(Number(this.scheduleId)).then(() => { wx.showToast({ title: '已加入候补', icon: 'success' }); wx.navigateBack(); })
      .catch(e => { this.setData({ error: (e && e.message) || '候补失败' }); return this.load(true); })
      .finally(() => this.setData({ submitting: false }));
  }
});
