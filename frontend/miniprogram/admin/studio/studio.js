// admin/studio/studio.js —— 门店信息管理
const api = require('../api');

Page({
  data: { info: {}, submitting: false, introLen: 0, startTime: '', endTime: '', loading: true, error: '' },
  onLoad() { this.load(); },
  onPullDownRefresh() { this.load(); },
  load() {
    this.setData({loading:true,error:''});
    return api.get('/api/coach-end/home/studio').then(info => {
      info = info || {};
      const m = (info.hours || '').match(/^(\d{2}:\d{2})-(\d{2}:\d{2})$/);
      this.setData({
        info,
        startTime: m ? m[1] : '',
        endTime: m ? m[2] : '',
        introLen: (info.intro || '').length
      });
    }).catch(err => this.setData({error:err.message || '加载失败，请重试'})).finally(()=>{this.setData({loading:false});wx.stopPullDownRefresh();});
  },
  onInput(e) {
    const k = e.currentTarget.dataset.k;
    const v = e.detail.value;
    const patch = { ['info.' + k]: v };
    if (k === 'intro') patch.introLen = v.length;
    this.setData(patch);
  },
  onStart(e) { this.setData({ startTime: e.detail.value }); },
  onEnd(e) { this.setData({ endTime: e.detail.value }); },
  submit() {
    if (this.data.submitting || this.data.loading || this.data.error) return;
    const info = this.data.info;
    if (!(info.name || '').trim()) { wx.showToast({ title: '请输入门店名称', icon: 'none' }); return; }
    if (!(info.address || '').trim()) { wx.showToast({ title: '请输入门店地址', icon: 'none' }); return; }
    if (!(info.phone || '').trim()) { wx.showToast({ title: '请输入联系电话', icon: 'none' }); return; }
    const phone = (info.phone || '').trim();
    if (!/^(1[3-9]\d{9}|0\d{2,3}-?\d{7,8})$/.test(phone)) { wx.showToast({ title: '联系电话格式不正确', icon: 'none' }); return; }
    if (this.data.startTime && this.data.endTime) {
      if (this.data.endTime <= this.data.startTime) {
        wx.showModal({ title: '提示', content: '营业时间结束时间不能早于开始时间', showCancel: false });
        return;
      }
      info.hours = this.data.startTime + '-' + this.data.endTime;
    }
    this.setData({ submitting: true });
    api.put('/api/coach-end/home/studio', info).then(() => {
      wx.showToast({ title: '已保存', icon: 'success' });
      this.setData({ submitting: false });
    }).catch(() => this.setData({ submitting: false }));
  }
});
