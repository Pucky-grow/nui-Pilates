const u = require("../common");
Page({ data: { form: {}, fields: [], busy: false, error: "", mode: "", window: "" }, onLoad() {
  if (u.guard("system:manage")) this.load();
}, onPullDownRefresh() {
  this.load();
}, load() {
  return u.run(this, async () => {
    const d = await u.api.get("/api/admin/workspace/rules");
    this.setData({ form: d.values, mode: d.bookingMode, window: d.checkinWindow, fields: [{ key: "booking.advance_days", label: "提前开放预约天数（0—365）", type: "number" }, { key: "booking.deadline_minutes", label: "开课前停止预约分钟（0—1440）", type: "number" }, { key: "booking.max_per_day", label: "每位会员每日预约上限（1—50）", type: "number" }, { key: "booking.cancel_free_hours", label: "免费取消提前小时（0—720）", type: "number" }, { key: "booking.cancel_late_deduct", label: "历史课包模式晚取消扣课时（0—100）", type: "number", hint: "FREE 免费预约仍不扣课时" }] });
  });
}, change(e) {
  const form = { ...this.data.form, [e.detail.key]: e.detail.value };
  this.setData({ form });
}, save() {
  return u.run(this, async () => {
    const values = {};
    for (const k of Object.keys(this.data.form)) {
      const n = Number(this.data.form[k]);
      if (!Number.isInteger(n)) throw { message: "规则必须是整数" };
      values[k] = n;
    }
    if (!await u.confirm("修改预约规则", "保存后影响后续操作；已有预约保留，FREE 模式保持不变。")) return;
    await u.api.put("/api/admin/workspace/rules", values);
    u.success();
  });
} });
