const u = require("../common");
Page({ data: { list: [], total: 0, page: 1, userId: "", scheduleId: "", keyword: "", appliedKeyword: "", status: "", date: "", busy: false, error: "", statuses: ["全部", "已预约", "已签到", "已完成", "未到课", "已取消"], statusValues: ["", "BOOKED", "SIGNED_IN", "COMPLETED", "NO_SHOW", "CANCELLED"], statusIndex: 0, canManage: false }, onLoad(q) {
  this.setData({ userId: q.userId || "", scheduleId: q.scheduleId || "", canManage: u.isAdmin() });
  if (u.guard("member:view")) this.load();
}, onShow() {
  if (this.loaded) this.load();
  this.loaded = true;
}, onPullDownRefresh() {
  this.load();
}, input(e) {
  this.setData({ keyword: e.detail.value });
}, date(e) {
  this.setData({ date: e.detail.value });
  this.load();
}, clearDate() {
  this.setData({ date: "" });
  this.load();
}, status(e) {
  const i = Number(e.detail.value);
  this.setData({ statusIndex: i, status: this.data.statusValues[i] });
  this.load();
}, load() {
  return this.fetch(false);
}, more() {
  return this.fetch(true);
}, fetch(append) {
  return u.run(this, async () => {
    const page = append ? this.data.page + 1 : 1;
    const keyword = append ? this.data.appliedKeyword : this.data.keyword.trim();
    const d = await u.api.get("/api/admin/bookings", { userId: this.data.userId, scheduleId: this.data.scheduleId, keyword, status: this.data.status, fromDate: this.data.date, toDate: this.data.date, page, size: 20 });
    this.setData({ page, appliedKeyword: keyword, total: d.total, list: (append ? this.data.list : []).concat(d.list.map(x => ({ ...x, id: x.booking.id, statusLabel: u.status(x.booking.status) }))) });
  });
}, open(e) {
  wx.navigateTo({ url: "/admin/booking-detail/booking-detail?id=" + e.currentTarget.dataset.id });
}, add() {
  if (this.data.canManage) wx.navigateTo({ url: "/admin/booking-edit/booking-edit?userId=" + this.data.userId + "&scheduleId=" + this.data.scheduleId });
} });
