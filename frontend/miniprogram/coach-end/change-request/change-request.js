const api = require('../api');
const { localDate } = require('../../utils/util');

const STATUS = { PENDING: '待审批', APPROVED: '已通过', REJECTED: '已拒绝' };
const TYPES = [
  { label: '换时间', value: 'TIME' },
  { label: '换教练', value: 'COACH' },
  { label: '换教室', value: 'CLASSROOM' }
];

Page({
  data: {
    requests: [], statusText: STATUS, types: TYPES, schedules: [], coaches: [], classrooms: [],
    scheduleId: null, type: 'TIME', newDate: '', newStart: '', newEnd: '', newCoachId: '', newClassroomId: '',
    reason: '', loading: true, submitting: false, error: ''
  },
  onShow() {
    this.setData({ loading: true, error: '' });
    return Promise.all([
      api.get('/api/coach-end/schedules', { fromDate: localDate(), toDate: localDate(30) }),
      api.get('/api/coaches'),
      api.get('/api/classrooms'),
      api.get('/api/coach-end/change-requests')
    ]).then(([schedules, coaches, classrooms, requests]) => {
      const now = new Date(Date.now() + 8 * 3600000).toISOString().slice(0, 19);
      const upcoming = schedules.filter(item => item.status === 'SCHEDULED'
        && item.courseDate + 'T' + item.startTime.slice(0, 5) + ':00' > now);
      this.setData({
        schedules: upcoming.map(item => ({ ...item, label: item.courseDate + ' ' + item.startTime.slice(0, 5) + ' · ' + item.courseName })),
        coaches, classrooms, requests, loading: false
      });
    }).catch(error => this.setData({ loading: false, error: error?.message || '调课信息加载失败' }));
  },
  onSchedule(e) {
    const schedule = this.data.schedules[e.detail.value];
    this.setData({ scheduleId: schedule.id, scheduleLabel: schedule.label, newDate: schedule.courseDate,
      newStart: schedule.startTime.slice(0, 5), newEnd: schedule.endTime.slice(0, 5),
      newCoachId: '', newCoachName: '', newClassroomId: '', newClassroomName: '' });
  },
  onType(e) { this.setData({ type: TYPES[e.detail.value].value }); },
  onNewDate(e) { this.setData({ newDate: e.detail.value }); },
  onNewStart(e) { this.setData({ newStart: e.detail.value }); },
  onNewEnd(e) { this.setData({ newEnd: e.detail.value }); },
  onNewCoach(e) {
    const coach = this.data.coaches[e.detail.value];
    this.setData({ newCoachId: coach.id, newCoachName: coach.name });
  },
  onNewClassroom(e) {
    const room = this.data.classrooms[e.detail.value];
    this.setData({ newClassroomId: room.id, newClassroomName: room.name });
  },
  onReason(e) { this.setData({ reason: e.detail.value }); },
  submit() {
    if (this.data.submitting || this.data.loading) return;
    const { scheduleId, type, newDate, newStart, newEnd, newCoachId, newClassroomId, reason } = this.data;
    if (!scheduleId || !reason.trim()) {
      wx.showToast({ title: '请选择课程并填写原因', icon: 'none' });
      return;
    }
    if ((type === 'TIME' && (!newDate || !newStart || !newEnd || newEnd <= newStart))
        || (type === 'COACH' && !newCoachId) || (type === 'CLASSROOM' && !newClassroomId)) {
      wx.showToast({ title: '请填写有效的新安排', icon: 'none' });
      return;
    }
    const body = { scheduleId: Number(scheduleId), type, reason: reason.trim() };
    if (type === 'TIME') Object.assign(body, { newCourseDate: newDate, newStartTime: newStart, newEndTime: newEnd });
    if (type === 'COACH') body.newCoachId = Number(newCoachId);
    if (type === 'CLASSROOM') body.newClassroomId = Number(newClassroomId);
    this.setData({ submitting: true, error: '' });
    return api.post('/api/coach-end/change-requests', body).then(() => {
      wx.showToast({ title: '已提交', icon: 'success' });
      this.setData({ reason: '' });
      return this.onShow();
    }).catch(error => this.setData({ error: error?.message || '申请提交失败，请重试' }))
      .finally(() => this.setData({ submitting: false }));
  }
});
