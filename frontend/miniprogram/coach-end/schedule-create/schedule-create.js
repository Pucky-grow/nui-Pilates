// coach-end/schedule-create/schedule-create.js —— 教练创建排课
const api = require('../api');

Page({
  data: {
    courses: [], courseIndex: -1, classrooms: [], roomIndex: -1,
    courseDate: '', startTime: '09:00', endTime: '10:00', maxCapacity: '8', submitting: false
  },
  onLoad() {
    api.get('/api/coach-end/courses/my').then(list => {
      this.setData({ courses: list.filter(c => c.status === 'APPROVED') });
    });
    api.get('/api/classrooms').then(list => this.setData({ classrooms: list }));
  },
  onCourse(e) {
    const courseIndex = Number(e.detail.value);
    const privateClass = this.data.courses[courseIndex]?.courseType === 'PRIVATE';
    const maxCapacity = privateClass ? '1' : this.data.privateClass ? '8' : this.data.maxCapacity;
    this.setData({ courseIndex, privateClass, maxCapacity });
  },
  onRoom(e) { this.setData({ roomIndex: Number(e.detail.value) }); },
  onDate(e) { this.setData({ courseDate: e.detail.value }); },
  onStart(e) { this.setData({ startTime: e.detail.value }); },
  onEnd(e) { this.setData({ endTime: e.detail.value }); },
  onCap(e) { this.setData({ maxCapacity: e.detail.value }); },
  submit() {
    const { courses, courseIndex, classrooms, roomIndex, courseDate, startTime, endTime, maxCapacity } = this.data;
    if (courseIndex < 0) { wx.showToast({ title: '请选择课程', icon: 'none' }); return; }
    if (roomIndex < 0) { wx.showToast({ title: '请选择教室', icon: 'none' }); return; }
    if (!courseDate) { wx.showToast({ title: '请选择日期', icon: 'none' }); return; }
    if (!endTime || endTime <= startTime) { wx.showToast({ title: '结束时间须晚于开始时间', icon: 'none' }); return; }
    const cap = Number(maxCapacity);
    if (!Number.isInteger(cap) || cap < 1) { wx.showToast({ title: '人数上限须为正整数', icon: 'none' }); return; }
    if (this.data.submitting) return;
    this.setData({ submitting: true });
    const body = {
      courseId: courses[courseIndex].courseId,
      coachId: 0, // 后端按当前登录教练强制为本人
      classroomId: classrooms[roomIndex].id,
      courseDate, startTime, endTime, maxCapacity: cap
    };
    api.post('/api/coach-end/schedules', body).then(() => {
      wx.showToast({ title: '排课成功', icon: 'success' });
      setTimeout(() => wx.navigateBack(), 800);
    }).catch(() => this.setData({ submitting: false }));
  }
});
