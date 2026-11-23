const u = require("../common");
const { commands, displayTime } = require("../../utils/studio-ledger");
const o = u.option;
const avatarIdentity = url => url.match(/^(?:https?:\/\/[^/]+)?\/media\/(?:ref|access|public)\/([a-f0-9-]{36})(?:\?.*)?$/)?.[1] || url;

Page({
  data: {
    id: null, form: {}, fields: [], busy: false, uploading: false, error: "", canEdit: false, canBook: false, profileOpen: false,
    studioLedger: false, wechatBound: null, bindingStatusLoading: false, bindingStatusFailed: false,
    bindingOpen: false, bindingBusy: false, bindingLoaded: false, bindingError: "", bindingInvite: null,
    inviteCode: "", bindingForm: {}, bindingFields: [
      { key: "proof", label: "本人核对码", maxlength: 8, required: true },
      { key: "reason", label: "身份核对依据", maxlength: 255, required: true, type: "textarea" }
    ]
  },
  async onLoad(q) {
    this.setData({ id: q.id, canEdit: u.can("member:update"), canBook: u.isAdmin() });
    if (!u.guard("member:view")) return;
    this.sendBinding = commands(u.api);
    this.capabilities = u.isAdmin() ? u.api.get('/api/capabilities', undefined, { quiet: true })
      .then(c => this.setData({ studioLedger: c.studioLedger === true })).catch(() => {}) : Promise.resolve();
    await Promise.all([this.load(), this.capabilities]);
    if (q.bind === '1' && this.canBind()) {
      await this.toggleBinding();
    }
  },
  onShow() {
    if (this.data.bindingOpen) return this.refreshInvites();
  },
  async onPullDownRefresh() {
    await this.load();
    if (this.data.bindingOpen) await this.refreshInvites();
  },
  load() {
    if (this.data.uploading || this.data.bindingBusy || this.data.bindingStatusLoading) {
      wx.stopPullDownRefresh();
      return;
    }
    return u.run(this, () => this.read());
  },
  async read() {
    const { user, member, levels, wechatBound } = await u.api.get("/admin/members/" + this.data.id);
    const storedAvatar = user.avatarUrl || "";
    const base = getApp().globalData.baseUrl || "";
    const avatarUrl = /^\/(?!\/)/.test(storedAvatar) ? base + storedAvatar : storedAvatar;
    this.loadedAvatarUrl = avatarUrl;
    this.setData({
      member,
      memberActive: user.status === "ACTIVE",
      memberName: user.nickname || "未填写昵称",
      memberStatus: u.status(member.status),
      levelName: levels.find(level => String(level.id) === String(member.levelId))?.name || "",
      form: { nickname: user.nickname || "", avatarUrl, phone: user.phone || "", gender: user.gender || 0,
        levelId: member.levelId, validUntil: member.validUntil || "", status: member.status },
      fields: [
        { key: "nickname", label: "昵称", required: true },
        { key: "avatarUrl", label: "头像", type: "image", uploadPurpose: "AVATAR", ownerId: Number(this.data.id) },
        { key: "phone", label: "手机号", hint: "修改会记录操作，不改变微信身份绑定" },
        { key: "gender", label: "性别", type: "select", options: [o(0, "未设置"), o(1, "男"), o(2, "女")] },
        { key: "levelId", label: "会员等级", type: "select", options: levels.map(l => o(l.id, l.name)) },
        { key: "validUntil", label: "有效期（留空表示未设置）", type: "date" },
        { key: "status", label: "状态", type: "select", options: [o("ACTIVE", "启用"), o("EXPIRED", "已到期"), o("DISABLED", "停用")] }
      ]
    });
    if (typeof wechatBound === 'boolean') {
      this.setBindingStatus(wechatBound);
    } else {
      await this.capabilities;
      await this.refreshBindingStatus();
    }
    return avatarUrl;
  },
  change(e) {
    this.setData({ ["form." + e.detail.key]: e.detail.value });
  },
  onUploadState(e) {
    this.setData({ uploading: e.detail.uploading });
  },
  toggleProfile() {
    if (!this.data.busy && !this.data.uploading && !this.data.bindingBusy) this.setData({ profileOpen: !this.data.profileOpen });
  },
  clearDate() {
    this.setData({ "form.validUntil": "" });
  },
  save() {
    if (this.data.uploading || this.data.bindingBusy || !this.data.canEdit || !this.data.member?.id) return;
    return u.run(this, async () => {
      if (this.data.form.status === "DISABLED" && this.data.member.status !== "DISABLED" &&
          !await u.confirm("停用会员", "停用后，该会员无法继续预约。")) return;
      const reason = await u.reason("保存会员资料", "");
      if (!reason) return;
      const body = { ...this.data.form, reason };
      if (!body.phone) delete body.phone;
      if (body.avatarUrl === this.loadedAvatarUrl) delete body.avatarUrl;
      await u.api.put("/admin/members/" + this.data.id, body);
      let savedAvatar;
      try {
        savedAvatar = await this.read();
      } catch (_) {
        throw new Error("会员资料已保存，读取最新资料失败，请刷新核对。");
      }
      if (body.avatarUrl !== undefined && avatarIdentity(savedAvatar) !== avatarIdentity(body.avatarUrl)) {
        throw new Error("已提交会员资料，重新读取时未能确认头像已更新。");
      }
      u.success();
    });
  },
  canBind() {
    const { studioLedger, wechatBound, memberActive, member, bindingStatusLoading } = this.data;
    return studioLedger && !bindingStatusLoading && wechatBound === false && memberActive && !!member?.id;
  },
  ledgerMemberPath() { return '/api/admin/studio-ledger/members/' + this.data.member.id; },
  invitePath() { return this.ledgerMemberPath() + '/invites'; },
  setBindingStatus(bound) {
    this.setData({
      wechatBound: bound,
      bindingStatusFailed: bound === null,
      bindingOpen: bound === false && this.data.memberActive && this.data.bindingOpen
    });
  },
  async refreshBindingStatus() {
    if (!this.data.studioLedger || !this.data.member?.id || this.data.bindingStatusLoading) return;
    this.setData({ bindingStatusLoading: true, bindingStatusFailed: false });
    try {
      const { member } = await u.api.get(this.ledgerMemberPath(), undefined, { quiet: true });
      const binding = member?.binding;
      this.setBindingStatus(binding === 'BOUND' ? true : binding === 'UNBOUND' ? false : null);
    } catch (_) {
      this.setBindingStatus(null);
    } finally {
      this.setData({ bindingStatusLoading: false });
    }
  },
  toggleBinding() {
    if (!this.canBind() || this.data.busy || this.data.uploading || this.data.bindingBusy) return;
    this.setData({ bindingOpen: !this.data.bindingOpen });
    if (this.data.bindingOpen) return this.refreshInvites();
  },
  async runBinding(work) {
    if (!this.canBind() || !this.data.bindingOpen || this.data.bindingBusy || this.data.busy || this.data.uploading) return;
    this.setData({ bindingBusy: true, bindingError: "" });
    try {
      await work();
    } catch (error) {
      this.setData({ bindingError: error?.message || "操作失败，请重试" });
    } finally {
      this.setData({ bindingBusy: false });
    }
  },
  refreshInvites() {
    return this.runBinding(async () => {
      this.setData({ bindingLoaded: false });
      const invites = await u.api.get(this.invitePath(), undefined, { quiet: true });
      if (invites.some(invite => invite.state === 'CLAIMED')) {
        this.setData({ wechatBound: true, bindingOpen: false, bindingInvite: null, inviteCode: '', bindingForm: {} });
        return;
      }
      const active = invites.find(invite => ['ISSUED', 'REQUESTED'].includes(invite.state));
      const same = active && String(active.id) === String(this.data.bindingInvite?.id);
      this.setData({
        bindingInvite: active ? { ...active, expiresText: displayTime(active.expires_at) } : null,
        bindingLoaded: true,
        inviteCode: same && active.state === 'ISSUED' ? this.data.inviteCode : '',
        bindingForm: same ? this.data.bindingForm : {}
      });
    });
  },
  issueInvite() {
    if (!this.data.bindingLoaded || this.data.bindingInvite?.state === 'REQUESTED') return;
    return this.runBinding(async () => {
      if (this.data.bindingInvite && !await u.confirm('重新生成邀请码', '旧邀请码会失效，新邀请码有效 24 小时。')) return;
      const invite = await u.api.post(this.invitePath(), {});
      this.setData({ inviteCode: invite.code, bindingInvite: { id: invite.id, state: 'ISSUED', expiresText: displayTime(invite.expiresAt) } });
    });
  },
  copyInvite() { u.copyText(this.data.inviteCode); },
  changeBinding(e) {
    this.setData({ ['bindingForm.' + e.detail.key]: e.detail.value });
  },
  revokeInvite() {
    const id = this.data.bindingInvite?.id;
    if (!id) return;
    return this.runBinding(async () => {
      if (!await u.confirm('撤销绑定邀请', '撤销后，此邀请码和对应申请都会失效。')) return;
      await this.sendBinding(this.invitePath() + '/' + id + '/revoke', {});
      this.setData({ bindingInvite: null, inviteCode: '', bindingForm: {} });
    });
  },
  approveInvite() {
    const { bindingInvite, bindingForm } = this.data;
    if (bindingInvite?.state !== 'REQUESTED') return;
    return this.runBinding(async () => {
      const proof = (bindingForm.proof || '').trim();
      const reason = (bindingForm.reason || '').trim();
      if (!/^[0-9A-F]{8}$/i.test(proof) || !reason) throw new Error('请填写会员出示的 8 位核对码和核对依据。');
      await this.sendBinding(this.invitePath() + '/' + bindingInvite.id + '/approve', { proof, reason });
      this.setData({ wechatBound: true, bindingOpen: false, bindingInvite: null, inviteCode: '', bindingForm: {} });
      wx.showToast({ title: '微信已绑定', icon: 'success' });
    });
  },
  ledger() {
    if (this.data.studioLedger && this.data.member?.id) {
      wx.navigateTo({ url: "/admin/studio-ledger/studio-ledger?id=" + this.data.member.id });
    }
  },
  bookings() {
    wx.navigateTo({ url: "/admin/bookings/bookings?userId=" + this.data.id });
  },
  book() {
    if (this.data.canBook) wx.navigateTo({ url: "/admin/booking-edit/booking-edit?userId=" + this.data.id });
  }
});
