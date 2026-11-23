// pages/package-detail/package-detail.js —— 课包详情 + 下单
const api = require('../../services/api');
const http = require('../../utils/request');

Page({
  data: { packageSales: false, pkg: null, coupons: [], usePoints: false, points: 0, submitting: false },
  onLoad(options) {
    http.get('/api/capabilities').then(c => this.setData({ packageSales: c.packageSales })).catch(() => {});
    this.packageId = Number(options.id);
    http.login().then(() => {
      api.packageDetail(this.packageId).then(p => this.setData({ pkg: p }));
      api.myCoupons('UNUSED').then(list => this.setData({ coupons: list }));
      api.myPoints().then(d => this.setData({ points: d.balance }));
    });
  },
  togglePoints(e) { this.setData({ usePoints: e.detail.value }); },
  submit() {
    if (!this.data.packageSales) { wx.showToast({ title: '当前免费预约，未开放购课', icon: 'none' }); return; }
    if (this.data.submitting) return;
    this.setData({ submitting: true });
    api.createOrder({
      packageId: this.packageId,
      userCouponId: this.data.selectedCouponId,
      pointsUsed: this.data.usePoints ? this.data.points : 0
    }).then(d => {
      wx.redirectTo({ url: '/pages/order-detail/order-detail?id=' + d.orderId });
    }).catch(() => this.setData({ submitting: false }));
  },
  selectCoupon(e) {
    this.setData({ selectedCouponId: e.currentTarget.dataset.id });
  }
});
