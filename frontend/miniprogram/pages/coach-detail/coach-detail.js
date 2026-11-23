// pages/coach-detail/coach-detail.js —— 教练详情
const api = require('../../services/api');
Page({
  data: { coach: null, reviews: [] },
  onLoad(options) {
    this.id = options.id;
    api.coachDetail(this.id).then(c => this.setData({ coach: c }));
    api.coachReviews(this.id).then(d => this.setData({ reviews: d.list }));
  },
  book() {
    wx.navigateTo({ url: '/pages/private-booking/private-booking?coachId=' + this.id });
  }
});
