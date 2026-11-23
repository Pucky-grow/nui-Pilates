const u = require("../common");
Page({ data: { list: [], total: 0, page: 1, date: u.date(), busy: false, error: "", canCreate: false, filters: { coachId: "", courseId: "", status: "" }, fields: [] }, onLoad() {
  this.setData({ canCreate: u.can("schedule:create") });
  if (u.guard("schedule:view")) this.load();
}, onShow() {
  if (this.loaded) this.load();
  this.loaded = true;
}, onPullDownRefresh() {
  this.load();
}, date(e) {
  this.setData({ date: e.detail.value });
  this.load();
}, load() {
  return this.fetch(false);
}, more() {
  return this.fetch(true);
}, fetch(append) {
  return u.run(this, async () => {
    const page = append ? this.data.page + 1 : 1;
    if (!this.data.fields.length) {
      const options = await u.api.get("/admin/schedules/options");
      const o = u.option;
      this.setData({ fields: [{ key: "coachId", label: "教练", type: "select", options: [o("", "全部教练"), ...options.coaches.map((c) => o(c.id, c.name))] }, { key: "courseId", label: "课程", type: "select", options: [o("", "全部课程"), ...options.courses.map((c) => o(c.id, c.name))] }, { key: "status", label: "排课状态", type: "select", options: [o("", "全部状态"), o("SCHEDULED", "待开课"), o("CANCELLED", "已取消"), o("FINISHED", "已结束")] }] });
    }
    const d = await u.api.get("/admin/schedules", { ...this.data.filters, fromDate: this.data.date, toDate: this.data.date, page, size: 20 });
    this.setData({ page, total: d.total, list: (append ? this.data.list : []).concat(d.list.map((s) => ({ ...s, statusLabel: u.status(s.status) }))) });
  });
}, filterChange(e) {
  if (this.data.busy) return;
  this.setData({ ["filters." + e.detail.key]: e.detail.value });
  return this.load();
}, edit(e) {
  wx.navigateTo({ url: "/admin/schedule-edit/schedule-edit" + (e.currentTarget.dataset.id ? "?id=" + e.currentTarget.dataset.id : "?date=" + this.data.date) });
}, students(e) {
  wx.navigateTo({ url: "/admin/bookings/bookings?scheduleId=" + e.currentTarget.dataset.id });
} });
