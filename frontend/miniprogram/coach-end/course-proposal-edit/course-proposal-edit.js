// coach-end/course-proposal-edit/course-proposal-edit.js —— 课程方案编辑
const api = require('../api');

Page({
  data: { id: null, name: '', type: 'GROUP', difficulty: 'BEGINNER', duration: 60, capacity: 8, credits: 1, description: '', goal: '', audience: '', content: '', precautions: '', categories: [], categoryId: null, catIndex: -1 },
  onLoad(options) {
    api.get('/api/course-categories').then(cs => this.setData({ categories: cs }));
    if (options.id) {
      this.setData({ id: options.id });
      api.get('/api/coach-end/course-proposals/' + options.id).then(p => {
        this.setData({
          name: p.courseName, type: p.courseType, difficulty: p.difficulty, duration: p.duration,
          capacity: p.suggestedCapacity, credits: p.creditCost, description: p.description,
          goal: p.trainingGoal, audience: p.targetAudience, content: p.content, precautions: p.precautions,
          categoryId: p.categoryId
        });
      });
    }
  },
  onName(e) { this.setData({ name: e.detail.value }); },
  onType(e) { this.setData({ type: e.currentTarget.dataset.v }); },
  onDifficulty(e) { this.setData({ difficulty: e.currentTarget.dataset.v }); },
  onDuration(e) { this.setData({ duration: Number(e.detail.value) }); },
  onCapacity(e) { this.setData({ capacity: Number(e.detail.value) }); },
  onCredits(e) { this.setData({ credits: Number(e.detail.value) }); },
  onDescription(e) { this.setData({ description: e.detail.value }); },
  onGoal(e) { this.setData({ goal: e.detail.value }); },
  onAudience(e) { this.setData({ audience: e.detail.value }); },
  onContent(e) { this.setData({ content: e.detail.value }); },
  onPrecautions(e) { this.setData({ precautions: e.detail.value }); },
  onCategory(e) { const i = e.detail.value; this.setData({ catIndex: i, categoryId: this.data.categories[i].id }); },
  build() {
    const { id, categoryId, name, type, difficulty, duration, capacity, credits, description, goal, audience, content, precautions } = this.data;
    if (!name) { wx.showToast({ title: '请填写课程名称', icon: 'none' }); return; }
    return { id: id ? Number(id) : null, categoryId, courseName: name, courseType: type, difficulty, duration, suggestedCapacity: capacity, creditCost: credits, description, trainingGoal: goal, targetAudience: audience, content, precautions };
  },
  save() {
    const body = this.build();
    const p = this.data.id
      ? api.put('/api/coach-end/course-proposals/' + this.data.id, body)
      : api.post('/api/coach-end/course-proposals', body);
    p.then(() => { wx.showToast({ title: '已保存草稿', icon: 'success' }); setTimeout(() => wx.navigateBack(), 600); });
  },
  submit() {
    const body = this.build();
    const save = this.data.id
      ? api.put('/api/coach-end/course-proposals/' + this.data.id, body)
      : api.post('/api/coach-end/course-proposals', body);
    save.then(d => {
      const id = this.data.id || d;
      api.post('/api/coach-end/course-proposals/' + id + '/submit').then(() => {
        wx.showToast({ title: '已提交审核', icon: 'success' });
        setTimeout(() => wx.navigateBack(), 600);
      });
    });
  }
});
