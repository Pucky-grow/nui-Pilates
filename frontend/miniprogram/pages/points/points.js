// pages/points/points.js —— 积分
const api = require('../../services/api');
const http = require('../../utils/request');
Page({
  data: { balance: 0, records: [] },
  onShow() {
    http.login().then(() => {
      api.myPoints().then(d => this.setData({ balance: d.balance }));
      api.pointsRecords().then(d => this.setData({ records: d.list }));
    });
  }
});
