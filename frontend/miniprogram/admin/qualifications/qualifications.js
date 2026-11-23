const u = require("../common");
Page({ data: { id: "", list: [], coaches: [], selected: 0, busy: false, error: "" }, onLoad(q) {
  this.setData({ id: q.id });
  if (u.guard("manager:course:coach:manage")) this.load();
}, onPullDownRefresh() {
  this.load();
}, load() {
  return u.run(this, async () => {
    const [rows, coaches] = await Promise.all([u.api.get("/admin/courses/" + this.data.id + "/coaches"), u.api.get("/api/coaches")]);
    this.setData({ coaches, list: rows.map((r) => ({ ...r, name: coaches.find((c) => c.id === r.coachId)?.name || "已停用教练", statusLabel: u.status(r.status) })) });
  });
}, select(e) {
  this.setData({ selected: Number(e.detail.value) });
}, assign() {
  return u.run(this, async () => {
    const c = this.data.coaches[this.data.selected];
    if (!c) throw { message: "请选择教练" };
    if (!await u.confirm("指派 / 恢复资格", "仅为当前课程授予 " + c.name + " 的授课资格。")) return;
    await u.api.put("/admin/courses/" + this.data.id + "/coaches/" + c.id, {});
    u.success();
    const rows = await u.api.get("/admin/courses/" + this.data.id + "/coaches");
    this.setData({ list: rows.map((r) => ({ ...r, name: this.data.coaches.find((c2) => c2.id === r.coachId)?.name || "已停用教练", statusLabel: u.status(r.status) })) });
  });
}, disable(e) {
  return u.run(this, async () => {
    if (!await u.confirm("停用授课资格", "该教练当前课程将暂停新增预约，历史预约保留。")) return;
    await u.api.post("/admin/courses/" + this.data.id + "/coaches/" + e.currentTarget.dataset.id + "/disable", {});
    const row = this.data.list.find((r) => r.coachId === e.currentTarget.dataset.id);
    row.status = "DISABLED";
    row.statusLabel = u.status(row.status);
    this.setData({ list: this.data.list });
    u.success();
  });
} });
