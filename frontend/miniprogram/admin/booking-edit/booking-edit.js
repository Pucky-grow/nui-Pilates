const u = require("../common");
const display = require("../../utils/studio-ledger");
Page({ data: { userId: "", bookingId: "", scheduleId: "", keyword: "", members: [], memberName: "", list: [], date: u.date(), reason: "", remark: "", busy: false, error: "", canManage: false, fixedMember: false, ledgerBooking: false }, onLoad(q) {
  this.ledgerMemberId = q.ledgerMemberId;
  this.sendLedger = display.commands(u.api);
  this.initialSchedule = q.scheduleId || "";
  this.initialBooking = q.bookingId || "";
  this.setData({ userId: q.userId || "", bookingId: q.bookingId || "", scheduleId: q.scheduleId || "",
    canManage: u.isAdmin(), fixedMember: Boolean(q.bookingId || q.ledgerMemberId), ledgerBooking: Boolean(q.ledgerMemberId) });
  if (!u.guard("booking:manage")) return;
  if (!u.isAdmin()) {
    this.setData({ error: "只有管理员可以代预约或为会员换场次。" });
    return;
  }
  return this.load();
}, onPullDownRefresh() {
  this.load();
}, load() {
  if (!u.isAdmin()) return;
  return u.run(this, async () => {
    if (this.initialSchedule) {
      const row = await u.api.get("/api/course-schedules/" + this.initialSchedule);
      this.setData({ date: row.courseDate });
      this.initialSchedule = "";
    } else if (this.initialBooking) {
      const row = await u.api.get("/api/admin/bookings/" + this.initialBooking);
      this.setData({ date: row.booking.courseDate, userId: row.userId || this.data.userId });
      this.initialBooking = "";
    }
    const list = await u.api.get("/api/course-schedules", { date: this.data.date, days: 1 });
    const patch = { list };
    if (this.ledgerMemberId) {
      const { member } = await u.api.get("/api/admin/studio-ledger/members/" + this.ledgerMemberId);
      patch.userId = member.user_id;
      patch.memberName = member.nickname || member.member_no;
    } else if (this.data.userId) {
      const m = await u.api.get("/admin/members/" + this.data.userId);
      patch.memberName = m.user.nickname || m.member.memberNo;
    }
    this.setData(patch);
  });
}, input(e) {
  this.setData({ [e.currentTarget.dataset.key]: e.detail.value });
}, search() {
  if (!this.data.canManage || this.data.fixedMember) return;
  return u.run(this, async () => {
    const d = await u.api.get("/admin/members", { keyword: this.data.keyword, size: 20 });
    this.setData({ members: d.list.map((x) => ({ ...x, id: x.member.userId })) });
  });
}, pickMember(e) {
  if (this.data.fixedMember || this.data.busy) return;
  const x = this.data.members.find((m) => String(m.member.userId) === String(e.currentTarget.dataset.id));
  if (x) this.setData({ userId: x.member.userId, memberName: x.nickname || x.member.memberNo, members: [] });
}, date(e) {
  this.setData({ date: e.detail.value, scheduleId: "" });
  this.load();
}, pick(e) {
  const selected = this.data.list.find(row => String(row.id) === String(e.currentTarget.dataset.id));
  if (selected?.bookable && !this.data.busy) this.setData({ scheduleId: selected.id });
}, submit() {
  if (!u.isAdmin()) return;
  return u.run(this, async () => {
    if (!this.data.userId || !this.data.scheduleId || !this.data.reason.trim()) throw { message: "请选择会员、排课并填写原因" };
    const chosen = this.data.list.find((s) => String(s.id) === String(this.data.scheduleId));
    if (!chosen || !chosen.bookable) throw { message: "请选择当前可预约排课" };
    if (this.ledgerMemberId) {
      const path = "/api/admin/studio-ledger";
      const terms = await u.api.get(path + "/booking-terms/" + this.data.scheduleId);
      let quote;
      if (terms) {
        const description = "课程价 ¥" + display.money(terms.priceFen) + "，" +
          display.displayTime(terms.cutoffAt) + " 后临时取消费 ¥" + display.money(terms.lateFen) + "。请先向会员确认。";
        const choice = await new Promise(resolve => wx.showModal({
          title: "确认预约及取消约定", content: description, confirmText: "确认预约", cancelText: "返回",
          success: result => resolve(result.confirm), fail: () => resolve(false)
        }));
        if (!choice) return;
        quote = terms.quote;
      } else if (!await u.confirm("确认代会员预约", this.data.memberName + " · " + chosen.courseName)) return;
      const id = await this.sendLedger(path + "/members/" + this.ledgerMemberId + "/bookings", {
        scheduleId: Number(this.data.scheduleId), reason: this.data.reason.trim(), quote
      });
      wx.redirectTo({ url: "/admin/booking-detail/booking-detail?id=" + id });
      return;
    }
    if (!await u.confirm(this.data.bookingId ? "确认换场次" : "确认代会员预约", this.data.memberName + " → " + chosen.courseName + " " + chosen.courseDate + " " + chosen.startTime + "（" + chosen.coachName + "）")) return;
    const body = { userId: Number(this.data.userId), scheduleId: Number(this.data.scheduleId), reason: this.data.reason.trim(), remark: this.data.remark };
    const id = await u.api.post(this.data.bookingId ? "/api/admin/bookings/" + this.data.bookingId + "/move" : "/api/admin/bookings", body);
    u.success();
    wx.redirectTo({ url: "/admin/booking-detail/booking-detail?id=" + id });
  });
} });
