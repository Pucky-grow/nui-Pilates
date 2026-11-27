const u = require('../common');
const display = require('../../utils/studio-ledger');
const { courseLabel, endTime, futureStart } = require('../../utils/schedule-form');

Page({
  data: {
    id: '', form: { courseDate: u.date(), startTime: '09:00', maxCapacity: 8 },
    courseFields: [], fields: [], options: {}, busy: false, error: '', history: false,
    canEdit: false, canCancel: false, canCreateCourse: false, canEditCourse: false,
    canViewCourses: false, canAssignCoach: false, eligibleCoachCount: 0,
    bookUserId: '', bookMemberName: '', reason: '', remark: ''
  },

  onLoad(q) {
    this.ledgerMemberId = q.ledgerMemberId;
    this.sendLedger = display.commands(u.api);
    this.setData({
      id: q.id || '', bookUserId: q.id ? '' : q.bookUserId || '',
      'form.courseDate': q.date || u.date()
    });
    if (this.data.bookUserId && !u.isAdmin()) {
      this.setData({ error: '只有管理员可以为会员新建场次并代约。' });
      return;
    }
    if (u.guard(q.id ? 'schedule:view' : 'schedule:create')) return this.load();
  },

  onShow() {
    if (!this.refreshAfterReturn) return;
    this.refreshAfterReturn = false;
    return this.refreshOptions();
  },

  onPullDownRefresh() {
    return this.load();
  },

  load() {
    if (this.data.bookUserId && !u.isAdmin()) return;
    return u.run(this, async () => {
      const options = await u.api.get('/admin/schedules/options');
      let { form } = this.data;
      let history = false;
      let canEdit = u.can('schedule:create');
      let canCancel = false;
      let managementReason = '';
      if (this.data.id) {
        const row = await u.api.get('/admin/schedules/' + this.data.id);
        this.setData({ originalSummary: [row.courseName, row.coachName, row.classroomName].filter(Boolean).join(' · ') });
        form = {
          courseId: row.courseId, coachId: row.coachId, classroomId: row.classroomId,
          courseDate: row.courseDate, startTime: row.startTime.slice(0, 5), endTime: row.endTime.slice(0, 5),
          maxCapacity: row.maxCapacity
        };
        history = row.hasBookingHistory;
        canEdit = row.editable && u.can('schedule:update');
        canCancel = row.cancellable && u.can('schedule:cancel');
        managementReason = row.managementReason;
      }
      let bookMemberName = '';
      if (this.data.bookUserId) {
        if (this.ledgerMemberId) {
          const { member } = await u.api.get('/api/admin/studio-ledger/members/' + this.ledgerMemberId);
          if (String(member.user_id) !== String(this.data.bookUserId)) {
            throw { message: '会员资料已变化，请返回会员账本重新预约' };
          }
          bookMemberName = member.nickname || member.member_no;
        } else {
          const { user, member } = await u.api.get('/admin/members/' + this.data.bookUserId);
          bookMemberName = user.nickname || member.memberNo;
        }
      }
      this.setData({
        options, form, history, canEdit, canCancel, managementReason, bookMemberName,
        canCreateCourse: u.can('course:create'), canEditCourse: u.can('course:update'),
        canViewCourses: u.can('course:view'), canAssignCoach: u.can('manager:course:coach:manage')
      });
      this.updateFields();
    });
  },

  refreshOptions() {
    return u.run(this, async () => {
      const options = await u.api.get('/admin/schedules/options');
      const saved = this.savedCourseId;
      this.savedCourseId = null;
      const { courseId } = this.data.form;
      const available = options.courses || [];
      this.setData({ options });
      if (saved && available.some(course => Number(course.id) === Number(saved)) && Number(saved) !== Number(courseId)) {
        this.change({ detail: { key: 'courseId', value: saved } });
        return;
      }
      if (!this.data.id && courseId && !available.some(course => Number(course.id) === Number(courseId))) {
        this.setData({ 'form.courseId': '', 'form.coachId': '' });
      } else if (!this.data.history && this.data.form.coachId &&
          !this.eligibleCoaches().some(coach => Number(coach.id) === Number(this.data.form.coachId))) {
        this.setData({ 'form.coachId': '' });
      }
      this.updateFields();
    });
  },

  eligibleCoaches() {
    const { options, form } = this.data;
    const ids = new Set((options.qualifications || [])
      .filter(row => Number(row.courseId) === Number(form.courseId)).map(row => Number(row.coachId)));
    return (options.coaches || []).filter(coach => ids.has(Number(coach.id)));
  },

  updateFields() {
    const { options, form, history, id } = this.data;
    const courses = options.courses || [];
    const selected = courses.find(course => Number(course.id) === Number(form.courseId));
    const privateClass = selected?.type === 'PRIVATE';
    const coaches = this.eligibleCoaches();
    const end = endTime(form.startTime, selected?.durationMinutes);
    const durationHint = !id && selected
      ? end ? selected.durationMinutes + ' 分钟，预计 ' + end + ' 结束' : '所选开始时间和课程时长须在当天内完成。'
      : '';
    this.setData({
      privateClass, eligibleCoachCount: coaches.length,
      courseFields: [{
        key: 'courseId', label: '课程', type: 'select', readonly: history || courses.length === 0,
        options: [u.option('', '请选择课程'), ...courses.map(course => u.option(course.id, courseLabel(course.name, course.type)))]
      }],
      fields: [
        { key: 'coachId', label: '授课教练', type: 'select', options: [u.option('', '请选择教练'), ...coaches.map(coach => u.option(coach.id, coach.name))], readonly: history || !coaches.length },
        { key: 'classroomId', label: '教室', type: 'select',
          options: [u.option('', '请选择教室'), ...(options.classrooms || []).map(room => u.option(room.id, room.name + '（容量 ' + room.capacity + '）'))],
          readonly: history || !options.classrooms?.length },
        { key: 'courseDate', label: '上课日期', type: 'date', readonly: history },
        { key: 'startTime', label: '开始时间', type: 'time', readonly: history, hint: durationHint },
        ...(id ? [{ key: 'endTime', label: '结束时间', type: 'time', readonly: history }] : []),
        { key: 'maxCapacity', label: '人数上限', type: 'number', readonly: privateClass, hint: privateClass ? '一对一私教为 1 人' : '容量不得低于已占位人数，也不得超过教室容量。' }
      ]
    });
  },

  change(e) {
    const { key, value } = e.detail;
    const patch = { ['form.' + key]: value };
    if (key === 'courseId') {
      patch['form.coachId'] = '';
      const selected = (this.data.options.courses || []).find(course => Number(course.id) === Number(value));
      if (selected?.type === 'PRIVATE') patch['form.maxCapacity'] = 1;
      else if (this.data.privateClass) patch['form.maxCapacity'] = Math.min(8, this.roomCapacity());
    }
    if (key === 'classroomId' && !this.data.id && !this.data.privateClass) {
      const limit = this.roomCapacity(value);
      if (Number(this.data.form.maxCapacity) > limit) patch['form.maxCapacity'] = limit;
    }
    this.setData(patch);
    this.updateFields();
  },

  roomCapacity(id = this.data.form.classroomId) {
    const room = (this.data.options.classrooms || []).find(option => Number(option.id) === Number(id));
    return room?.capacity || 8;
  },

  bookingInput(e) {
    this.setData({ [e.currentTarget.dataset.key]: e.detail.value });
  },

  openCoursePage(url) {
    if (this.data.busy) return;
    this.refreshAfterReturn = true;
    wx.navigateTo({
      url, events: { courseSaved: ({ id }) => { this.savedCourseId = id; } },
      fail: () => { this.refreshAfterReturn = false; }
    });
  },

  newCourse() {
    if (u.can('course:create')) this.openCoursePage('/admin/course-edit/course-edit?fromSchedule=1');
  },

  editCourse() {
    if (u.can('course:update') && this.data.form.courseId) {
      this.openCoursePage('/admin/course-edit/course-edit?fromSchedule=1&id=' + this.data.form.courseId);
    }
  },

  courseDirectory() {
    if (u.can('course:view')) this.openCoursePage('/admin/courses/courses');
  },

  assignCoach() {
    if (u.can('manager:course:coach:manage') && this.data.form.courseId) {
      this.openCoursePage('/admin/qualifications/qualifications?id=' + this.data.form.courseId);
    }
  },

  save() {
    if (!this.data.canEdit) return;
    return u.run(this, async () => {
      const { form, id, bookUserId, bookMemberName, reason, remark, privateClass } = this.data;
      const selected = (this.data.options.courses || []).find(course => Number(course.id) === Number(form.courseId));
      const body = {
        ...form, courseId: Number(form.courseId), coachId: Number(form.coachId),
        classroomId: Number(form.classroomId), maxCapacity: privateClass ? 1 : Number(form.maxCapacity),
        endTime: id ? form.endTime : endTime(form.startTime, selected?.durationMinutes)
      };
      if (!body.courseId || !body.coachId || !body.classroomId || !Number.isInteger(body.maxCapacity) || body.maxCapacity < 1) {
        throw { message: '请选择课程、教练和教室，并填写正整数人数上限' };
      }
      if (!futureStart(body.courseDate, body.startTime)) throw { message: '请选择未来的上课日期和开始时间' };
      if (!body.endTime || body.endTime <= body.startTime) throw { message: '请调整开始时间或课程时长，课程须在当天内完成' };
      if (bookUserId) {
        if (!reason.trim()) throw { message: '请填写代预约原因' };
        const summary = [bookMemberName, selected.name, body.courseDate + ' ' + body.startTime].join(' · ');
        if (!await u.confirm('确认排课并代约', summary)) return;
        const request = { schedule: body, reason: reason.trim(), remark };
        const bookingId = this.ledgerMemberId
          ? await this.sendLedger('/api/admin/studio-ledger/members/' + this.ledgerMemberId + '/scheduled-bookings', request)
          : await u.api.post('/api/admin/bookings/new-schedule', { ...request, userId: Number(bookUserId) });
        wx.showToast({ title: '排课并预约成功', icon: 'success' });
        wx.redirectTo({ url: '/admin/booking-detail/booking-detail?id=' + bookingId });
        return;
      }
      if (!await u.confirm('保存排课', (selected?.name || this.data.originalSummary) + ' · ' + body.courseDate + ' ' + body.startTime)) return;
      if (id) {
        await u.api.put('/admin/schedules/' + id, body);
      } else {
        this.setData({ id: await u.api.post('/admin/schedules', body), 'form.endTime': body.endTime,
          canCancel: u.can('schedule:cancel'), originalSummary: selected.name });
        this.updateFields();
      }
      u.success();
    });
  },

  cancel() {
    return u.run(this, async () => {
      if (!await u.confirm('取消整场排课', '该场次所有预约都会取消，填写的原因会展示给会员。')) return;
      const reason = await u.reason('填写整场取消原因', '');
      if (!reason) return;
      await u.api.post('/admin/schedules/' + this.data.id + '/cancel?reason=' + encodeURIComponent(reason), {});
      this.setData({ canCancel: false, canEdit: false, managementReason: '排课已取消' });
      u.success();
    });
  },

  students() {
    wx.navigateTo({ url: '/admin/bookings/bookings?scheduleId=' + this.data.id });
  }
});
