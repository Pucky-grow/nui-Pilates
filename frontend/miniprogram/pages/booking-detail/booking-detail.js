// pages/booking-detail/booking-detail.js —— 预约详情
const api = require('../../services/api');
const http = require('../../utils/request');
const { cancellationNotice } = require('../../utils/studio-ledger');
const STATUS = { BOOKED: '已预约', SIGNED_IN: '已签到', COMPLETED: '已完成', CANCELLED: '已取消', NO_SHOW: '已爽约' };

Page({
  data: { b: null, statusText: STATUS, cancelling: false, loading: true, error: '' },
  onLoad(options) {
    this.id = options.id;
  },
  onShow() {
    // 从签到/评价/取消返回时刷新, 保证状态最新
    if (!this.id || !http.ensureLogin()) return;
    return this.load();
  },
  load() {
    const requestId = this.requestId = (this.requestId || 0) + 1;
    this.setData({ loading: true, error: '' });
    return api.bookingDetail(this.id).then(b => {
      if (requestId === this.requestId) this.setData({ b, loading: false });
    }).catch(error => {
      if (requestId === this.requestId) this.setData({ loading: false, error: error?.message || '预约详情加载失败' });
    });
  },
  cancel() {
    if (this.data.cancelling || !this.data.b?.canCancel) return;
    this.setData({ cancelling: true });
    wx.showModal({
      title: '取消预约',
      content: '确定取消「' + (this.data.b.courseName || '') + '」的预约吗？' + cancellationNotice(this.data.b),
      success: r => {
        if (!r.confirm) { this.setData({ cancelling: false }); return; }
        api.cancelBooking(this.id).then(() => {
          wx.showToast({ title: '已取消', icon: 'success' });
          return this.load();
        }).catch(() => this.load()).finally(() => this.setData({ cancelling: false }));
      },
      fail: () => this.setData({ cancelling: false })
    });
  },
  checkin() { wx.navigateTo({ url: '/pages/checkin/checkin?bookingId=' + this.id }); },
  review() { wx.navigateTo({ url: '/pages/review/review?bookingId=' + this.id }); }
});
