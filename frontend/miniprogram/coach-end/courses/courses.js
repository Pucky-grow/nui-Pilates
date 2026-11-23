// coach-end/courses/courses.js —— 课程中心(我的课程 + 推荐课程 + 申请)
const api = require('../api');

Page({
  data: { myCourses: [], allCourses: [], showAll: false },
  onShow() { this.load(); },
  load() {
    api.get('/api/coach-end/courses/my').then(list => this.setData({ myCourses: list }));
    api.get('/api/coach-end/courses').then(list => this.setData({ allCourses: list }));
  },
  apply(e) {
    const id = e.currentTarget.dataset.id;
    api.post('/api/coach-end/courses/' + id + '/apply').then(() => {
      wx.showToast({ title: '已提交申请', icon: 'success' });
      this.load();
    });
  },
  goProposals() { wx.navigateTo({ url: '/coach-end/course-proposals/course-proposals' }); },
  editDisplay(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: '/coach-end/course-edit/course-edit?id=' + id });
  }
});
