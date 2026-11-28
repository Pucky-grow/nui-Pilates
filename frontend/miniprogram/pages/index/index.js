const api = require('../../services/api');
const http = require('../../utils/request');
const { bookingGroups, bookingDisplay, scheduleDisplay, typeLabels } = require('../../utils/member-booking');

Page({
  data: {
    banners: [], hotCourses: [], coaches: [], articles: [], studio: null, slogan: '让身体回到更舒展的状态',
    nextBooking: null, available: [], planLoading: true, planError: '', contentError: ''
  },
  onShow() {
    if (http.ensureLogin()) return this.load();
  },
  onPullDownRefresh() { return this.load().finally(() => wx.stopPullDownRefresh()); },
  load() { return Promise.all([this.loadPlans(), this.loadContent()]); },
  async loadPlans() {
    const request = this.planRequest = (this.planRequest || 0) + 1;
    this.setData({ planLoading: true, planError: '', nextBooking: null, available: [] });
    try {
      const bookings = await api.myBookings();
      if (request !== this.planRequest) return;
      const next = bookingGroups(bookings).upcoming[0];
      if (next) {
        this.setData({ nextBooking: bookingDisplay(next), planLoading: false });
        return;
      }
      const schedules = await api.schedules({ days: 7, onlyAvailable: true });
      if (request === this.planRequest) {
        this.setData({ available: schedules.filter(item => item.bookable).slice(0, 3).map(scheduleDisplay), planLoading: false });
      }
    } catch (error) {
      if (request === this.planRequest) this.setData({ planLoading: false, planError: error?.message || '上课安排暂时无法加载' });
    }
  },
  loadContent() {
    const request = this.contentRequest = (this.contentRequest || 0) + 1;
    this.setData({ contentError: '' });
    return Promise.all([
      api.home().then(data => {
        if (request !== this.contentRequest) return;
        const hotCourses = new Map();
        for (const item of data.hotCourses || []) {
          if (item.enabled !== false && item.enabled !== 0 && item.courseId && item.courseName && !hotCourses.has(item.courseId)) {
            hotCourses.set(item.courseId, { ...item, typeText: typeLabels[item.courseType] || '' });
          }
        }
        this.setData({
          banners: (data.banners || []).filter(item => item.imageUrl || item.title || item.subtitle),
          hotCourses: [...hotCourses.values()],
          coaches: (data.coaches || []).filter(item => item.enabled !== false && item.enabled !== 0 && item.coachId && item.coachName),
          articles: data.articles || []
        });
      }).catch(() => {
        if (request === this.contentRequest) this.setData({ contentError: '门店内容暂时无法加载' });
      }),
      api.studio().then(studio => {
        if (request === this.contentRequest && studio) this.setData({ studio, slogan: studio.slogan || this.data.slogan });
      }).catch(() => {})
    ]);
  },
  goCourse() { wx.switchTab({ url: '/pages/course/course' }); },
  goPrivate() { wx.navigateTo({ url: '/pages/private-booking/private-booking' }); },
  goBooking() {
    const next = this.data.nextBooking;
    if (next) wx.navigateTo({ url: '/pages/booking-detail/booking-detail?id=' + next.id });
  },
  checkin() {
    const next = this.data.nextBooking;
    if (next?.canCheckin) wx.navigateTo({ url: '/pages/checkin/checkin?bookingId=' + next.id });
  },
  goSchedule(e) {
    const id = e.currentTarget.dataset.id;
    if (this.data.available.some(row => String(row.id) === String(id))) {
      wx.navigateTo({ url: '/pages/booking-confirm/booking-confirm?scheduleId=' + id });
    }
  },
  goCourseDetail(e) { wx.navigateTo({ url: '/pages/course-detail/course-detail?id=' + e.currentTarget.dataset.id }); },
  goCoaches() { wx.navigateTo({ url: '/pages/coach-list/coach-list' }); },
  goCoach(e) {
    const id = e.currentTarget.dataset.id;
    if (id) wx.navigateTo({ url: '/pages/coach-detail/coach-detail?id=' + id });
  },
  goArticle(e) { wx.navigateTo({ url: '/pages/article-detail/article-detail?id=' + e.currentTarget.dataset.id }); },
  goAbout() { wx.navigateTo({ url: '/pages/about/about' }); }
});
