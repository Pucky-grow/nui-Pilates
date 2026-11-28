const http = require('../../utils/request');
const { walletAccounts, ledgerRows } = require('../../utils/member-ledger');

const paths = {
  entries: '/api/my/wallet/entries',
  history: '/api/my/lesson-history',
  fees: '/api/my/wallet/cancellation-fees'
};
const tabOptions = [{ key: 'entries', label: '账单明细' }, { key: 'history', label: '补录课程' }, { key: 'fees', label: '取消费' }];
const emptyText = { entries: '暂无余额变动', history: '暂无门店补录课程', fees: '暂无已处理的取消费记录' };

Page({
  data: {
    tab: 'entries', tabOptions, emptyText, groups: [], accounts: [],
    walletLoading: true, walletError: '', listLoading: true, listError: '', more: false
  },
  onLoad(options) {
    if (tabOptions.some(item => item.key === options.tab)) this.setData({ tab: options.tab });
  },
  onShow() {
    if (!http.ensureLogin()) {
      this.rows = [];
      this.setData({ accounts: [], groups: [], more: false });
      return;
    }
    const context = http.captureAuthContext();
    if (!this.context || !http.isAuthContextCurrent(this.context)) {
      this.rows = [];
      this.setData({ accounts: [], groups: [], more: false });
    }
    this.context = context;
    return this.refresh();
  },
  onPullDownRefresh() { return this.refresh().finally(() => wx.stopPullDownRefresh()); },
  refresh() { return Promise.all([this.loadWallet(), this.loadRows(false)]); },
  selectTab(e) {
    const key = e.currentTarget.dataset.tab;
    if (key === this.data.tab || !tabOptions.some(item => item.key === key)) return;
    this.rows = [];
    this.setData({ tab: key, groups: [], more: false });
    return this.loadRows(false);
  },
  next() {
    if (this.data.more && !this.data.listLoading) return this.loadRows(true);
  },
  retryRows() { return this.loadRows(this.retryAppend); },
  async loadWallet() {
    const request = this.walletRequest = (this.walletRequest || 0) + 1;
    const context = http.captureAuthContext();
    this.setData({ walletLoading: true, walletError: '' });
    try {
      const wallet = await http.get('/api/my/wallet', null, { skipAuth: true, silent: true });
      if (request === this.walletRequest && http.isAuthContextCurrent(context)) this.setData({ accounts: walletAccounts(wallet) });
    } catch (error) {
      if (request === this.walletRequest && http.isAuthContextCurrent(context)) {
        this.setData({ walletError: error?.message || '余额读取失败' });
      }
    } finally {
      if (request === this.walletRequest) this.setData({ walletLoading: false });
    }
  },
  async loadRows(append) {
    const request = this.listRequest = (this.listRequest || 0) + 1;
    const context = http.captureAuthContext();
    const { tab } = this.data;
    const previous = append ? this.rows || [] : [];
    const last = previous[previous.length - 1];
    this.setData({ listLoading: true, listError: '' });
    try {
      const rows = await http.get(paths[tab], last ? { before: last.id || last.booking_id } : {}, { skipAuth: true, silent: true });
      if (request !== this.listRequest || !http.isAuthContextCurrent(context)) return;
      this.rows = previous.concat(rows);
      this.setData({ groups: ledgerRows(this.rows, tab), more: rows.length === 30 });
    } catch (error) {
      if (request !== this.listRequest || !http.isAuthContextCurrent(context)) return;
      this.retryAppend = append;
      this.setData({ listError: error?.message || '账单读取失败' });
    } finally {
      if (request === this.listRequest) this.setData({ listLoading: false });
    }
  }
});
