const http = require('../../utils/request');
const display = require('../../utils/studio-ledger');
const paths = {
  history: '/api/my/lesson-history',
  entries: '/api/my/wallet/entries',
  fees: '/api/my/wallet/cancellation-fees'
};

Page({
  data: { tab: 'history', list: [], accounts: [], busy: false, error: '', more: false },
  onShow() {
    this.setData({ list: [], accounts: [], more: false });
    if (http.ensureLogin()) this.load(false);
  },
  onPullDownRefresh() { this.load(false); },
  tab(e) {
    if (this.data.busy) return;
    this.setData({ tab: e.currentTarget.dataset.tab, list: [], more: false });
    this.load(false);
  },
  next() { this.load(true); },
  retry() { this.load(false); },
  async load(append) {
    if (this.data.busy) return;
    const context = http.captureAuthContext();
    const tab = this.data.tab;
    const previous = this.data.list;
    const last = previous[previous.length - 1];
    this.setData({ busy: true, error: '' });
    try {
      const [wallet, rows] = await Promise.all([
        http.get('/api/my/wallet', null, { skipAuth: true }),
        http.get(paths[tab], append && last ? { before: last.id || last.booking_id } : {}, { skipAuth: true })
      ]);
      if (!http.isAuthContextCurrent(context)) return;
      this.setData({
        accounts: wallet.accounts.map(r => ({ ...r, name: display.label(r.bucket), amount: display.money(r.balance_fen) })),
        list: (append ? previous : []).concat(rows.map(display.present)), more: rows.length === 30
      });
    } catch (error) {
      if (http.isAuthContextCurrent(context)) this.setData({ error: error?.message || '读取失败，请重试' });
    } finally {
      this.setData({ busy: false });
      wx.stopPullDownRefresh();
    }
  }
});
