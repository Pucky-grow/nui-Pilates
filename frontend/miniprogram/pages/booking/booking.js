const api = require('../../services/api');
const http = require('../../utils/request');
const { bookingGroups, bookingDisplay } = require('../../utils/member-booking');

const statusText = {
  BOOKED: '已预约', SIGNED_IN: '已签到', COMPLETED: '已完成',
  CANCELLED: '已取消', NO_SHOW: '未到课', REFUNDED: '已退款'
};

Page({
  data: { tab: 'upcoming', nextBooking: null, upcoming: [], history: [], statusText, loading: true, error: '' },
  onShow() {
    if (http.ensureLogin()) return this.load();
  },
  load() {
    const requestId = this.requestId = (this.requestId || 0) + 1;
    this.setData({ loading: true, error: '' });
    return api.myBookings().then(rows => {
      if (requestId !== this.requestId) return;
      const { upcoming, history } = bookingGroups(rows);
      const [nextBooking, ...later] = upcoming;
      this.setData({
        nextBooking: nextBooking ? bookingDisplay(nextBooking) : null,
        upcoming: later.map(bookingDisplay),
        history: history.map(bookingDisplay),
        loading: false
      });
    }).catch(error => {
      if (requestId === this.requestId) this.setData({ loading: false, error: error?.message || '预约暂时无法加载' });
    });
  },
  onPullDownRefresh() { return this.load().finally(() => wx.stopPullDownRefresh()); },
  selectTab(e) { this.setData({ tab: e.currentTarget.dataset.tab }); },
  goDetail(e) { wx.navigateTo({ url: '/pages/booking-detail/booking-detail?id=' + e.currentTarget.dataset.id }); },
  checkin() {
    const next = this.data.nextBooking;
    if (next?.canCheckin) wx.navigateTo({ url: '/pages/checkin/checkin?bookingId=' + next.id });
  },
  goAgain(e) { wx.navigateTo({ url: '/pages/course-detail/course-detail?id=' + e.currentTarget.dataset.cid }); },
  goCourse() { wx.switchTab({ url: '/pages/course/course' }); }
});
