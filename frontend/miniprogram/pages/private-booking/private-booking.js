const api = require('../../services/api');
const http = require('../../utils/request');
const GOALS = ['体态改善', '核心训练', '身体塑形', '运动基础', '柔韧性训练', '产后运动恢复', '日常身体管理'];
Page({
  data: { coaches: [], coachIndex: 0, goals: GOALS, goal: '', schedules: [], pkgIndex: 0, packages: [], bookingMode: '', loading: true, error: '', submitting: false },
  onLoad(options) { this.requestedCoach = options.coachId; },
  onShow() { return this.load(); },
  load() {
    this.setData({ loading: true, error: '' });
    return http.login().then(() => Promise.all([api.bookingRules(), api.coaches()])).then(([rules, coaches]) => {
      const selected = this.requestedCoach || this.data.coaches[this.data.coachIndex]?.id;
      const index = Math.max(0, coaches.findIndex(c => String(c.id) === String(selected)));
      this.requestedCoach = null;
      this.setData({ coaches, coachIndex: index, bookingMode: rules.bookingMode, packages: [] });
      return (rules.bookingMode === 'FREE' ? Promise.resolve([]) : api.myPackages('ACTIVE'))
        .then(packages => { this.setData({ packages: packages.filter(p => p.type === 'PRIVATE') }); return this.loadSchedules(); });
    }).catch(e => this.setData({ loading: false, error: (e && e.message) || '私教课程加载失败，请重试' }));
  },
  retry() { return this.load(); },
  onCoach(e) { this.setData({ coachIndex: Number(e.detail.value) }); return this.loadSchedules(); },
  onGoal(e) { this.setData({ goal: GOALS[e.detail.value] }); },
  onPkg(e) { this.setData({ pkgIndex: Number(e.detail.value) }); },
  loadSchedules(keepError = false) {
    const coach = this.data.coaches[this.data.coachIndex];
    const generation = this.generation = (this.generation || 0) + 1;
    this.setData({ loading: true, ...(keepError ? {} : { error: '' }) });
    if (!coach) { this.setData({ loading: false, schedules: [] }); return Promise.resolve(); }
    return api.coachSchedules(coach.id).then(list => { if (generation === this.generation) this.setData({ schedules: list.filter(s => s.courseType === 'PRIVATE'), loading: false }); })
      .catch(e => { if (generation === this.generation) this.setData({ loading: false, error: (e && e.message) || '私教时段加载失败，请重试' }); });
  },
  submit(e) {
    const scheduleId = Number(e.currentTarget.dataset.sid);
    const schedule = this.data.schedules.find(s => s.id === scheduleId);
    if (this.data.submitting || this.data.loading || !schedule || !schedule.bookable) return;
    const body = { scheduleId, remark: this.data.goal };
    if (this.data.bookingMode !== 'FREE') {
      const pkg = this.data.packages[this.data.pkgIndex];
      if (!pkg) { wx.showToast({ title: '暂无适用私教课包', icon: 'none' }); return; }
      body.userPackageId = pkg.id;
    }
    this.setData({ submitting: true, error: '' });
    return api.createBooking(body).then(d => wx.redirectTo({ url: '/pages/booking-detail/booking-detail?id=' + d.bookingId }))
      .catch(e => { this.setData({ error: (e && e.message) || '预约失败，请刷新重试' }); return this.loadSchedules(true); })
      .finally(() => this.setData({ submitting: false }));
  }
});
