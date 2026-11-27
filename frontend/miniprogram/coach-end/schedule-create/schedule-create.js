const api = require('../api');
const { studioDate } = require('../../utils/util');
const { courseLabel, endTime, futureStart } = require('../../utils/schedule-form');

Page({
  data: {
    courses: [], courseIndex: -1, courseChoices: [], classrooms: [], roomIndex: -1, roomChoices: [],
    today: '', courseDate: '', startTime: '09:00', endTime: '',
    maxCapacity: '8', privateClass: false, booking: false,
    memberReference: '', member: null, reason: '',
    loading: false, lookingUp: false, submitting: false, loadError: '', memberError: '', error: ''
  },

  onLoad(q = {}) {
    const today = studioDate();
    const booking = q.mode === 'booking';
    this.setData({ today, booking, courseDate: q.date && q.date >= today ? q.date : today });
    if (booking) wx.setNavigationBarTitle({ title: '排课并代约' });
    return this.load();
  },

  onShow() {
    if (!this.reloadCourses) return;
    this.reloadCourses = false;
    return this.load();
  },

  async load() {
    if (this.data.loading) return;
    const selectedId = this.data.courses[this.data.courseIndex]?.courseId;
    const selectedRoom = this.data.classrooms[this.data.roomIndex]?.id;
    this.setData({ loading: true, loadError: '' });
    try {
      const [rows, classrooms] = await Promise.all([
        api.get('/api/coach-end/courses/my'), api.get('/api/classrooms')
      ]);
      const courses = rows.filter(course => course.status === 'APPROVED' && course.courseStatus === 'ON_SHELF')
        .map(course => ({ ...course, label: courseLabel(course.courseName, course.courseType) }));
      const courseIndex = courses.findIndex(course => course.courseId === selectedId);
      const roomIndex = classrooms.findIndex(room => room.id === selectedRoom);
      this.setData({ courses, classrooms, roomIndex,
        courseChoices: ['请选择课程', ...courses.map(course => course.label)],
        roomChoices: ['请选择教室', ...classrooms.map(room => room.name + '（容量 ' + room.capacity + '）')]
      });
      this.selectCourse(courseIndex);
    } catch (error) {
      this.setData({ loadError: error?.message || '课程和教室加载失败，请重试。' });
    } finally {
      this.setData({ loading: false });
    }
  },

  goCourses() {
    this.reloadCourses = true;
    wx.navigateTo({ url: '/coach-end/courses/courses', fail: () => { this.reloadCourses = false; } });
  },

  selectCourse(courseIndex) {
    const course = this.data.courses[courseIndex];
    const privateClass = course?.courseType === 'PRIVATE';
    const room = this.data.classrooms[this.data.roomIndex];
    const maxCapacity = privateClass ? '1' : this.data.privateClass
      ? String(Math.min(8, room?.capacity || 8)) : this.data.maxCapacity;
    this.setData({ courseIndex, privateClass, maxCapacity,
      endTime: endTime(this.data.startTime, course?.durationMinutes) });
  },

  onCourse(e) { this.selectCourse(Number(e.detail.value) - 1); },

  onRoom(e) {
    const roomIndex = Number(e.detail.value) - 1;
    const room = this.data.classrooms[roomIndex];
    const patch = { roomIndex };
    if (!this.data.privateClass && room && Number(this.data.maxCapacity) > room.capacity) {
      patch.maxCapacity = String(room.capacity);
    }
    this.setData(patch);
  },

  onDate(e) { this.setData({ courseDate: e.detail.value }); },
  onStart(e) {
    const startTime = e.detail.value;
    const course = this.data.courses[this.data.courseIndex];
    this.setData({ startTime, endTime: endTime(startTime, course?.durationMinutes) });
  },
  onCap(e) { this.setData({ maxCapacity: e.detail.value }); },
  onReason(e) { this.setData({ reason: e.detail.value }); },
  onMember(e) { this.setData({ memberReference: e.detail.value, member: null, memberError: '' }); },

  async lookupMember() {
    if (!this.data.booking || this.data.lookingUp || this.data.submitting) return;
    const memberReference = this.data.memberReference.trim();
    if (!/^(?:1[0-9]{10}|MB[0-9]{1,30})$/i.test(memberReference)) {
      this.setData({ memberError: '请输入完整手机号或会员编号。', member: null });
      return;
    }
    this.setData({ lookingUp: true, memberError: '', member: null });
    try {
      const member = await api.post('/api/coach-end/schedules/booking-member', { memberReference });
      if (this.data.memberReference.trim() === memberReference) this.setData({ member });
    } catch (error) {
      this.setData({ memberError: error?.message || '会员核对失败，请重试。' });
    } finally {
      this.setData({ lookingUp: false });
    }
  },

  async submit() {
    const { courseDate, startTime, courses, courseIndex, classrooms, roomIndex, maxCapacity,
      booking, member, memberReference, reason, loading, loadError, lookingUp, submitting } = this.data;
    if (loading || loadError || lookingUp || submitting) return;
    const course = courses[courseIndex];
    const room = classrooms[roomIndex];
    const end = endTime(startTime, course?.durationMinutes);
    const capacity = course?.courseType === 'PRIVATE' ? 1 : Number(maxCapacity);
    if (!course || !room) { this.setData({ error: '请选择课程和教室。' }); return; }
    if (!futureStart(courseDate, startTime)) { this.setData({ error: '请选择未来的上课日期和开始时间。' }); return; }
    if (!end) { this.setData({ error: '请调整开始时间，课程须在当天内完成。' }); return; }
    if (!Number.isInteger(capacity) || capacity < 1 || capacity > room.capacity) {
      this.setData({ error: '请填写不超过教室容量的正整数人数。' });
      return;
    }
    if (booking && (!member || !reason.trim())) {
      this.setData({ error: '请核对会员并填写代约原因。' });
      return;
    }

    const schedule = { courseId: course.courseId, coachId: 0, classroomId: room.id,
      courseDate, startTime, endTime: end, maxCapacity: capacity };
    this.setData({ submitting: true, error: '' });
    try {
      if (booking) {
        const confirmed = await new Promise(resolve => wx.showModal({
          title: '确认排课并代约',
          content: [member.nickname, course.courseName, courseDate + ' ' + startTime].join(' · '),
          success: result => resolve(result.confirm), fail: () => resolve(false)
        }));
        if (!confirmed) return;
        const result = await api.post('/api/coach-end/schedules/book', {
          schedule, userId: member.userId, memberReference: memberReference.trim(), reason: reason.trim()
        });
        wx.showToast({ title: '排课并预约成功', icon: 'success' });
        wx.redirectTo({ url: '/coach-end/students/students?scheduleId=' + result.scheduleId });
      } else {
        await api.post('/api/coach-end/schedules', schedule);
        wx.showToast({ title: '排课成功', icon: 'success' });
        wx.navigateBack();
      }
    } catch (error) {
      this.setData({ error: error?.message || '未能确认保存结果，请查看课表后重试。' });
    } finally {
      this.setData({ submitting: false });
    }
  }
});
