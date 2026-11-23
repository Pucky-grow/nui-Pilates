// pages/message/message.js —— 消息中心
const api = require('../../services/api');
const http = require('../../utils/request');
Page({
  data: { messages: [] },
  onShow() { http.login().then(() => api.notifications().then(d => this.setData({ messages: d.list }))); },
  read(e) {
    const message = this.data.messages.find(item => String(item.id) === String(e.currentTarget.dataset.id));
    if (!message) return;
    return api.readNotification(message.id).then(() => {
      const reference = String(message.bizId || '');
      const booking = message.bizType === 'SCHEDULE_CHANGE' ? reference.match(/^(\d+):\d+$/)
        : message.bizType === 'COURSE_CANCELLED' ? reference.match(/^(\d+)$/) : null;
      if (booking) wx.navigateTo({ url: '/pages/booking-detail/booking-detail?id=' + booking[1] });
      else this.onShow();
    }).catch(() => {});
  }
});
