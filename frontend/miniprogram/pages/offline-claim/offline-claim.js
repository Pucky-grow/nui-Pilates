const http = require('../../utils/request');

Page({
  data: { inviteCode: '', proof: '', busy: false, error: '', fromProfile: false },
  onLoad(q) { this.setData({ fromProfile: q.from === 'profile' }); },
  onInput(e) { this.setData({ inviteCode: e.detail.value.trim() }); },
  async submit() {
    if (this.data.busy) return;
    if (!/^[A-Za-z0-9_-]{32}$/.test(this.data.inviteCode)) {
      this.setData({ error: '请粘贴门店提供的邀请码' });
      return;
    }
    this.setData({ busy: true, error: '' });
    try {
      const context = http.captureAuthContext();
      const code = await http.wechatCode(context);
      if (!http.isAuthContextCurrent(context)) return;
      const result = await http.post('/api/auth/offline-member-claims', { inviteCode: this.data.inviteCode, code }, { skipAuth: true, silent: true });
      this.setData({ proof: result.proof, inviteCode: '' });
    } catch (error) {
      this.setData({ error: error?.message || '申请失败，请重试' });
    } finally {
      this.setData({ busy: false });
    }
  },
  async finish() {
    if (!this.data.fromProfile) {
      wx.reLaunch({ url: '/pages/login/login' });
      return;
    }
    if (this.data.busy) return;
    this.setData({ busy: true, error: '' });
    try {
      await http.login({ force: true });
      wx.switchTab({ url: '/pages/profile/profile' });
    } catch (error) {
      this.setData({ error: error?.message || '刷新账号失败，请重试' });
    } finally {
      this.setData({ busy: false });
    }
  }
});
