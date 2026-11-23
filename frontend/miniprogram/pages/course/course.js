const api = require('../../services/api');
const { localDate } = require('../../utils/util');
Page({
  data: { categories: [], schedules: [], loading: true, error: '', bookingMode: '', waitlistEnabled: false, days: [], dayIndex: 0, timeOfDay: '', categoryId: '', difficulty: '' },
  onShow() {
    this.setData({ loading: true, error: '' });
    return api.bookingRules().then(rules => {
      const days = Array.from({ length: rules.advanceDays + 1 }, (_, i) => ({ label: i === 0 ? '今天' : i === 1 ? '明天' : localDate(i).slice(5), date: localDate(i) }));
      this.setData({ bookingMode: rules.bookingMode, waitlistEnabled: rules.waitlistEnabled === true, days, dayIndex: Math.min(this.data.dayIndex, days.length - 1) });
      return this.load();
    }).catch(e => this.setData({ loading: false, error: (e && e.message) || '预约规则加载失败，点击重试' }));
  },
  load() {
    const day = this.data.days[this.data.dayIndex];
    if (!day) return this.onShow();
    const generation = this.generation = (this.generation || 0) + 1;
    this.setData({ loading: true, error: '' });
    return api.schedules({ date: day.date, days: 1, timeOfDay: this.data.timeOfDay || undefined, categoryId: this.data.categoryId || undefined, difficulty: this.data.difficulty || undefined })
      .then(list => { if (generation === this.generation) this.setData({ schedules: list, loading: false }); })
      .catch(e => { if (generation === this.generation) this.setData({ loading: false, error: (e && e.message) || '课程加载失败，点击重试' }); });
  },
  retry() { return this.onShow(); },
  onDay(e) { this.setData({ dayIndex: Number(e.currentTarget.dataset.i) }); this.load(); },
  onTime(e) { this.setData({ timeOfDay: e.currentTarget.dataset.v }); this.load(); },
  onCategory(e) { this.setData({ categoryId: e.currentTarget.dataset.v }); this.load(); },
  onDifficulty(e) { this.setData({ difficulty: e.currentTarget.dataset.v }); this.load(); },
  goDetail(e) { wx.navigateTo({ url: '/pages/booking-confirm/booking-confirm?scheduleId=' + e.currentTarget.dataset.id }); },
  goCourseDetail(e) { wx.navigateTo({ url: '/pages/course-detail/course-detail?id=' + e.currentTarget.dataset.cid }); }
});
