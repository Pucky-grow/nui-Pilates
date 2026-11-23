const u = require("../common");
const o = u.option;
Page({ data: { id: "", form: { courseDate: u.date(), startTime: "09:00", endTime: "10:00", maxCapacity: 8 }, fields: [], options: {}, busy: false, error: "", history: false, canEdit: false, canCancel: false }, onLoad(q) {
  this.setData({ id: q.id || "", "form.courseDate": q.date || u.date() });
  if (u.guard(q.id ? "schedule:view" : "schedule:create")) this.load();
}, onPullDownRefresh() {
  this.load();
}, load() {
  return u.run(this, async () => {
    const options = await u.api.get("/admin/schedules/options");
    let form = this.data.form;
    let history = false, canEdit = u.can("schedule:create"), canCancel = false, reason = "";
    if (this.data.id) {
      const row = await u.api.get("/admin/schedules/" + this.data.id);
      const s = row;
      this.setData({originalSummary:[s.courseName,s.coachName,s.classroomName].filter(Boolean).join(' · ')});
      form = { courseId: s.courseId, coachId: s.coachId, classroomId: s.classroomId, courseDate: s.courseDate, startTime: s.startTime.slice(0, 5), endTime: s.endTime.slice(0, 5), maxCapacity: s.maxCapacity };
      history = row.hasBookingHistory;
      canEdit = row.editable && u.can("schedule:update");
      canCancel = row.cancellable && u.can("schedule:cancel");
      reason = row.managementReason;
    }
    this.setData({ options, form, history, canEdit, canCancel, managementReason: reason });
    this.fields();
  });
}, fields() {
  const data = this.data;
  const courses = data.options.courses || [];
  const privateClass = courses.some(c => c.id === Number(data.form.courseId) && c.type === "PRIVATE");
  const qualifications = data.options.qualifications || [];
  const coaches = (data.options.coaches || []).filter((c) => qualifications.some((q) => q.courseId === Number(data.form.courseId) && q.coachId === c.id));
  this.setData({ privateClass, fields: [{ key: "courseId", label: "课程", type: "select", options: courses.map((c) => o(c.id, c.name)), readonly: data.history }, { key: "coachId", label: "授课教练（仅有效资格）", type: "select", options: coaches.map((c) => o(c.id, c.name)), readonly: data.history }, { key: "classroomId", label: "教室", type: "select", options: (data.options.classrooms || []).map((c) => o(c.id, c.name + "（容量" + c.capacity + "）")), readonly: data.history }, { key: "courseDate", label: "上课日期", type: "date", readonly: data.history }, { key: "startTime", label: "开始时间", type: "time", readonly: data.history }, { key: "endTime", label: "结束时间", type: "time", readonly: data.history }, { key: "maxCapacity", label: "人数上限", type: "number", readonly: privateClass, hint: privateClass ? "一对一私教固定为1人" : "不得低于实际占位人数，不得超过教室容量" }] });
}, change(e) {
  const patch = { ["form." + e.detail.key]: e.detail.value };
  if (e.detail.key === "courseId") {
    patch["form.coachId"] = "";
    const selected = this.data.options.courses.find(c => c.id === Number(e.detail.value));
    if (selected?.type === "PRIVATE") patch["form.maxCapacity"] = 1;
    else if (this.data.privateClass) patch["form.maxCapacity"] = 8;
  }
  this.setData(patch);
  this.fields();
}, save() {
  return u.run(this, async () => {
    const f = this.data.form;
    const body = { ...f, courseId: Number(f.courseId), coachId: Number(f.coachId), classroomId: Number(f.classroomId), maxCapacity: this.data.privateClass ? 1 : Number(f.maxCapacity) };
    if (!body.courseId || !body.coachId || !body.classroomId || !Number.isInteger(body.maxCapacity) || body.maxCapacity < 1) throw { message: "请选择课程、教练、教室并填写正整数容量" };
    if (!await u.confirm("保存排课", "已有预约历史时仅允许改容量；服务端将检查教练、教室冲突和资格。")) return;
    if (this.data.id) await u.api.put("/admin/schedules/" + this.data.id, body);
    else this.setData({ id: await u.api.post("/admin/schedules", body) });
    u.success();
  });
}, cancel() {
  return u.run(this, async () => {
    if (!await u.confirm("取消整场排课", "该场次所有预约都会取消，填写的原因会展示给会员。")) return;
    const reason = await u.reason("填写整场取消原因", "");
    if (!reason) return;
    await u.api.post("/admin/schedules/" + this.data.id + "/cancel?reason=" + encodeURIComponent(reason), {});
    this.setData({ canCancel: false, canEdit: false, managementReason: "排课已取消" });
    u.success();
  });
}, students() {
  wx.navigateTo({ url: "/admin/bookings/bookings?scheduleId=" + this.data.id });
} });
