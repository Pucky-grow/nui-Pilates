const u = require("../common");
Page({ data: { list: [], total: 0, page: 1, keyword: "", busy: false, error: "", canCreate: false, canUpdate: false }, onLoad() {
  this.setData({ canCreate: u.can("course:create"), canUpdate: u.can("course:update") });
  if (u.guard("course:view")) this.load();
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
    const d = await u.api.get("/admin/courses", { keyword: this.data.keyword, page, size: 20 });
    this.setData({ page, total: d.total, list: (append ? this.data.list : []).concat(d.list.map((c) => ({ ...c, statusLabel: u.status(c.status) }))) });
  });
}, edit(e) {
  wx.navigateTo({ url: "/admin/course-edit/course-edit" + (e.currentTarget.dataset.id ? "?id=" + e.currentTarget.dataset.id : "") });
}, categories() {
  wx.navigateTo({ url: "/admin/categories/categories" });
}, qualifications(e) {
  wx.navigateTo({ url: "/admin/qualifications/qualifications?id=" + e.currentTarget.dataset.id });
}, shelf(e) {
  return u.run(this, async () => {
    const c = this.data.list.find((c2) => c2.id === e.currentTarget.dataset.id);
    const on = c.status !== "ON_SHELF";
    if (!await u.confirm(on ? "上架课程" : "下架课程", on ? "上架后仍需有效授课资格和排课才可预约。" : "下架后停止新增预约，已有预约和历史记录保留。")) return;
    await u.api.put("/admin/courses/" + c.id + "/shelf?onShelf=" + on, {});
    c.status = on ? "ON_SHELF" : "OFF_SHELF";
    c.statusLabel = u.status(c.status);
    this.setData({ list: this.data.list });
    u.success();
  });
} });
