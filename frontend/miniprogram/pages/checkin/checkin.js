const api = require('../../services/api');
const http = require('../../utils/request');

Page({
  data: { checked: false, loading: true, scanning: false, submitting: false, canCheckin: false, error: '' },
  onLoad(options) {
    this.bookingId = options.bookingId;
    this.setData({ bookingId: this.bookingId });
    return this.load();
  },
  load() {
    this.setData({ loading: true, error: '' });
    if (!this.bookingId) {
      this.setData({ loading: false, error: '请从预约详情进入签到' });
      return;
    }
    return http.login().then(() => api.bookingDetail(this.bookingId)).then(booking => {
      this.setData({
        loading: false,
        checked: booking.status === 'SIGNED_IN' || booking.status === 'COMPLETED',
        canCheckin: booking.canCheckin
      });
    }).catch(error => this.setData({ loading: false, error: error?.message || '无法读取预约，请重试' }));
  },
  scan() {
    if (!this.data.canCheckin || this.data.scanning || this.data.submitting) return;
    this.setData({ scanning: true, error: '' });
    wx.scanCode({
      onlyFromCamera: true,
      scanType: ['qrCode'],
      success: res => {
        const code = res.result || '';
        if (/^YOYI-CHECKIN:[A-Za-z0-9_-]{24}$/.test(code)) this.doCheckin(code);
        else this.setData({ error: '签到码无效，请扫描教练出示的新码' });
      },
      fail: err => {
        if (!err?.errMsg?.includes('cancel')) {
          this.setData({ error: '无法打开摄像头，请联系教练或前台代签到' });
        }
      },
      complete: () => this.setData({ scanning: false })
    });
  },
  doCheckin(code) {
    if (this.data.submitting || this.data.checked) return;
    this.setData({ submitting: true });
    return api.checkin(this.bookingId, code).then(() => {
      this.setData({ checked: true });
      wx.showToast({ title: '签到成功', icon: 'success' });
    }).catch(error => this.setData({ error: error?.message || '签到失败，请重新扫码或联系工作人员' }))
      .finally(() => this.setData({ submitting: false }));
  }
});
