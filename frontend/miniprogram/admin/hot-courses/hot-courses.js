// admin/hot-courses/hot-courses.js —— 首页热门课程管理(勾选 + 上移/下移排序)
const api = require('../api');
const u = require('../common');
const MAX = 5;
const TYPE = { GROUP: '小班课', PRIVATE: '私教课', EXPERIENCE: '历史体验课' };
const DIFF = { BEGINNER: '入门', JUNIOR: '初级', INTERMEDIATE: '中级', ADVANCED: '进阶' };

Page({
  data: { allCourses: [], hotCourseIds: [], selectedList: [], submitting: false, loading: true, loadError: false, dirty: false, canEdit: false, canOpenCatalog: false },
  onLoad() { this.initialShow = true; this.load(); },
  onShow() { if (this.initialShow) { this.initialShow = false; return; } return this.load(); },
  load() {
    const loadId = this.loadId = (this.loadId || 0) + 1;
    this.setData({ loading: true, loadError: false, canEdit: u.can('coach:home:hot-course'), canOpenCatalog: u.can('course:view') });
    return Promise.all([api.get('/api/courses'), api.get('/api/coach-end/home/hot-courses')])
      .then(([all, hot]) => {
        if (loadId !== this.loadId) return;
        (all || []).forEach(c => { c.typeText = TYPE[c.type] || c.type; c.diffText = DIFF[c.difficulty] || c.difficulty; });
        this.setData({
          allCourses: all || [],
          hotCourseIds: this.data.dirty ? this.data.hotCourseIds : (hot || []).map(h => Number(h.courseId)),
          loading: false
        });
        this.refreshSelected();
      })
      .catch(() => { if (loadId === this.loadId) this.setData({ loading: false, loadError: true }); });
  },
  refreshSelected() {
    const selected = new Set(this.data.hotCourseIds.map(Number));
    this.setData({ allCourses: this.data.allCourses.map(item => ({...item, selected: selected.has(Number(item.id))})) });
    const map = {};
    this.data.allCourses.forEach(c => { map[c.id] = c; });
    this.setData({ selectedList: this.data.hotCourseIds.map(id => map[id]).filter(Boolean) });
  },
  toggle(e) {
    if (!this.data.canEdit || this.data.loading || this.data.submitting) return;
    const id = Number(e.currentTarget.dataset.id);
    const ids = this.data.hotCourseIds.slice();
    const i = ids.indexOf(id);
    if (i >= 0) {
      ids.splice(i, 1);
    } else {
      if (ids.length >= MAX) { wx.showToast({ title: '最多选择' + MAX + '门课程', icon: 'none' }); return; }
      ids.push(id);
    }
    this.setData({ hotCourseIds: ids, dirty: true });
    this.refreshSelected();
  },
  moveUp(e) { this.move(Number(e.currentTarget.dataset.i), -1); },
  moveDown(e) { this.move(Number(e.currentTarget.dataset.i), 1); },
  move(from, delta) {
    if (!this.data.canEdit || this.data.loading || this.data.submitting) return;
    const to = from + delta;
    const ids = this.data.hotCourseIds;
    if (to < 0 || to >= ids.length) return;
    const arr = ids.slice();
    const tmp = arr[from]; arr[from] = arr[to]; arr[to] = tmp;
    this.setData({ hotCourseIds: arr, dirty: true });
    this.refreshSelected();
  },
  removeSelected(e) {
    if (!this.data.canEdit || this.data.loading || this.data.submitting) return;
    this.setData({ dirty: true });
    const id = Number(e.currentTarget.dataset.id);
    this.setData({ hotCourseIds: this.data.hotCourseIds.filter(x => x !== id) });
    this.refreshSelected();
  },
  openCatalog() {
    if (this.data.canOpenCatalog) wx.navigateTo({ url: '/admin/courses/courses' });
  },
  submit() {
    if (!this.data.canEdit || this.data.submitting || this.data.loading || this.data.loadError) return;
    if (this.data.hotCourseIds.length === 0) {
      const self = this;
      wx.showModal({ title: '清空热门课程', content: '当前未选择任何课程，保存将清空全部热门课程，确定吗？', success(r) { if (r.confirm) self.doSave(); } });
      return;
    }
    this.doSave();
  },
  doSave() {
    if (!this.data.canEdit || this.data.submitting) return;
    this.setData({ submitting: true });
    const items = this.data.hotCourseIds.map((id, idx) => ({ courseId: id, sort: idx + 1, enabled: 1 }));
    api.put('/api/coach-end/home/hot-courses', items).then(() => {
      wx.showToast({ title: '已保存', icon: 'success' });
      this.setData({ submitting: false, dirty: false });
      return this.load();
    }).catch(() => this.setData({ submitting: false }));
  }
});
