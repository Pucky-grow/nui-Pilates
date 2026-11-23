// coach-end/leave/leave.js —— 我的请假
const api = require('../api');
const { localDate } = require('../../utils/util');

const STATUS = { PENDING: '待审批', APPROVED: '已通过', REJECTED: '已拒绝', CANCELLED: '已取消' };

Page({
  data: { leaves: [], statusText: STATUS, date: '', startTime: '', endTime: '', reason: '' },
  onShow() { this.load(); },
  load() { api.get('/api/coach-end/leaves').then(list => this.setData({ leaves: list })); },
  onDate(e) { this.setData({ date: e.detail.value }); },
  onStart(e) { this.setData({ startTime: e.detail.value }); },
  onEnd(e) { this.setData({ endTime: e.detail.value }); },
  onReason(e) { this.setData({ reason: e.detail.value }); },
  submit() {
    const { date, startTime, endTime, reason } = this.data;
    if (!date || !startTime || !endTime) { wx.showToast({ title: '请选择日期和时间', icon: 'none' }); return; }
    api.post('/api/coach-end/leaves', { leaveDate: date, startTime, endTime, reason }).then(() => {
      wx.showToast({ title: '请假已提交', icon: 'success' });
      this.setData({ date: '', startTime: '', endTime: '', reason: '' });
      this.load();
    });
  },
  cancel(e) {
    const id = e.currentTarget.dataset.id;
    const self = this;
    wx.showModal({
      title: '取消请假', content: '确定取消该请假申请？',
      success(r) { if (r.confirm) api.post('/api/coach-end/leaves/' + id + '/cancel').then(() => self.load()); }
    });
  }
});
