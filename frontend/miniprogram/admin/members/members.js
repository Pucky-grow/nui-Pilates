const u = require("../common");
Page({
  data: { list: [], total: 0, page: 1, keyword: "", appliedKeyword: "", busy: false, error: "", ledgerStatus: "checking" },
  onShow() {
    return this.refresh();
  },
  onPullDownRefresh() {
    return this.refresh();
  },
  refresh() {
    if (!u.guard("member:view")) {
      wx.stopPullDownRefresh();
      return;
    }
    return Promise.all([this.load(), this.checkLedger()]);
  },
  async checkLedger() {
    if (this.checkingLedger) return;
    this.checkingLedger = true;
    const token = wx.getStorageSync("adminToken");
    this.setData({ ledgerStatus: "checking" });
    try {
      const me = await u.api.get("/api/auth/me");
      if (wx.getStorageSync("adminToken") !== token) return;
      if (!Array.isArray(me?.roles)) throw new Error("管理员身份响应异常");
      wx.setStorageSync("adminRoles", me.roles);
      wx.setStorageSync("adminPerms", me.permissions || []);
      if (!me.roles.includes("ADMIN")) {
        this.setData({ ledgerStatus: "restricted" });
        return;
      }
      const capabilities = await u.api.get("/api/capabilities", undefined, { quiet: true });
      if (wx.getStorageSync("adminToken") !== token) return;
      if (typeof capabilities?.studioLedger !== "boolean") throw new Error("会员账本配置响应异常");
      this.setData({ ledgerStatus: capabilities.studioLedger ? "ready" : "disabled" });
    } catch (_) {
      this.setData({ ledgerStatus: "unavailable" });
    } finally {
      this.checkingLedger = false;
    }
  },
  onInput(e) {
    this.setData({ keyword: e.detail.value });
  },
  load() {
    return this.fetch(false);
  },
  more() {
    return this.fetch(true);
  },
  fetch(append) {
    return u.run(this, async () => {
      const page = append ? this.data.page + 1 : 1;
      const keyword = append ? this.data.appliedKeyword : this.data.keyword.trim();
      const d = await u.api.get("/admin/members", { keyword, page, size: 20 });
      const rows = d.list.map(x => ({ ...x, id: x.member.userId, statusLabel: u.status(x.member.status) }));
      this.setData({ page, appliedKeyword: keyword, total: d.total, list: (append ? this.data.list : []).concat(rows) });
    });
  },
  createOffline() {
    if (this.data.ledgerStatus === "ready") wx.navigateTo({ url: "/admin/studio-ledger/studio-ledger?create=1" });
  },
  open(e) {
    wx.navigateTo({ url: "/admin/member-detail/member-detail?id=" + e.currentTarget.dataset.id });
  }
});
