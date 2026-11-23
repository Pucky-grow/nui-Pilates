// pages/order-list/order-list.js —— 我的订单
const api = require('../../services/api');
const http = require('../../utils/request');
Page({
  data: { orders: [] },
  onShow() { http.login().then(() => this.load()); },
  load() { api.myOrders({ size: 50 }).then(d => this.setData({ orders: d.list })); },
  goDetail(e) {
    wx.navigateTo({ url: '/pages/order-detail/order-detail?id=' + e.currentTarget.dataset.id });
  }
});
