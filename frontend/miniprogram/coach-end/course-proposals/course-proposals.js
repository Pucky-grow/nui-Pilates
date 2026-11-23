// coach-end/course-proposals/course-proposals.js —— 课程申请列表
const api = require('../api');

const STATUS = { DRAFT: '草稿', PENDING_REVIEW: '审核中', APPROVED: '已通过', REJECTED: '已驳回', CANCELLED: '已取消' };

Page({
  data: { proposals: [], statusText: STATUS },
  onShow() { this.load(); },
  load() { api.get('/api/coach-end/course-proposals').then(list => this.setData({ proposals: list })); },
  create() { wx.navigateTo({ url: '/coach-end/course-proposal-edit/course-proposal-edit' }); },
  edit(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: '/coach-end/course-proposal-edit/course-proposal-edit?id=' + id });
  },
  submit(e) {
    const id = e.currentTarget.dataset.id;
    api.post('/api/coach-end/course-proposals/' + id + '/submit').then(() => {
      wx.showToast({ title: '已提交审核', icon: 'success' });
      this.load();
    });
  }
});
