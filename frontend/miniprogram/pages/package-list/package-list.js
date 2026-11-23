// Existing package entry keeps historical entitlements available while new sales stay closed.
const api = require('../../services/api');
const http = require('../../utils/request');
Page({
  data: { packages: [], history: [], packageSales: false, loading: false, error: '' },
  onShow() {
    if (!http.ensureLogin()) return;
    this.setData({ loading: true, error: '' });
    Promise.all([http.get('/api/capabilities'), api.myPackages()]).then(([capabilities, history]) => {
      this.setData({ packageSales: capabilities.packageSales, history });
      if (capabilities.packageSales) return api.packages().then(packages => this.setData({ packages }));
    }).catch(() => this.setData({ error: '历史课包加载失败，请重试' })).finally(() => this.setData({ loading: false }));
  },
  retry() { this.onShow(); },
  goDetail(e) { wx.navigateTo({ url: '/pages/package-detail/package-detail?id=' + e.currentTarget.dataset.id }); }
});
