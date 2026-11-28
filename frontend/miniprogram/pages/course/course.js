const api = require('../../services/api');
const { localDate, weekday } = require('../../utils/util');
const { scheduleDisplay } = require('../../utils/member-booking');

Page({
  data: {
    schedules: [], loading: true, error: '', waitlistEnabled: false, days: [], dayIndex: 0,
    timeOfDay: '', difficulty: '', showFilters: false,
    times: [{ key: '', label: '不限时段' }, { key: 'MORNING', label: '上午' }, { key: 'AFTERNOON', label: '下午' }, { key: 'EVENING', label: '晚上' }],
    difficulties: [{ key: '', label: '不限难度' }, { key: 'BEGINNER', label: '入门' }, { key: 'JUNIOR', label: '初级' }, { key: 'INTERMEDIATE', label: '中级' }, { key: 'ADVANCED', label: '进阶' }]
  },
  onShow() {
    const generation = this.generation = (this.generation || 0) + 1;
    const selected = this.data.days[this.data.dayIndex]?.date;
    this.setData({ loading: true, error: '', schedules: [], days: [] });
    return api.bookingRules().then(rules => {
      if (generation !== this.generation) return;
      const days = Array.from({ length: rules.advanceDays + 1 }, (_, i) => {
        const date = localDate(i);
        return { date, label: i === 0 ? '今天' : i === 1 ? '明天' : weekday(date), short: date.slice(5).replace('-', '/') };
      });
      this.setData({ waitlistEnabled: rules.waitlistEnabled === true, days, dayIndex: Math.max(0, days.findIndex(day => day.date === selected)) });
      return this.load();
    }).catch(error => {
      if (generation === this.generation) this.setData({ loading: false, error: error?.message || '预约规则加载失败，请重试' });
    });
  },
  load() {
    const day = this.data.days[this.data.dayIndex];
    if (!day) return this.onShow();
    const generation = this.generation = (this.generation || 0) + 1;
    this.setData({ loading: true, error: '', schedules: [] });
    return api.schedules({ date: day.date, days: 1, timeOfDay: this.data.timeOfDay || undefined, difficulty: this.data.difficulty || undefined })
      .then(items => { if (generation === this.generation) this.setData({ schedules: items.map(scheduleDisplay), loading: false }); })
      .catch(error => { if (generation === this.generation) this.setData({ loading: false, error: error?.message || '场次加载失败，请重试' }); });
  },
  retry() { return this.data.days.length ? this.load() : this.onShow(); },
  toggleFilters() { this.setData({ showFilters: !this.data.showFilters }); },
  onDay(e) { this.setData({ dayIndex: Number(e.currentTarget.dataset.i) }); return this.load(); },
  onTime(e) { this.setData({ timeOfDay: e.currentTarget.dataset.v }); return this.load(); },
  onDifficulty(e) { this.setData({ difficulty: e.currentTarget.dataset.v }); return this.load(); },
  resetFilters() { this.setData({ timeOfDay: '', difficulty: '' }); return this.load(); },
  goDetail(e) {
    const id = e.currentTarget.dataset.id;
    const schedule = this.data.schedules.find(row => String(row.id) === String(id));
    if (!schedule || this.data.loading || (!schedule.bookable && !(this.data.waitlistEnabled && schedule.bookingStatus === 'FULL'))) return;
    wx.navigateTo({ url: '/pages/booking-confirm/booking-confirm?scheduleId=' + id });
  },
  goCourseDetail(e) {
    wx.navigateTo({ url: '/pages/course-detail/course-detail?id=' + e.currentTarget.dataset.cid });
  }
});
