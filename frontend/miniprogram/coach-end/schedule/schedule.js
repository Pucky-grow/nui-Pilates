// coach-end/schedule/schedule.js —— 教练课表(今日/明日/本周/下周/日期) + 创建/取消排课
const api = require('../api');
const { localDate, weekRange } = require('../../utils/util');

const RANGES = [
  { label: '今日', key: 'today' }, { label: '明日', key: 'tomorrow' },
  { label: '本周', key: 'week' }, { label: '下周', key: 'next_week' }
];

Page({
  data: { loading: false, error: '', schedules: [], ranges: RANGES, rangeIndex: 0, fromDate: '', toDate: '' },
  onLoad() {
    this.computeRange(0);
  },
  onShow() { if (this.data.rangeIndex >= 0) this.computeRange(this.data.rangeIndex); return this.load(); },
  computeRange(idx) {
    const now = new Date();
    let from, to;
    switch (RANGES[idx].key) {
      case 'today': from = localDate(0); to = localDate(0); break;
      case 'tomorrow': from = localDate(1); to = localDate(1); break;
      case 'week': ({ fromDate: from, toDate: to } = weekRange(false)); break;
      case 'next_week': ({ fromDate: from, toDate: to } = weekRange(true)); break;
    }
    this.setData({ fromDate: from, toDate: to, rangeIndex: idx });
  },
  onRange(e) { this.computeRange(e.currentTarget.dataset.i); this.load(); },
  onDate(e) {
    this.setData({ fromDate: e.detail.value, toDate: e.detail.value, rangeIndex: -1 });
    this.load();
  },
  load() {
    const { fromDate, toDate } = this.data;
    this.setData({ loading: true, error: '' });
    return api.get('/api/coach-end/schedules?fromDate=' + fromDate + '&toDate=' + toDate)
      .then(list => this.setData({ schedules: list, loading: false }))
      .catch(e => this.setData({ loading: false, error: (e && e.message) || '课表加载失败，请重试' }));
  },
  goCreate(e) {
    const mode = e.currentTarget.dataset.mode;
    wx.navigateTo({ url: '/coach-end/schedule-create/schedule-create?date=' + this.data.fromDate + (mode === 'booking' ? '&mode=booking' : '') });
  },
  goStudents(e) { wx.navigateTo({ url: '/coach-end/students/students?scheduleId=' + e.currentTarget.dataset.id }); },
  cancel(e) {
    const id = e.currentTarget.dataset.id;
    const self = this;
    wx.showModal({
      title: '取消排课',
      content: '确定取消这节排课吗？已预约的学员将被自动退课。',
      success(r) {
        if (!r.confirm) return;
        api.post('/api/coach-end/schedules/' + id + '/cancel').then(() => {
          wx.showToast({ title: '已取消', icon: 'success' });
          self.load();
        });
      }
    });
  }
});
