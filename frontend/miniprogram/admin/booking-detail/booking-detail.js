const u = require('../common');
const { attendanceSourceLabel } = require('../../utils/util');

const actions = {
  cancel: { title: '门店取消该预约', detail: '只取消这位会员的预约并释放名额，课程照常。' },
  'member-cancel': { title: '代会员取消预约', detail: '请核实会员本人已提出取消。符合原预约约定的临时取消费会进入待处理。' },
  'check-in': { title: '登记会员签到' }
};

Page({
  data: { id: null, row: null, busy: false, error: '', canManage: false, canSign: false, studioLedger: false },
  onLoad(q) {
    this.setData({ id: q.id, canManage: u.isAdmin(), canSign: u.can('staff:attendance:create') });
    if (!u.guard('member:view')) return;
    if (this.data.canManage) {
      u.api.get('/api/capabilities', undefined, { quiet: true })
        .then(c => this.setData({ studioLedger: c.studioLedger === true })).catch(() => {});
    }
    return this.load();
  },
  onShow() {
    if (this.loaded) this.load();
    this.loaded = true;
  },
  onPullDownRefresh() { return this.load(); },
  load() { return u.run(this, () => this.read()); },
  async read() {
    const row = await u.api.get('/api/admin/bookings/' + this.data.id);
    this.setData({ row, statusLabel: u.status(row.booking.status), attendanceSource: attendanceSourceLabel(row.booking.attendanceSource) });
  },
  cancelMenu() {
    if (!this.data.canManage || !this.data.row?.booking.canCancel || this.data.busy) return;
    if (!this.data.studioLedger) return this.commit('cancel');
    wx.showActionSheet({
      itemList: ['门店取消该预约', '应会员请求取消'],
      success: ({ tapIndex }) => {
        if (tapIndex === 0) return this.commit('cancel');
        if (tapIndex === 1) return this.commit('member-cancel');
      }
    });
  },
  checkIn() {
    if (this.data.canSign && this.data.row?.booking.canCheckin) return this.commit('check-in');
  },
  commit(action) {
    return u.run(this, async () => {
      const { title, detail } = actions[action];
      if (detail && !await u.confirm(title, detail)) return;
      const reason = await u.reason(title, '');
      if (!reason) return;
      await u.api.post('/api/admin/bookings/' + this.data.id + '/' + action, { reason });
      this.setData({ row: null });
      try {
        await this.read();
      } catch (_) {
        throw new Error('操作已提交，预约状态读取失败，请刷新查看。');
      }
      u.success();
    });
  },
  move() {
    if (!this.data.canManage || !this.data.row?.booking.canCancel || this.data.busy) return;
    wx.navigateTo({ url: '/admin/booking-edit/booking-edit?bookingId=' + this.data.id + '&userId=' + this.data.row.userId });
  }
});
