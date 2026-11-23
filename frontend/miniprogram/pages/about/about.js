// pages/about/about.js —— 关于优仪
const api = require('../../services/api');
Page({
  data: { studio: null },
  onLoad() { api.studio().then(s => this.setData({ studio: s })); },
  call() { if(!this.data.studio?.phone){wx.showToast({title:'联系电话待配置',icon:'none'});return;} wx.makePhoneCall({ phoneNumber: this.data.studio.phone }); },
  navigate() {
    if(!this.data.studio?.location){wx.showToast({title:'门店地址待配置',icon:'none'});return;}
    const [lat, lng] = (this.data.studio.location || '').split(',');
    wx.openLocation({ latitude: Number(lat), longitude: Number(lng), name: this.data.studio.name, address: this.data.studio.address });
  },
  contact() {
    wx.showToast({title:'在线客服尚未配置，可使用意见反馈',icon:'none'});
  }
});
