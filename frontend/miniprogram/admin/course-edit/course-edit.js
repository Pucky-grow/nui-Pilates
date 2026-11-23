const u = require('../common');
const o = u.option;

function fieldsFor(courseType) {
  const types = [o('GROUP', '小班课'), o('PRIVATE', '私教课')];
  if (courseType === 'EXPERIENCE') types.push(o('EXPERIENCE', '体验课（历史）'));
  return [
    { key: 'name', label: '课程名称', required: true, maxlength: 64 },
    { key: 'type', label: '课程类型', type: 'select', required: true, options: types },
    { key: 'durationMinutes', label: '时长（分钟）', type: 'number', required: true },
    { key: 'difficulty', label: '难度', type: 'select', options: [o('BEGINNER', '入门'), o('JUNIOR', '初级'), o('INTERMEDIATE', '中级'), o('ADVANCED', '进阶')] },
    { key: 'cover', label: '封面', type: 'image' },
    { key: 'description', label: '课程介绍', type: 'textarea' },
    { key: 'targetUsers', label: '适合人群', type: 'textarea', maxlength: 512 },
    { key: 'trainingFocus', label: '训练重点', type: 'textarea', maxlength: 512 },
    { key: 'notices', label: '注意事项', type: 'textarea', maxlength: 512 },
    { key: 'creditsCost', label: '历史课包模式课时参数', type: 'number', hint: '当前免费预约不扣课时、不要求购课' },
    { key: 'price', label: '课程展示价格', type: 'decimal', hint: '编辑价格不会开启销售或支付' },
    { key: 'sort', label: '排序', type: 'number' },
    { key: 'status', label: '上架状态', type: 'select', options: [o('ON_SHELF', '上架'), o('OFF_SHELF', '下架')] }
  ];
}

Page({
  data: {
    id: '',
    form: { name: '', durationMinutes: 60, type: 'GROUP', difficulty: 'JUNIOR', creditsCost: 1, price: 0, sort: 0, status: 'ON_SHELF' },
    fields: [], busy: false, uploading: false, error: '', canEdit: false, canDelete: false
  },
  onLoad(q) {
    this.setData({ id: q.id || '', canEdit: u.can(q.id ? 'course:update' : 'course:create'), canDelete: u.can('course:delete') });
    if (u.guard(q.id ? 'course:view' : 'course:create')) this.load();
  },
  onPullDownRefresh() { this.load(); },
  load() { return u.run(this, () => this.read()); },
  async read() {
    const form = this.data.id ? await u.api.get('/admin/courses/' + this.data.id) : this.data.form;
    this.setData({ form, fields: fieldsFor(form.type) });
  },
  change(e) { this.setData({ ['form.' + e.detail.key]: e.detail.value }); },
  onUploadState(e) { this.setData({ uploading: e.detail.uploading }); },
  removeCourse() {
    if (!this.data.canDelete || !this.data.id || !this.data.fields.length || this.data.uploading) return;
    return u.run(this, async () => {
      if (!await u.confirm('删除目录课程', '将「' + this.data.form.name + '」从课程目录移除。系统会检查关联排课和历史记录。')) return;
      await u.api.del('/admin/courses/' + this.data.id);
      wx.showToast({ title: '课程已删除', icon: 'success' });
      wx.navigateBack({ fail: () => wx.redirectTo({ url: '/admin/courses/courses' }) });
    });
  },
  save() {
    if (this.data.uploading || !this.data.fields.length) return;
    return u.run(this, async () => {
      const form = this.data.form;
      const body = Object.fromEntries(this.data.fields
        .filter(field => form[field.key] !== undefined)
        .map(field => [field.key, form[field.key]]));
      for (const key of ['durationMinutes', 'creditsCost', 'price', 'sort']) body[key] = Number(body[key] || 0);
      const types = this.data.fields.find(field => field.key === 'type').options;
      if (!body.name || !types.some(type => type.value === body.type) || body.durationMinutes < 15 || body.creditsCost < 1) {
        throw { message: '请检查名称、课程类型、时长和课时参数' };
      }
      if (!await u.confirm('保存课程', '修改后课程展示会更新，已有预约历史保留。')) return;
      if (this.data.id) {
        await u.api.put('/admin/courses/' + this.data.id, body);
      } else {
        this.setData({ id: await u.api.post('/admin/courses', body) });
      }
      try {
        await this.read();
        u.success();
      } catch (_) {
        this.setData({ error: '课程已保存，读取最新资料失败，请点重试。' });
      }
    });
  }
});
