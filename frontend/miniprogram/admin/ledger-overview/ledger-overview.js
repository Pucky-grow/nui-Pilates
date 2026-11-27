const u = require('../common');

const yuan = value => (value == null ? '—' : Number(value).toFixed(2));

Page({
  data: { busy: false, error: '', overview: null, unavailable: false },
  onShow() {
    if (u.guard('statistics:view')) this.load();
  },
  onPullDownRefresh() { this.load(); },
  load() {
    return u.run(this, async () => {
      const token = wx.getStorageSync('adminToken');
      const overview = await u.api.get('/api/admin/statistics/studio-ledger', undefined, { quiet: true });
      if (wx.getStorageSync('adminToken') !== token) return;
      if (!overview) {
        this.setData({ overview: null, unavailable: true });
        return;
      }
      const present = overview.revenue ? {
        today: yuan(overview.revenue.today),
        week: yuan(overview.revenue.week),
        month: yuan(overview.revenue.month),
        total: yuan(overview.revenue.total)
      } : null;
      this.setData({ overview: { ...overview, revenue: present }, unavailable: false });
    });
  }
});
