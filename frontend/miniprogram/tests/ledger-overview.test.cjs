const test = require('node:test'), assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const root = path.join(__dirname, '..');

function moduleAt(file, context = {}) {
  const module = { exports: {} };
  vm.runInNewContext(fs.readFileSync(path.join(root, file), 'utf8'), { module, console, Date, Promise, setTimeout, clearTimeout, ...context }, { filename: file });
  return module.exports;
}

function pageHarness(file, responses = {}) {
  let def; const calls = [], storage = new Map([['adminToken', 'fixture'], ['adminRoles', ['ADMIN']]]), nav = [];
  const wx = { getStorageSync: k => storage.get(k), setStorageSync: (k, v) => storage.set(k, v), showToast() {}, stopPullDownRefresh() {}, reLaunch: o => nav.push(o.url), navigateTo: o => nav.push(o.url), redirectTo: o => { nav.push(o.url); o.success?.(); }, showModal: o => o.success({ confirm: true, content: '隔离测试原因' }) };
  const api = {}; for (const verb of ['get', 'post', 'put', 'del']) api[verb] = async (url, body) => { calls.push({ verb, url, body }); const response = responses[url]; if (response instanceof Error) throw response; return typeof response === 'function' ? response(body, verb) : response === undefined ? {} : response; };
  const u = moduleAt('admin/common.js', { wx, require: () => api });
  const modules = { '../common': u, '../api': api, '../../utils/studio-ledger': require('../utils/studio-ledger'), '../../utils/util': require('../utils/util'), '../../utils/schedule-form': require('../utils/schedule-form') };
  moduleAt(file, { wx, getApp: () => ({ globalData: { baseUrl: 'http://127.0.0.1:8090' } }), require: p => modules[p] || { clearAuth() { storage.clear(); } }, Page: d => def = d });
  const page = { ...def, data: JSON.parse(JSON.stringify(def.data)), setData(values) { for (const [k, v] of Object.entries(values)) { const parts = k.split('.'); let target = this.data; while (parts.length > 1) { const p = parts.shift(); target[p] ??= {}; target = target[p]; } target[parts[0]] = v; } } };
  return { page, calls, storage, nav, wx };
}

const endpoint = '/api/admin/statistics/studio-ledger';
const operations = {
  attended: { today: 1, week: 4, month: 12, total: 88 },
  remainingCredits: { total: 8, group: 5, private: 3 },
  upcomingBookings: 2
};
const overview = {
  ...operations,
  revenue: { today: 100, week: 250, month: 900, total: 12345.6 },
  courseConsumption: { today: 0, week: 120, month: 700, total: 12345.6 },
  netReceipts: { today: -12, week: 42.5, month: 800, total: 15000 }
};

test('默认展示累计，切换时段只改变时段内的指标', async () => {
  const h = pageHarness('admin/ledger-overview/ledger-overview.js', { [endpoint]: overview });
  await h.page.onShow();
  assert.equal(h.calls[0].url, endpoint);
  assert.equal(h.page.data.period, 'total');
  const view = h.page.data.overview;
  assert.equal(view.financials.courseConsumption.total, '¥12,345.60');
  assert.equal(view.financials.netReceipts.total, '¥15,000.00');
  assert.equal(view.attended.total, 88);

  h.page.selectPeriod({ currentTarget: { dataset: { period: 'today' } } });
  const period = h.page.data.period;
  assert.equal(period, 'today');
  assert.equal(view.financials.courseConsumption[period], '¥0.00');
  assert.equal(view.financials.netReceipts[period], '−¥12.00');
  assert.equal(view.attended[period], 1);
  assert.equal(view.remainingCredits.group, 5);
  assert.equal(view.upcomingBookings, 2);
  assert.equal(h.calls.length, 1);
});

test('后端隐藏金额时仍展示运营数字', async () => {
  const h = pageHarness('admin/ledger-overview/ledger-overview.js', { [endpoint]: operations });
  await h.page.load();
  assert.equal(h.page.data.overview.financials, null);
  assert.equal(h.page.data.overview.financialsPending, false);
  assert.equal(h.page.data.overview.attended.today, 1);
});

test('新版页面接到旧接口时提示服务待更新', async () => {
  const oldOverview = { ...operations, revenue: overview.revenue };
  const h = pageHarness('admin/ledger-overview/ledger-overview.js', { [endpoint]: oldOverview });
  await h.page.load();
  assert.equal(h.page.data.overview.financials, null);
  assert.equal(h.page.data.overview.financialsPending, true);
  assert.equal(h.page.data.overview.revenue, undefined);
});

test('换号时清除先前金额并忽略迟到响应', async () => {
  let deliver;
  const freshResponse = new Promise(resolve => { deliver = resolve; });
  let refreshing = false;
  const h = pageHarness('admin/ledger-overview/ledger-overview.js', {
    [endpoint]: () => refreshing ? freshResponse : overview
  });
  await h.page.load();
  assert.equal(h.page.data.overview.financials.courseConsumption.total, '¥12,345.60');
  refreshing = true;
  const pending = h.page.onPullDownRefresh();
  h.storage.set('adminToken', 'different-user');
  h.storage.set('adminRoles', ['STAFF']);
  await h.page.onShow();
  assert.equal(h.page.data.overview, null);
  deliver(overview);
  await pending;
  assert.equal(h.page.data.overview, null);
});

test('读取失败提示错误, 重试成功后恢复', async () => {
  let fail = true;
  const h = pageHarness('admin/ledger-overview/ledger-overview.js', {
    [endpoint]: () => { if (fail) throw new Error('offline'); return overview; }
  });
  await h.page.load();
  assert.ok(h.page.data.error.includes('offline'));
  assert.equal(h.page.data.overview, null);
  fail = false;
  await h.page.load();
  assert.equal(h.page.data.error, '');
  assert.equal(h.page.data.overview.financials.courseConsumption.today, '¥0.00');
});
