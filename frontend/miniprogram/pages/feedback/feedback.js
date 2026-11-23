// pages/feedback/feedback.js —— 意见反馈
const api = require('../../services/api');
const http = require('../../utils/request');

const TYPES = ['课程相关', '教练相关', '支付相关', '使用体验', '建议', '其他'];
const STATUS = { PENDING: '待处理', PROCESSING: '处理中', DONE: '已完成' };

Page({
  data: { types: TYPES, typeIndex: 0, content: '', contact: '', mine: [], statusText: STATUS },
  onShow() { http.login().then(() => api.myFeedback().then(list => this.setData({ mine: list }))); },
  onType(e) { this.setData({ typeIndex: Number(e.detail.value) }); },
  onContent(e) { this.setData({ content: e.detail.value }); },
  onContact(e) { this.setData({ contact: e.detail.value }); },
  submit() {
    if (!this.data.content) { wx.showToast({ title: '请填写反馈内容', icon: 'none' }); return; }
    api.submitFeedback({ type: this.data.types[this.data.typeIndex], content: this.data.content, contact: this.data.contact })
      .then(() => {
        wx.showToast({ title: '已提交', icon: 'success' });
        this.setData({ content: '', contact: '' });
        this.onShow();
      });
  }
});
