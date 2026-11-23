const api = require('../api');
const { attendanceSourceLabel } = require('../../utils/util');

const statusText = { BOOKED: '已预约', SIGNED_IN: '已签到', COMPLETED: '已完成', NO_SHOW: '未到课' };

Page({
  data: { students: [], loading: true, error: '', signingId: null, issuing: false, ticket: null, ticketError: '' },
  onLoad(options) { this.scheduleId = options.scheduleId; },
  onShow() { return this.load(); },
  onUnload() {
    clearTimeout(this.ticketTimer);
    this.unloaded = true;
  },
  load() {
    this.setData({ loading: true, error: '' });
    return api.get('/api/coach-end/schedules/' + this.scheduleId + '/students').then(list => {
      this.setData({ students: list.map(student => ({
        ...student,
        statusLabel: statusText[student.bookingStatus] || student.bookingStatus,
        attendanceLabel: attendanceSourceLabel(student.attendanceSource)
      })), loading: false });
    }).catch(error => this.setData({ loading: false, error: error?.message || '读取学员名单失败' }));
  },
  issueCode() {
    if (this.data.issuing) return;
    this.setData({ issuing: true, ticketError: '' });
    return api.post('/api/coach-end/schedules/' + this.scheduleId + '/attendance-code').then(ticket => {
      if (this.unloaded) return;
      clearTimeout(this.ticketTimer);
      this.setData({ ticket });
      this.ticketTimer = setTimeout(() => this.setData({ ticket: null, ticketError: '签到码已过期，请重新生成' }), ticket.expiresInSeconds * 1000);
    }).catch(error => {
      if (!this.unloaded) this.setData({ ticketError: error?.message || '生成签到码失败' });
    }).finally(() => {
      if (!this.unloaded) this.setData({ issuing: false });
    });
  },
  checkin(e) {
    const id = e.currentTarget.dataset.id;
    if (this.data.signingId || !this.data.students.some(student => String(student.bookingId) === String(id) && student.canCheckin)) return;
    this.setData({ signingId: id });
    wx.showModal({
      title: '确认代签到',
      content: '请确认学员已实际到店。',
      success: result => {
        if (!result.confirm) { this.setData({ signingId: null }); return; }
        api.post('/api/coach-end/bookings/' + id + '/check-in').then(() => {
          wx.showToast({ title: '已签到', icon: 'success' });
          return this.load();
        }).catch(() => this.load()).finally(() => this.setData({ signingId: null }));
      },
      fail: () => this.setData({ signingId: null })
    });
  },
  goTraining(e) {
    wx.navigateTo({ url: '/coach-end/training/training?bookingId=' + e.currentTarget.dataset.id + '&userId=' + e.currentTarget.dataset.uid });
  },
  goDetail(e) {
    wx.navigateTo({ url: '/coach-end/student-detail/student-detail?userId=' + e.currentTarget.dataset.uid });
  }
});
