const test = require('node:test'), assert = require('node:assert/strict'), fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const root = path.join(__dirname, '..');
const createTransport = require('./support/transport.cjs');

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

const overview = { revenue: { today: 100, week: 250, month: 900, total: 12345.6 }, attended: { today: 1, week: 4, month: 12, total: 88 }, remainingCredits: { total: 8, group: 5, private: 3 }, upcomingBookings: 2 };

test('经营账本页面格式化营业额并保留课次原值', async () => {
  const h = pageHarness('admin/ledger-overview/ledger-overview.js', { '/api/admin/statistics/studio-ledger': overview });
  await h.page.load();
  assert.equal(h.calls[0].url, '/api/admin/statistics/studio-ledger');
  assert.equal(h.page.data.overview.revenue.today, '100.00');
  assert.equal(h.page.data.overview.revenue.week, '250.00');
  assert.equal(h.page.data.overview.revenue.total, '12345.60');
  assert.equal(h.page.data.overview.attended.total, 88);
  assert.equal(h.page.data.overview.remainingCredits.group, 5);
  assert.equal(h.page.data.overview.upcomingBookings, 2);
  assert.equal(h.page.data.unavailable, false);
});

test('后端隐藏营业额时页面不渲染金额', async () => {
  const h = pageHarness('admin/ledger-overview/ledger-overview.js', {
    '/api/admin/statistics/studio-ledger': { attended: overview.attended, remainingCredits: overview.remainingCredits, upcomingBookings: 0 }
  });
  await h.page.load();
  assert.equal(h.page.data.overview.revenue, null);
  assert.equal(h.page.data.overview.attended.today, 1);
});

test('读取失败提示错误, 重试成功后恢复', async () => {
  let fail = true;
  const h = pageHarness('admin/ledger-overview/ledger-overview.js', {
    '/api/admin/statistics/studio-ledger': () => { if (fail) throw new Error('offline'); return overview; }
  });
  await h.page.load();
  assert.ok(h.page.data.error.includes('offline'));
  assert.equal(h.page.data.overview, null);
  fail = false;
  await h.page.load();
  assert.equal(h.page.data.error, '');
  assert.equal(h.page.data.overview.revenue.today, '100.00');
});
