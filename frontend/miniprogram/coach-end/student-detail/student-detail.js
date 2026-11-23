// coach-end/student-detail/student-detail.js —— 学员档案
const api = require('../api');

Page({
  data: { student: null },
  onLoad(options) {
    this.userId = options.userId;
    this.load();
  },
  load() {
    api.get('/api/coach-end/students/' + this.userId).then(s => this.setData({ student: s }));
  }
});
