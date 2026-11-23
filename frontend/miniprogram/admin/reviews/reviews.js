const u = require("../common");
Page({ data: { leaves: [], changes: [], busy: false, error: "", canLeave: false, canChange: false }, onLoad() {
  this.setData({ canLeave: u.can("member:update"), canChange: u.can("schedule:update") });
  if (u.guard()) this.load();
}, onPullDownRefresh() {
  this.load();
}, load() {
  return u.run(this, async () => {
    const [leaves, changes] = await Promise.all([this.data.canLeave ? u.api.get("/admin/leaves") : [], this.data.canChange ? u.api.get("/admin/change-requests") : []]);
    const [coaches, scheduleOptions] = await Promise.all([u.api.get("/api/coaches"), u.api.get("/admin/schedules/options")]);
    this.setData({ leaves: leaves.map((l) => ({ ...l, coachName: coaches.find((c) => c.id === l.coachId)?.name || "教练" })), changes: changes.map((c) => ({ ...c, coachName: coaches.find((x) => x.id === c.coachId)?.name || "教练", newCoachName: coaches.find((x) => x.id === c.newCoachId)?.name || "", newClassroomName: scheduleOptions.classrooms.find((x) => x.id === c.newClassroomId)?.name || "" })) });
  });
}, openSchedule(e) {
  wx.navigateTo({ url: "/admin/schedule-edit/schedule-edit?id=" + e.currentTarget.dataset.id });
}, review(e) {
  return u.run(this, async () => {
    const { id, type, approve } = e.currentTarget.dataset;
    let content = "请填写审批原因。";
    let previewParams = "";
    if (type === "change-requests" && approve) {
      const { current, proposed, affectedCount } = await u.api.get("/admin/change-requests/" + id + "/preview");
      const row = this.data.changes.find(change => String(change.id) === String(id));
      const time = item => item.courseDate + " " + item.startTime.slice(0, 5) + "–" + item.endTime.slice(0, 5);
      const before = time(current) + " · " + current.coachName + " · " + current.classroomName;
      const after = time(proposed) + " · " + (row?.newCoachName || current.coachName)
        + " · " + (row?.newClassroomName || current.classroomName);
      content = current.courseName + "\n原安排：" + before + "\n新安排：" + after
        + "\n涉及 " + affectedCount + " 笔预约。批准后保留预约并发送站内通知。";
      previewParams = "&scheduleVersion=" + current.version + "&affectedCount=" + affectedCount;
    }
    const reason = await u.reason(approve ? "批准申请" : "拒绝申请", content);
    if (!reason) return;
    await u.api.post("/admin/" + type + "/" + id + "/approve?approve=" + approve + "&rejectReason=" + encodeURIComponent(reason) + previewParams, {});
    this.setData({ [type === "leaves" ? "leaves" : "changes"]: this.data[type === "leaves" ? "leaves" : "changes"].filter((x) => x.id !== id) });
    u.success();
  });
} });
