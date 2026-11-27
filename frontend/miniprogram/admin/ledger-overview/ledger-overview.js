const u = require('../common');

const periods = [
  { key: 'today', label: '今日' },
  { key: 'week', label: '本周' },
  { key: 'month', label: '本月' },
  { key: 'total', label: '累计' }
];

function yuan(value) {
  const amount = Number(value);
  if (value == null || !Number.isFinite(amount)) return '—';
  const [integer, decimal] = Math.abs(amount).toFixed(2).split('.');
  return (amount < 0 ? '−' : '') + '¥' + integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',') + '.' + decimal;
}

function moneyByPeriod(amounts) {
  return Object.fromEntries(periods.map(({ key }) => [key, yuan(amounts[key])]));
}

Page({
  data: { periods, period: 'total', busy: false, error: '', overview: null, unavailable: false },
  onShow() { return this.load(); },
  onPullDownRefresh() { return this.load(); },
  selectPeriod(e) {
    const period = e.currentTarget.dataset.period;
    if (periods.some(item => item.key === period)) this.setData({ period });
  },
  load() {
    const token = wx.getStorageSync('adminToken');
    if (this.overviewToken !== token) {
      this.overviewToken = token;
      this.setData({ overview: null, unavailable: false, error: '' });
    }
    if (!u.guard('statistics:view')) {
      wx.stopPullDownRefresh();
      return;
    }
    return u.run(this, async () => {
      const overview = await u.api.get('/api/admin/statistics/studio-ledger', undefined, { quiet: true });
      if (wx.getStorageSync('adminToken') !== token) return;
      if (!overview) {
        this.setData({ overview: null, unavailable: true });
        return;
      }
      const { courseConsumption, netReceipts, revenue, ...operations } = overview;
      const financials = courseConsumption && netReceipts ? {
        courseConsumption: moneyByPeriod(courseConsumption),
        netReceipts: moneyByPeriod(netReceipts)
      } : null;
      this.setData({
        overview: { ...operations, financials, financialsPending: !financials && !!revenue },
        unavailable: false
      });
    });
  }
});
