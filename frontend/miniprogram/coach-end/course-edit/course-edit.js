// coach-end/course-edit/course-edit.js —— 编辑课程展示信息(封面/介绍/难度/适合人群/训练重点/注意事项)
const api = require('../api');
const app = getApp();

const DIFFICULTIES = [
  { v: 'BEGINNER', label: '入门' },
  { v: 'JUNIOR', label: '初级' },
  { v: 'INTERMEDIATE', label: '中级' },
  { v: 'ADVANCED', label: '进阶' }
];

Page({
  data: {
    id: null, course: null, cover: '', description: '', difficulty: 'JUNIOR',
    targetUsers: '', trainingFocus: '', notices: '',
    difficulties: DIFFICULTIES, diffIndex: 1, submitting: false
  },
  onLoad(options) {
    this.id = options.id;
    api.get('/api/courses/' + this.id).then(c => {
      let diffIndex = DIFFICULTIES.findIndex(d => d.v === c.difficulty);
      if (diffIndex < 0) diffIndex = 1;
      this.setData({
        course: c, cover: c.cover || '', description: c.description || '',
        difficulty: c.difficulty || 'JUNIOR', targetUsers: c.targetUsers || '',
        trainingFocus: c.trainingFocus || '', notices: c.notices || '', diffIndex
      });
    });
  },
  onDiff(e) {
    const i = Number(e.detail.value);
    this.setData({ diffIndex: i, difficulty: DIFFICULTIES[i].v });
  },
  onInput(e) {
    const k = e.currentTarget.dataset.k;
    this.setData({ [k]: e.detail.value });
  },
  onCover() {
    const self = this;
    wx.chooseMedia({
      count: 1, mediaType: ['image'],
      success(res) {
        const temp = res.tempFiles[0].tempFilePath;
        wx.showLoading({ title: '上传中', mask: true });
        api.upload('/api/upload', temp).then(url => {
          wx.hideLoading();
          self.setData({ cover: /^https?:\/\//.test(url) ? url : app.globalData.baseUrl + url });
        }).catch(() => wx.hideLoading());
      }
    });
  },
  submit() {
    if (this.data.submitting) return;
    this.setData({ submitting: true });
    const body = {
      cover: this.data.cover, description: this.data.description,
      difficulty: this.data.difficulty, targetUsers: this.data.targetUsers,
      trainingFocus: this.data.trainingFocus, notices: this.data.notices
    };
    api.put('/api/coach-end/courses/' + this.id + '/display', body).then(() => {
      wx.showToast({ title: '已保存', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 800);
    }).catch(() => this.setData({ submitting: false }));
  }
});
