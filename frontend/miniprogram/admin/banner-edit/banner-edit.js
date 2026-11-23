// admin/banner-edit/banner-edit.js —— 新增/编辑 Banner
const api = require('../api');
const app = getApp();

const LINK_TYPES = [
  { v: 'NONE', label: '无跳转' },
  { v: 'COURSE', label: '课程' },
  { v: 'PACKAGE', label: '课包' },
  { v: 'ACTIVITY', label: '活动' },
  { v: 'ARTICLE', label: '文章' },
  { v: 'URL', label: '链接' }
];

Page({
  data: {
    id: null, imageUrl: '', title: '', subtitle: '', linkType: 'NONE', linkValue: '', sort: '0',
    status: 'ACTIVE', linkTypes: LINK_TYPES, linkIndex: 0, submitting: false, loading: false, error: ''
  },
  onLoad(options) { this.id=options.id||null; if(this.id)this.load(); },
  onPullDownRefresh(){ if(this.id)this.load();else wx.stopPullDownRefresh(); },
  load() {
      this.setData({loading:true,error:''});
      return api.get('/api/coach-end/home/banners').then(list => {
        const b = list.find(x => x.id == this.id);
        if (!b) throw {message:'Banner 不存在，请返回列表刷新'};
        if (b) {
          const linkIndex = Math.max(0, LINK_TYPES.findIndex(t => t.v === b.linkType));
          this.setData({
            imageUrl: b.imageUrl || '', title: b.title || '', subtitle: b.subtitle || '',
            linkType: b.linkType || 'NONE', linkValue: b.linkValue || '',
            sort: String(b.sort == null ? 0 : b.sort), status: b.status || 'ACTIVE', linkIndex
          });
        }
      }).catch(err=>this.setData({error:err.message||'加载失败，请重试'})).finally(()=>{this.setData({loading:false});wx.stopPullDownRefresh();});
  },
  onImage() {
    const self = this;
    wx.chooseMedia({
      count: 1, mediaType: ['image'],
      success(res) {
        const temp = res.tempFiles[0].tempFilePath;
        wx.showLoading({ title: '上传中', mask: true });
        api.upload('/api/upload', temp, { purpose: 'PUBLIC_ASSET' }).then(url => {
          wx.hideLoading();
          self.setData({ imageUrl: /^https?:\/\//.test(url) ? url : app.globalData.baseUrl + url });
        }).catch(() => wx.hideLoading());
      }
    });
  },
  onLinkType(e) {
    const i = Number(e.detail.value);
    this.setData({ linkIndex: i, linkType: LINK_TYPES[i].v, linkValue: '' });
    wx.showToast({ title: '已切换跳转类型，请重新填写跳转值', icon: 'none' });
  },
  onInput(e) { const k = e.currentTarget.dataset.k; this.setData({ [k]: e.detail.value }); },
  onStatus(e) { this.setData({ status: e.detail.value ? 'ACTIVE' : 'DISABLED' }); },
  submit() {
    if (this.data.submitting || this.data.loading || this.data.error) return;
    if (!this.data.imageUrl) { wx.showToast({ title: '请上传Banner图片', icon: 'none' }); return; }
    const linkType = this.data.linkType;
    const linkValue = (this.data.linkValue || '').trim();
    if (linkType !== 'NONE') {
      if (!linkValue) { wx.showToast({ title: '请填写跳转值', icon: 'none' }); return; }
      if (linkType === 'URL') {
        if (!/^https?:\/\/.+/.test(linkValue)) { wx.showToast({ title: '链接需以 http(s):// 开头', icon: 'none' }); return; }
      } else if (!/^\d+$/.test(linkValue)) {
        wx.showToast({ title: '跳转值必须为数字ID', icon: 'none' });
        return;
      }
    }
    this.setData({ submitting: true });
    const body = {
      imageUrl: this.data.imageUrl, title: this.data.title, subtitle: this.data.subtitle,
      linkType: this.data.linkType, linkValue: linkValue,
      sort: Number(this.data.sort) || 0, status: this.data.status
    };
    const p = this.id ? api.put('/api/coach-end/home/banners/' + this.id, body) : api.post('/api/coach-end/home/banners', body);
    p.then(() => {
      wx.showToast({ title: '已保存', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 800);
    }).catch(() => this.setData({ submitting: false }));
  }
});
