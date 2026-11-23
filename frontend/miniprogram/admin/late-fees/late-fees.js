const u = require('../common');
const display = require('../../utils/studio-ledger');

Page({
  data: { rows: [], canAdmin: false, busy: false, more: false, error: '' },
  onLoad(q) {
    this.api = q.coach === '1' ? require('../../coach-end/api') : u.api;
    this.send = display.commands(this.api);
    this.setData({ canAdmin: q.coach !== '1' && (wx.getStorageSync('adminRoles') || []).includes('ADMIN') });
    this.load();
  },
  onPullDownRefresh() { this.load(); },
  load() { return u.run(this, () => this.read(false)); },
  next() { return u.run(this, () => this.read(true)); },
  async read(append) {
    const old = this.data.rows;
    const rows = await this.api.get('/api/studio/late-fees', append ? { before: old[old.length - 1].booking_id } : {});
    this.setData({ rows: (append ? old : []).concat(rows.map(display.present)), more: rows.length === 30 });
  },
  decide(e) {
    const { id, action } = e.currentTarget.dataset;
    const row = this.data.rows.find(r => String(r.booking_id) === String(id));
    if (!row) return;
    return u.run(this, async () => {
      const reason = await u.reason(action === 'CHARGE' ? '按规则收取 ¥' + row.amountText : '免收取消费', '请确认预约与实际取消情况。');
      if (!reason) return;
      await this.send('/api/studio/late-fees/' + id + '/decision', { decision: action, expectedFen: row.late_fen, reason });
      await this.read(false);
      u.success();
    });
  },
  finish(e) {
    const { id, action } = e.currentTarget.dataset;
    return u.run(this, async () => {
      const reason = await u.reason({ settle: '续结算', waive: '免收待结算费用', reverse: '冲正取消费' }[action], '请填写本次处理的原因。');
      if (!reason) return;
      let destination;
      if (action === 'reverse') {
        const choices = ['原账户', '通用余额', '小班账户', '私教账户'];
        const selected = await new Promise(resolve => wx.showActionSheet({ itemList: choices, success: r => resolve(r.tapIndex), fail: () => resolve(-1) }));
        if (selected < 0) return;
        destination = [null, 'GENERAL', 'GROUP', 'PRIVATE'][selected];
      }
      await this.send('/api/admin/studio-ledger/late-fees/' + id + '/' + action, { reason, destination });
      await this.read(false);
      u.success();
    });
  }
});
