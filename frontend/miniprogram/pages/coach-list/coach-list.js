// pages/coach-list/coach-list.js —— 教练列表
const api = require('../../services/api');
Page({
  data: { coaches: [] },
  onShow() { api.coaches().then(list => this.setData({ coaches: list })); },
  goDetail(e) {
    wx.navigateTo({ url: '/pages/coach-detail/coach-detail?id=' + e.currentTarget.dataset.id });
  }
});
