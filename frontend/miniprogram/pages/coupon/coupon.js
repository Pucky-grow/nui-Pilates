// pages/coupon/coupon.js —— 优惠券
const api = require('../../services/api');
const http = require('../../utils/request');
Page({
  data: { myCoupons: [], allCoupons: [] },
  onShow() {
    http.login().then(() => {
      api.myCoupons().then(list => this.setData({ myCoupons: list }));
      api.coupons().then(list => this.setData({ allCoupons: list }));
    });
  },
  receive(e) {
    api.receiveCoupon(e.currentTarget.dataset.id).then(() => {
      wx.showToast({ title: '领取成功', icon: 'success' });
      this.onShow();
    });
  }
});
