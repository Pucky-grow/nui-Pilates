const u = require("../common");
Page({ data: { list: [], total: 0, page: 1, keyword: "", busy: false, error: "" }, onLoad() {
  if (u.guard("coach:manage")) this.load();
}, onShow() {
  if (this.loaded) this.load();
  this.loaded = true;
}, onPullDownRefresh() {
  this.load();
}, input(e) {
  this.setData({ keyword: e.detail.value });
}, load() {
  return this.fetch(false);
}, more() {
  return this.fetch(true);
}, fetch(append) {
  return u.run(this, async () => {
    const page = append ? this.data.page + 1 : 1;
    const d = await u.api.get("/admin/coaches", { keyword: this.data.keyword, page, size: 20 });
    this.setData({ page, total: d.total, list: (append ? this.data.list : []).concat(d.list.map((c) => ({ ...c, statusLabel: u.status(c.status) }))) });
  });
}, edit(e) {
  wx.navigateTo({ url: "/admin/coach-edit/coach-edit" + (e.currentTarget.dataset.id ? "?id=" + e.currentTarget.dataset.id : "") });
} });
