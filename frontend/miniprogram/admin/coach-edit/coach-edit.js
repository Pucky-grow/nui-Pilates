const u = require("../common");
const o = u.option;
Page({ data: { id: "", form: { name: "", avatar: "", title: "", years: 0, specialties: "", bio: "", sort: 0, status: "ACTIVE" }, fields: [{ key: "name", label: "教练姓名", required: true, maxlength: 32 }, { key: "avatar", label: "头像", type: "image" }, { key: "title", label: "职称", maxlength: 64 }, { key: "years", label: "从业年限", type: "number" }, { key: "specialties", label: "擅长领域", type: "textarea", maxlength: 512 }, { key: "bio", label: "个人介绍", type: "textarea" }, { key: "sort", label: "排序", type: "number" }, { key: "status", label: "状态", type: "select", options: [o("ACTIVE", "启用"), o("DISABLED", "停用")] }], busy: false, uploading: false, error: "", canManage: false, ready: false }, onLoad(q) {
  this.setData({ id: q.id || "", canManage: u.can("coach:manage") });
  if (u.guard("coach:manage") && q.id) return this.load();
}, onPullDownRefresh() {
  if (this.data.id) this.load();
  else wx.stopPullDownRefresh();
}, load() {
  return u.run(this, () => this.read());
}, async read() {
    const c = await u.api.get("/admin/coaches/" + this.data.id);
    const form = {};
    this.data.fields.forEach((f) => form[f.key] = c[f.key] === void 0 || c[f.key] === null ? "" : c[f.key]);
    this.setData({ form, ready: true });
}, change(e) {
  this.setData({ ["form." + e.detail.key]: e.detail.value });
}, onUploadState(e) { this.setData({ uploading: e.detail.uploading }); }, removeCoach() {
  if (!this.data.canManage || !this.data.id || !this.data.ready || this.data.uploading) return;
  return u.run(this, async () => {
    if (!await u.confirm("删除教练档案", "将「" + this.data.form.name + "」从教练档案移除。系统会检查关联排课和历史记录。")) return;
    await u.api.del("/admin/coaches/" + this.data.id);
    wx.showToast({ title: "教练档案已删除", icon: "success" });
    wx.navigateBack({ fail: () => wx.redirectTo({ url: "/admin/coaches/coaches" }) });
  });
}, save() {
  if (this.data.uploading) return;
  return u.run(this, async () => {
    const body = { ...this.data.form, years: Number(this.data.form.years), sort: Number(this.data.form.sort) };
    if (!body.name.trim()) throw { message: "请输入教练姓名" };
    if (!await u.confirm("保存教练资料", "有待开课排课时不能直接停用，请先处理关联排课。")) return;
    if (this.data.id) await u.api.put("/admin/coaches/" + this.data.id, body);
    else this.setData({ id: await u.api.post("/admin/coaches", body) });
    try {
      await this.read();
      u.success();
    } catch (_) {
      this.setData({ error: "教练资料已保存，读取最新资料失败，请刷新重试。" });
    }
  });
} });
