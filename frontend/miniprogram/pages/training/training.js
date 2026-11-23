// pages/training/training.js —— 训练记录 + 数据统计
const api = require('../../services/api');
const http = require('../../utils/request');
Page({
  data: { records: [], stats: null },
  onShow() { http.login().then(() => this.load()); },
  load() {
    api.myTrainingRecords().then(d => this.setData({ records: d.list }));
    api.trainingStats().then(s => this.setData({ stats: s }));
  }
});
