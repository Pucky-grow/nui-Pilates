// admin/team-coaches/team-coaches.js —— 首页教练团队管理(勾选 + 上移/下移排序)
const api = require('../api');
const u = require('../common');
const MAX = 5;

Page({
  data: { allCoaches: [], teamCoachIds: [], selectedList: [], submitting: false, loading: true, loadError: false, dirty: false, canEdit: false, canOpenCatalog: false },
  onLoad() { this.initialShow = true; this.load(); },
  onShow() { if (this.initialShow) { this.initialShow = false; return; } return this.load(); },
  load() {
    const loadId = this.loadId = (this.loadId || 0) + 1;
    this.setData({ loading: true, loadError: false, canEdit: u.can('coach:home:coach-team'), canOpenCatalog: u.can('coach:manage') });
    return Promise.all([api.get('/api/coaches'), api.get('/api/coach-end/home/team-coaches')])
      .then(([all, team]) => {
        if (loadId !== this.loadId) return;
        this.setData({
          allCoaches: all || [],
          teamCoachIds: this.data.dirty ? this.data.teamCoachIds : (team || []).map(t => Number(t.coachId)),
          loading: false
        });
        this.refreshSelected();
      })
      .catch(() => { if (loadId === this.loadId) this.setData({ loading: false, loadError: true }); });
  },
  refreshSelected() {
    const selected = new Set(this.data.teamCoachIds.map(Number));
    this.setData({ allCoaches: this.data.allCoaches.map(item => ({...item, selected: selected.has(Number(item.id))})) });
    const map = {};
    this.data.allCoaches.forEach(c => { map[c.id] = c; });
    this.setData({ selectedList: this.data.teamCoachIds.map(id => map[id]).filter(Boolean) });
  },
  toggle(e) {
    if (!this.data.canEdit || this.data.loading || this.data.submitting) return;
    const id = Number(e.currentTarget.dataset.id);
    const ids = this.data.teamCoachIds.slice();
    const i = ids.indexOf(id);
    if (i >= 0) {
      ids.splice(i, 1);
    } else {
      if (ids.length >= MAX) { wx.showToast({ title: '最多选择' + MAX + '位教练', icon: 'none' }); return; }
      ids.push(id);
    }
    this.setData({ teamCoachIds: ids, dirty: true });
    this.refreshSelected();
  },
  moveUp(e) { this.move(Number(e.currentTarget.dataset.i), -1); },
  moveDown(e) { this.move(Number(e.currentTarget.dataset.i), 1); },
  move(from, delta) {
    if (!this.data.canEdit || this.data.loading || this.data.submitting) return;
    const to = from + delta;
    const ids = this.data.teamCoachIds;
    if (to < 0 || to >= ids.length) return;
    const arr = ids.slice();
    const tmp = arr[from]; arr[from] = arr[to]; arr[to] = tmp;
    this.setData({ teamCoachIds: arr, dirty: true });
    this.refreshSelected();
  },
  removeSelected(e) {
    if (!this.data.canEdit || this.data.loading || this.data.submitting) return;
    this.setData({ dirty: true });
    const id = Number(e.currentTarget.dataset.id);
    this.setData({ teamCoachIds: this.data.teamCoachIds.filter(x => x !== id) });
    this.refreshSelected();
  },
  openCatalog() {
    if (this.data.canOpenCatalog) wx.navigateTo({ url: '/admin/coaches/coaches' });
  },
  submit() {
    if (!this.data.canEdit || this.data.submitting || this.data.loading || this.data.loadError) return;
    if (this.data.teamCoachIds.length === 0) {
      const self = this;
      wx.showModal({ title: '清空教练团队', content: '当前未选择任何教练，保存将清空全部教练团队，确定吗？', success(r) { if (r.confirm) self.doSave(); } });
      return;
    }
    this.doSave();
  },
  doSave() {
    if (!this.data.canEdit || this.data.submitting) return;
    this.setData({ submitting: true });
    const items = this.data.teamCoachIds.map((id, idx) => ({ coachId: id, intro: '', sort: idx + 1, enabled: 1 }));
    api.put('/api/coach-end/home/team-coaches', items).then(() => {
      wx.showToast({ title: '已保存', icon: 'success' });
      this.setData({ submitting: false, dirty: false });
      return this.load();
    }).catch(() => this.setData({ submitting: false }));
  }
});
