// pages/invite/invite.js —— 邀请好友
const api = require('../../services/api');
const http = require('../../utils/request');

Page({
  data: { code: '', bound: false },
  onShow() { http.login().then(() => api.myInviteCode().then(d => this.setData({ code: d.inviteCode }))); },
  copy() {
    wx.setClipboardData({ data: this.data.code, success: () => wx.showToast({ title: '邀请码已复制', icon: 'success' }) });
  },
  onBindCode(e) { this.setData({ code: e.detail.value }); },
  bind() {
    if (!this.data.code) { wx.showToast({ title: '请输入邀请码', icon: 'none' }); return; }
    api.bindInvite(this.data.code).then(() => { wx.showToast({ title: '绑定成功', icon: 'success' }); this.setData({ bound: true }); });
  }
});
