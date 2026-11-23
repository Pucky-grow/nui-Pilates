const u = require("../common");
const o = u.option;
Page({ data: { list: [], id: "", form: { "name": "", "type": "REFORMER", "capacity": 8, "status": "ACTIVE", "equipment": "", "remark": "" }, fields: [], editing: false, busy: false, error: "", canEdit: false }, onLoad() {
  this.setData({ canEdit: u.can("classroom:manage") });
  if (u.guard("classroom:manage")) this.load();
}, onPullDownRefresh() {
  this.load();
}, load() {
  return u.run(this, async () => this.setData({ list: (await u.api.get("/admin/classrooms")).map((r) => ({ ...r, statusLabel: u.status(r.status) })) }));
}, edit(e) {
  const id = e.currentTarget.dataset.id || "";
  this.setData({ id, form: id ? { ...this.data.list.find((r) => r.id === id) } : { "name": "", "type": "REFORMER", "capacity": 8, "status": "ACTIVE", "equipment": "", "remark": "" }, editing: true });
  this.setData({ fields: [{ key: "name", label: "教室名称", required: true }, { key: "type", label: "类型", type: "select", options: [o("REFORMER", "器械"), o("MAT", "垫上"), o("PRIVATE", "私教"), o("GROUP", "通用")] }, { key: "capacity", label: "教室容量", type: "number", required: true }, { key: "equipment", label: "设备说明", type: "textarea", maxlength: 512 }, { key: "remark", label: "备注", type: "textarea", maxlength: 512 }, { key: "status", label: "状态", type: "select", options: [o("ACTIVE", "启用"), o("DISABLED", "停用")] }] });
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
    if (this.data.id) await u.api.put("/admin/classrooms/" + this.data.id, body);
    else await u.api.post("/admin/classrooms", body);
    this.setData({ editing: false, list: (await u.api.get("/admin/classrooms")).map((r) => ({ ...r, statusLabel: u.status(r.status) })) });
    u.success();
  });
} });
