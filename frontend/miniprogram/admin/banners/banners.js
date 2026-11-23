// admin/banners/banners.js —— 首页 Banner 管理(含启用/禁用)
const api = require('../api');
const STATUS_TEXT = { ACTIVE: '已启用', DISABLED: '已禁用' };

Page({
  data: { banners: [], error: '' },
  onShow() { this.load(); },
  load() {
    this.setData({error:''});
    return api.get('/api/coach-end/home/banners').then(banners => {
      (banners || []).forEach(b => { b.statusText = STATUS_TEXT[b.status] || b.status; });
      this.setData({ banners });
    }).catch(err => this.setData({error:err.message||'加载失败，请重试'}));
  },
  add() { wx.navigateTo({ url: '/admin/banner-edit/banner-edit' }); },
  edit(e) { wx.navigateTo({ url: '/admin/banner-edit/banner-edit?id=' + e.currentTarget.dataset.id }); },
  toggle(e) {
    const id = e.currentTarget.dataset.id;
    const status = e.currentTarget.dataset.status;
    const newStatus = status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
    api.put('/api/coach-end/home/banners/' + id + '/status', { status: newStatus }).then(() => {
      wx.showToast({ title: newStatus === 'ACTIVE' ? '已启用' : '已禁用', icon: 'success' });
      this.load();
    }).catch(() => {});
  },
  del(e) {
    const id = e.currentTarget.dataset.id;
    const self = this;
    wx.showModal({
      title: '删除 Banner', content: '确定删除该 Banner 吗？删除后不可恢复。',
      success(r) {
        if (!r.confirm) return;
        api.del('/api/coach-end/home/banners/' + id).then(() => {
          wx.showToast({ title: '已删除', icon: 'success' });
          self.load();
        }).catch(() => {});
      }
    });
  }
});
