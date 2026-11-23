// coach-end/training/training.js —— 填写训练记录(含模板)
const api = require('../api');
Page({
  data: { bookingId: null, userId: null, goal: '', content: '', intensity: 'MEDIUM', feedback: '', suggestion: '',
    adaptations: '', progression: '', templates: [], saving: false },
  onLoad(options) {
    this.setData({ bookingId: options.bookingId, userId: options.userId });
    api.get('/api/coach-end/training-templates').then(list => this.setData({ templates: list }));
  },
  onGoal(e) { this.setData({ goal: e.detail.value }); },
  onContent(e) { this.setData({ content: e.detail.value }); },
  onIntensity(e) { this.setData({ intensity: e.currentTarget.dataset.v }); },
  onFeedback(e) { this.setData({ feedback: e.detail.value }); },
  onSuggestion(e) { this.setData({ suggestion: e.detail.value }); },
  onAdaptations(e) { this.setData({ adaptations: e.detail.value }); },
  onProgression(e) { this.setData({ progression: e.detail.value }); },
  useTemplate(e) {
    const t = this.data.templates[e.currentTarget.dataset.i];
    if (t) this.setData({ content: t.content });
  },
  submit() {
    if (this.data.saving) return;
    this.setData({ saving: true });
    const append = (body, label, detail) => [body.trim(), detail.trim() ? label + detail.trim() : ''].filter(Boolean).join('\n');
    return api.post('/api/coach-end/training-records', {
      bookingId: this.data.bookingId ? Number(this.data.bookingId) : null,
      userId: this.data.userId ? Number(this.data.userId) : null,
      goal: this.data.goal, content: append(this.data.content, '动作调整：', this.data.adaptations), intensity: this.data.intensity,
      feedback: this.data.feedback, suggestion: append(this.data.suggestion, '进阶标准：', this.data.progression)
    }).then(() => {
      wx.showToast({ title: '已保存', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 800);
    }).catch(() => this.setData({ saving: false }));
  }
});
