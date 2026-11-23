// pages/assessment/assessment.js —— 身体评估
const api = require('../../services/api');
const http = require('../../utils/request');
Page({
  data: { assessments: [], trend: [] },
  onShow() { http.login().then(() => this.load()); },
  load() {
    api.myAssessments().then(list => this.setData({ assessments: list }));
    api.measurementTrend().then(list => this.setData({ trend: list }));
  }
});
