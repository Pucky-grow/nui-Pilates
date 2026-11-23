// pages/booking/booking.js —— 我的预约
const api = require('../../services/api');
const http = require('../../utils/request');
const { cancellationNotice } = require('../../utils/studio-ledger');

const STATUS_MAP = {
  BOOKED: '已预约', SIGNED_IN: '已签到', COMPLETED: '已完成',
  CANCELLED: '已取消', NO_SHOW: '已爽约', REFUNDED: '已退款'
};

Page({
  data: { bookings: [], status: '', statusText: STATUS_MAP, loading: true, error: '', cancellingId: null },
  onShow() {
    if (!http.ensureLogin()) return;
    return this.load();
  },
  load() {
    const requestId = this.requestId = (this.requestId || 0) + 1;
    this.setData({ loading: true, error: '' });
    return api.myBookings(this.data.status || undefined).then(bookings => {
      if (requestId === this.requestId) this.setData({ bookings, loading: false });
    }).catch(error => {
      if (requestId === this.requestId) {
        this.setData({ loading: false, error: error?.message || '预约加载失败，请重试' });
      }
    });
  },
  onPullDownRefresh() { return this.load().finally(() => wx.stopPullDownRefresh()); },
  onFilter(e) { this.setData({ status: e.currentTarget.dataset.v }); return this.load(); },
  goDetail(e) {
    wx.navigateTo({ url: '/pages/booking-detail/booking-detail?id=' + e.currentTarget.dataset.id });
  },
  onCancel(e) {
    const id = e.currentTarget.dataset.id;
    if (this.data.cancellingId) return;
    const booking = this.data.bookings.find(row => String(row.id) === String(id));
    this.setData({ cancellingId: id });
    wx.showModal({
      title: '取消预约', content: '确定取消这节课程的预约吗？' + cancellationNotice(booking),
      success: r => {
        if (!r.confirm) { this.setData({ cancellingId: null }); return; }
        api.cancelBooking(id).then(() => {
          wx.showToast({ title: '已取消', icon: 'success' });
          return this.load();
        }).catch(() => this.load()).finally(() => this.setData({ cancellingId: null }));
      },
      fail: () => this.setData({ cancellingId: null })
    });
  },
  onCheckin(e) {
    const id = e.currentTarget.dataset.id;
    wx.navigateTo({ url: '/pages/checkin/checkin?bookingId=' + id });
  },
  goAgain(e) {
    const cid = e.currentTarget.dataset.cid;
    wx.navigateTo({ url: '/pages/course-detail/course-detail?id=' + cid });
  },
  goCourse() { wx.switchTab({ url: '/pages/course/course' }); }
});
