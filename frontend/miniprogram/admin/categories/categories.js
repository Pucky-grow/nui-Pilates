const u = require("../common");
const o = u.option;
Page({ data: { list: [], id: "", form: { "code": "", "name": "", "sort": 0, "status": "ACTIVE" }, fields: [], editing: false, busy: false, error: "", canEdit: false }, onLoad() {
  this.setData({ canEdit: u.can("course:update") });
  if (u.guard("course:view")) this.load();
}, onPullDownRefresh() {
  this.load();
}, load() {
  return u.run(this, async () => this.setData({ list: (await u.api.get("/admin/course-categories")).map((r) => ({ ...r, statusLabel: u.status(r.status) })) }));
}, edit(e) {
  const id = e.currentTarget.dataset.id || "";
  this.setData({ id, form: id ? { ...this.data.list.find((r) => r.id === id) } : { "code": "", "name": "", "sort": 0, "status": "ACTIVE" }, editing: true });
  this.setData({ fields: [{ key: "code", label: "分类编码（新增后不可修改）", readonly: !!this.data.id, required: true }, { key: "name", label: "分类名称", required: true }, { key: "sort", label: "排序", type: "number" }, { key: "status", label: "状态", type: "select", options: [o("ACTIVE", "启用"), o("DISABLED", "停用")] }] });
}, change(e) {
  this.setData({ ["form." + e.detail.key]: e.detail.value });
}, close() {
  this.setData({ editing: false });
}, save() {
  return u.run(this, async () => {
    const f = this.data.form;
    const body = {};
    this.data.fields.forEach((x) => {
      if (f[x.key] !== void 0) body[x.key] = x.type === "number" ? Number(f[x.key]) : f[x.key];
    });
    if (!body.name) throw { message: "请输入名称" };
    if (!await u.confirm("保存设置", "修改会影响后续选课、排课；有关联排课的停用或缩容可能被拒绝。")) return;
    if (this.data.id) await u.api.put("/admin/course-categories/" + this.data.id, body);
    else await u.api.post("/admin/course-categories", body);
    this.setData({ editing: false, list: (await u.api.get("/admin/course-categories")).map((r) => ({ ...r, statusLabel: u.status(r.status) })) });
    u.success();
  });
} });
