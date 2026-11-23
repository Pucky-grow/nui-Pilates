// pages/order-detail/order-detail.js —— 订单详情 + 支付
const api = require('../../services/api');
const http = require('../../utils/request');

Page({
  data: { order: null, paymentEnabled: false },
  onLoad(options) {
    http.get('/api/capabilities').then(c => this.setData({ paymentEnabled: c.paymentInitiation })).catch(() => {});
    this.orderId = Number(options.id);
    http.login().then(() => this.load());
  },
  load() { return api.orderDetail(this.orderId).then(o => this.setData({ order: o })); },
  pay() {
    const self = this;
    return api.createPay(this.orderId).then(params => {
      if (params.settled === true) {
        wx.showToast({ title: '订单已结算', icon: 'success' });
        return self.load();
      }
      if (params.mock) {
        // 开发环境: 直接模拟支付完成闭环
        api.mockPay(this.orderId).then(() => {
          wx.showToast({ title: '支付成功', icon: 'success' });
          self.load();
        });
      } else {
        wx.requestPayment({
          timeStamp: params.timeStamp, nonceStr: params.nonceStr,
          package: params.packageValue, signType: params.signType, paySign: params.paySign,
          success() { wx.showToast({ title: '支付成功', icon: 'success' }); self.load(); },
          fail() { wx.showToast({ title: '支付取消', icon: 'none' }); }
        });
      }
    });
  },
  cancel() {
    const self = this;
    wx.showModal({
      title: '取消订单', content: '确定取消该订单？',
      success(r) { if (r.confirm) api.cancelOrder(self.orderId).then(() => self.load()); }
    });
  }
});
