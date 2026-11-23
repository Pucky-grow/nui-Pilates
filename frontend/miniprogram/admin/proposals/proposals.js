// admin/proposals/proposals.js —— 课程方案审核(通过/驳回)
const api = require('../api');
const STATUS = { DRAFT: '草稿', PENDING_REVIEW: '待审核', APPROVED: '已通过', REJECTED: '已驳回' };

Page({
  data: { list: [], statusText: STATUS, status: 'PENDING_REVIEW', submitting: false, error: '' },
  onShow() { this.load(); },
  load() {
    this.setData({error:''});
    const q = this.data.status === 'ALL' ? '' : '?status=' + this.data.status;
    return api.get('/admin/course-proposals' + q).then(list => this.setData({ list })).catch(err => this.setData({error:err.message||'加载失败，请重试'}));
  },
  onFilter(e) { this.setData({ status: e.currentTarget.dataset.v }); this.load(); },
  approve(e) {
    if (this.data.submitting) return;
    const id = e.currentTarget.dataset.id;
    const self = this;
    wx.showModal({
      title: '审核通过', content: '确定通过该课程方案吗？',
      success(r) {
        if (!r.confirm) return;
        self.setData({ submitting: true });
        api.post('/admin/course-proposals/' + id + '/approve').then(() => {
          wx.showToast({ title: '已通过', icon: 'success' });
          self.load();
          self.setData({ submitting: false });
        }).catch(() => self.setData({ submitting: false }));
      }
    });
  },
  reject(e) {
    if (this.data.submitting) return;
    const id = e.currentTarget.dataset.id;
    const self = this;
    wx.showModal({
      title: '驳回课程方案', editable: true, placeholderText: '请填写驳回原因',
      success(r) {
        if (!r.confirm) return;
        const reason = (r.content || '').trim();
        if (!reason) { wx.showToast({ title: '请填写原因', icon: 'none' }); return; }
        self.setData({ submitting: true });
        api.post('/admin/course-proposals/' + id + '/reject?reason=' + encodeURIComponent(reason)).then(() => {
          wx.showToast({ title: '已驳回', icon: 'success' });
          self.load();
          self.setData({ submitting: false });
        }).catch(() => self.setData({ submitting: false }));
      }
    });
  }
});
