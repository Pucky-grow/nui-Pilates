const api = require('../../services/api');
Page({
  data: { course: null, schedules: [], reviews: [], loading: true, error: '', bookingMode: '', waitlistEnabled: false },
  onLoad(options) { this.id = options.id; },
  onShow() { return this.load(); },
  load() {
    this.setData({ loading: true, error: '' });
    return Promise.all([api.courseDetail(this.id), api.courseSchedules(this.id), api.bookingRules()])
      .then(([course, schedules, rules]) => { this.setData({ course, schedules, bookingMode: rules.bookingMode, waitlistEnabled: rules.waitlistEnabled === true, loading: false }); return api.courseReviews(this.id).then(d => this.setData({ reviews: d.list })).catch(() => {}); })
      .catch(e => this.setData({ loading: false, error: (e && e.message) || '课程加载失败，请重试' }));
  },
  goBooking(e) {
    const id = e.currentTarget.dataset.id;
    const schedule = this.data.schedules.find(s => s.id == id);
    if (!schedule || (!schedule.bookable && !(this.data.waitlistEnabled && schedule.bookingStatus === 'FULL'))) return;
    wx.navigateTo({ url: '/pages/booking-confirm/booking-confirm?scheduleId=' + id });
  }
});
