// pages/review/review.js —— 课程评价(创建/我的)
const api = require('../../services/api');
const http = require('../../utils/request');
Page({
  data: { mode: 'list', bookingId: null, score: 5, coachScore: 5, envScore: 5, content: '', mine: [], submitting: false },
  onLoad(options) {
    http.login().then(() => api.myReviews().then(d => this.setData({ mine: d.list })));
    if (options.bookingId) {
      this.setData({ mode: 'create', bookingId: Number(options.bookingId) });
    }
  },
  onScore(e) { this.setData({ score: Number(e.detail.value) }); },
  onCoach(e) { this.setData({ coachScore: Number(e.detail.value) }); },
  onEnv(e) { this.setData({ envScore: Number(e.detail.value) }); },
  onContent(e) { this.setData({ content: e.detail.value }); },
  submit() {
    if (this.data.submitting) return;
    this.setData({ submitting: true });
    api.createReview({
      bookingId: this.data.bookingId, courseScore: this.data.score,
      coachScore: this.data.coachScore, envScore: this.data.envScore, content: this.data.content
    }).then(() => {
      wx.showToast({ title: '评价成功', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 800);
    }).catch(() => this.setData({ submitting: false }));
  }
});
