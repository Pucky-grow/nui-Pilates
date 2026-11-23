const u = require("../common");
Page({ data: { list: [], page: 1, total: 0, module: "", busy: false, error: "" }, onLoad() {
  if (u.guard("system:manage")) this.load();
}, onPullDownRefresh() {
  this.load();
}, input(e) {
  this.setData({ module: e.detail.value });
}, load() {
  return this.fetch(false);
}, more() {
  return this.fetch(true);
}, fetch(append) {
  return u.run(this, async () => {
    const page = append ? this.data.page + 1 : 1;
    const d = await u.api.get("/api/admin/workspace/logs", { page, size: 20, module: this.data.module });
    this.setData({ page, total: d.total, list: (append ? this.data.list : []).concat(d.list) });
  });
} });
