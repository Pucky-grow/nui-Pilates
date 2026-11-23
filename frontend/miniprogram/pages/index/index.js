// pages/index/index.js —— 首页
const api = require('../../services/api');
const http = require('../../utils/request');

Page({
  data: {
    banners: [], todayCourses: [], hotCourses: [], coaches: [],
    newbiePackage: null, articles: [], studio: null, slogan: '让身体回到更舒展的状态'
  },
  onShow() {
    if (!http.ensureLogin()) return;
    this.load();
  },
  load() {
    const self = this;
    api.home().then((d) => {
      self.setData({
        banners: d.banners || [],
        todayCourses: d.todayCourses || [],
        hotCourses: d.hotCourses || [],
        coaches: d.coaches || [],
        newbiePackage: d.newbiePackage,
        articles: d.articles || []
      });
    });
    api.studio().then((s) => self.setData({ studio: s, slogan: s.slogan }));
  },
  goCourse() { wx.switchTab({ url: '/pages/course/course' }); },
  goPrivate() { wx.navigateTo({ url: '/pages/private-booking/private-booking' }); },
  goBookings() { wx.switchTab({ url: '/pages/booking/booking' }); },
  goCoaches() { wx.navigateTo({ url: '/pages/coach-list/coach-list' }); },
  goSchedule(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: '/pages/booking-confirm/booking-confirm?scheduleId=' + id });
  },
  goCourseDetail(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: '/pages/course-detail/course-detail?id=' + id });
  },
  goCoach(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: '/pages/coach-detail/coach-detail?id=' + id });
  },
  goArticle(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: '/pages/article-detail/article-detail?id=' + id });
  },
  goPackageDetail() {
    if (this.data.newbiePackage) {
      wx.navigateTo({ url: '/pages/package-detail/package-detail?id=' + this.data.newbiePackage.id });
    }
  },
  goAbout() { wx.navigateTo({ url: '/pages/about/about' }); },
  goMessage() { wx.navigateTo({ url: '/pages/message/message' }); }
});
