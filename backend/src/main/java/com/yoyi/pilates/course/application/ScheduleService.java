package com.yoyi.pilates.course.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoyi.pilates.booking.application.BookingService;
import com.yoyi.pilates.booking.application.ScheduleExecution;
import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import com.yoyi.pilates.booking.infrastructure.mapper.BookingMapper;
import com.yoyi.pilates.coach.infrastructure.entity.CoachLeaveEntity;
import com.yoyi.pilates.coach.infrastructure.mapper.CoachLeaveMapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.course.infrastructure.entity.*;
import com.yoyi.pilates.course.infrastructure.mapper.*;
import com.yoyi.pilates.course.interfaces.dto.*;
import com.yoyi.pilates.ledger.StudioCancellationCapture;
import com.yoyi.pilates.notify.application.NotificationService;
import com.yoyi.pilates.system.security.*;
import com.yoyi.pilates.training.infrastructure.entity.TrainingRecordEntity;
import com.yoyi.pilates.training.infrastructure.mapper.TrainingRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** 所有排课入口共享校验；资源锁防并发重叠，排课锁与预约/签到共用。 */
@Service
@RequiredArgsConstructor
public class ScheduleService {
    private final ScheduleExecution execution;
    private final BookingService bookingService;
    private final CourseScheduleMapper scheduleMapper;
    private final CourseQueryService courseQueryService;
    private final ClassroomMapper classroomMapper;
    private final CourseCoachService courseCoachService;
    private final BookingMapper bookingMapper;
    private final CoachLeaveMapper leaveMapper;
    private final TrainingRecordMapper trainingRecords;
    private final StudioCancellationCapture cancellation;
    private final NotificationService notifications;

    public Long create(ScheduleCreateRequest request) {
        authorize("create"); forceCoach(request);
        return execution.run(null,null,true,()->insert(request));
    }
    private Long insert(ScheduleCreateRequest request) {
        validate(request,null);
        return insertSchedule(request, 0);
    }

    private Long insertSchedule(ScheduleCreateRequest request, int bookedCount) {
        CourseScheduleEntity s=new CourseScheduleEntity();
        org.springframework.beans.BeanUtils.copyProperties(request,s);
        s.setBookedCount(bookedCount);s.setStatus("SCHEDULED");
        if(scheduleMapper.insert(s)!=1) throw BusinessException.of(ResultCode.CONFLICT);
        execution.changed(s.getId());return s.getId();
    }

    /** 店长确认后的调课，保留原排课历史并迁移未签到的免费预约。 */
    public void approveChange(Long id, ScheduleCreateRequest edit, Integer expectedVersion,
                              Integer expectedCount, Runnable markApproved) {
        authorize("update");
        List<Long> memberIds = booked(id).stream().map(BookingEntity::getUserId).sorted().toList();
        execution.runForMembers(List.of(id), memberIds, true, () -> {
            CourseScheduleEntity original = scheduleMapper.lockById(id);
            requireEditable(original);
            List<BookingEntity> affected = booked(id);
            if (!Objects.equals(expectedVersion, original.getVersion()) || !Objects.equals(expectedCount, affected.size())
                    || !memberIds.equals(affected.stream().map(BookingEntity::getUserId).sorted().toList())) {
                throw BusinessException.of(ResultCode.CONFLICT, "排课或学员名单已更新，请重新核对调课预览");
            }
            if (!protectedFieldsChanged(original, edit))
                throw BusinessException.of(ResultCode.BAD_REQUEST, "新安排与当前排课相同");
            validate(edit, id);
            if (original.getBookedCount() != affected.size()) {
                throw BusinessException.of(ResultCode.CONFLICT, "已有签到或其他出勤记录，不能直接调课");
            }
            if (bookingMapper.countHistory(id) == 0) {
                org.springframework.beans.BeanUtils.copyProperties(edit, original);
                if (scheduleMapper.updateById(original) != 1) throw BusinessException.of(ResultCode.CONFLICT);
                markApproved.run();
                execution.changed(id);
                return null;
            }
            for (BookingEntity booking : affected) {
                if (booking.getUserPackageId() != null || cancellation.hasTerms(booking.getId())
                        || trainingRecords.exists(new LambdaQueryWrapper<TrainingRecordEntity>()
                        .eq(TrainingRecordEntity::getBookingId, booking.getId()))) {
                    throw BusinessException.of(ResultCode.CONFLICT, "有课包、账本条款或训练记录的预约，请先人工处理");
                }
                bookingService.requireNoOverlap(booking.getUserId(), id, edit.getCourseDate(), edit.getStartTime(), edit.getEndTime());
                if (!original.getCourseDate().equals(edit.getCourseDate())) {
                    bookingService.requireDailyLimit(booking.getUserId(), edit.getCourseDate(), id);
                }
            }
            if (edit.getMaxCapacity() < affected.size())
                throw BusinessException.of(ResultCode.CONFLICT, "新安排容量不足，无法保留现有预约");

            var before = courseQueryService.scheduleDetail(id);
            Long replacementId = insertSchedule(edit, affected.size());
            var after = courseQueryService.scheduleDetail(replacementId);
            for (BookingEntity booking : affected) {
                booking.setScheduleId(replacementId);
                if (bookingMapper.updateById(booking) != 1) throw BusinessException.of(ResultCode.CONFLICT);
                notifications.sendText(booking.getUserId(), "SCHEDULE_CHANGE", "课程安排已变更",
                        before.getCourseName() + "\n原安排：" + arrangement(before) + "\n新安排：" + arrangement(after)
                                + "\n预约已保留，如无法出席，可在预约详情取消。",
                        "SCHEDULE_CHANGE", booking.getId() + ":" + replacementId);
            }
            original.setStatus("CANCELLED");
            original.setCancelReason("课程已调课");
            original.setBookedCount(0);
            if (scheduleMapper.updateById(original) != 1) throw BusinessException.of(ResultCode.CONFLICT);
            markApproved.run();
            execution.changed(id);
            return null;
        });
    }

    public List<BookingEntity> booked(Long scheduleId) {
        return bookingMapper.selectList(new LambdaQueryWrapper<BookingEntity>()
                .eq(BookingEntity::getScheduleId, scheduleId).eq(BookingEntity::getStatus, "BOOKED"));
    }

    private String arrangement(ScheduleView schedule) {
        var time = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
        return schedule.getCourseDate() + " " + schedule.getStartTime().format(time) + "–" + schedule.getEndTime().format(time)
                + " · " + schedule.getCoachName() + " · " + schedule.getClassroomName();
    }
    public int createRecurring(RecurringScheduleRequest request) {
        authorize("create");
        return execution.run(null,null,true,()->{
            if(request.getFromDate()==null || request.getToDate()==null || request.getToDate().isBefore(request.getFromDate())
                    || request.getWeekdays()==null || request.getWeekdays().isEmpty() || request.getWeekdays().stream().anyMatch(d->d==null||d<1||d>7))
                throw BusinessException.of(ResultCode.BAD_REQUEST,"日期范围和星期不正确");
            if(request.getToDate().isAfter(request.getFromDate().plusDays(365))) throw BusinessException.of(ResultCode.BAD_REQUEST,"单次周期范围不能超过一年");
            int count=0;
            for(LocalDate date=request.getFromDate();!date.isAfter(request.getToDate());date=date.plusDays(1)) {
                if(!request.getWeekdays().contains(date.getDayOfWeek().getValue()))continue;
                ScheduleCreateRequest one=new ScheduleCreateRequest();org.springframework.beans.BeanUtils.copyProperties(request,one);one.setCourseDate(date);forceCoach(one);insert(one);count++;
            }
            if(count==0)throw BusinessException.of(ResultCode.BAD_REQUEST,"没有匹配的排课日期");return count;
        });
    }
    public void update(Long id,ScheduleCreateRequest request) {
        authorize("update");
        execution.run(id,null,true,()->{
            CourseScheduleEntity s=scheduleMapper.lockById(id);
            requireEditable(s);
            LoginUser actor=SecurityUtils.current().orElse(null);
            if(actor!=null&&actor.isCoach()&&!actor.isAdminRole()&&!Objects.equals(actor.getCoachId(),s.getCoachId()))
                throw BusinessException.of(ResultCode.FORBIDDEN,"只能编辑本人排课");
            if(bookingMapper.countHistory(id)>0 && protectedFieldsChanged(s,request))
                throw BusinessException.of(ResultCode.CONFLICT,"已有预约历史，仅允许调整容量；改期需另行确认流程");
            validate(request,id);
            long occupied=bookingMapper.selectCount(new LambdaQueryWrapper<BookingEntity>().eq(BookingEntity::getScheduleId,id)
                    .in(BookingEntity::getStatus,List.of("BOOKED","SIGNED_IN","COMPLETED","NO_SHOW")));
            if(request.getMaxCapacity()<occupied)throw BusinessException.of(ResultCode.CONFLICT,"人数上限不能低于实际已占人数("+occupied+")");
            org.springframework.beans.BeanUtils.copyProperties(request,s);s.setBookedCount((int)occupied);
            if(scheduleMapper.updateById(s)!=1)throw BusinessException.of(ResultCode.CONFLICT,"排课已变化，请刷新重试");
            execution.changed(id);return null;
        });
    }
    public void cancel(Long id,String reason) {authorize("cancel");bookingService.cancelSchedule(id,reason);}
    private void authorize(String operation) {
        LoginUser actor=SecurityUtils.current().orElse(null);if(actor==null)return; // internal jobs/tests; HTTP endpoints also require authentication
        if(actor.isAdminRole())return;
        if(actor.getRoles()!=null&&actor.getRoles().contains("STORE_MANAGER")&&actor.hasPerm("schedule:"+operation))return;
        if(actor.isCoach()&&actor.getCoachId()!=null&&actor.hasPerm("coach:schedule:"+operation))return;
        throw BusinessException.of(ResultCode.FORBIDDEN,"没有排课"+operation+"权限");
    }
    private void forceCoach(ScheduleCreateRequest request) {
        LoginUser actor=SecurityUtils.current().orElse(null);
        if(actor!=null&&actor.isCoach()&&!actor.isAdminRole())request.setCoachId(actor.getCoachId());
    }
    private void requireEditable(CourseScheduleEntity s) {
        if(s==null)throw BusinessException.of(ResultCode.SCHEDULE_NOT_FOUND);
        if(!"SCHEDULED".equals(s.getStatus())||!StudioTime.now().isBefore(LocalDateTime.of(s.getCourseDate(),s.getStartTime())))
            throw BusinessException.of(ResultCode.CONFLICT,"仅未开始的待开课排课可编辑");
    }
    private boolean protectedFieldsChanged(CourseScheduleEntity s,ScheduleCreateRequest r) {
        return !Objects.equals(s.getCourseId(),r.getCourseId())||!Objects.equals(s.getCoachId(),r.getCoachId())
                ||!Objects.equals(s.getClassroomId(),r.getClassroomId())||!Objects.equals(s.getCourseDate(),r.getCourseDate())
                ||!Objects.equals(s.getStartTime(),r.getStartTime())||!Objects.equals(s.getEndTime(),r.getEndTime());
    }
    private void validate(ScheduleCreateRequest r,Long exclude) {
        if(r.getCourseId()==null||r.getCoachId()==null||r.getClassroomId()==null||r.getCourseDate()==null||r.getStartTime()==null||r.getEndTime()==null||r.getMaxCapacity()==null||r.getMaxCapacity()<1)
            throw BusinessException.of(ResultCode.BAD_REQUEST,"课程、教练、教室、日期、时间和正整数容量均为必填");
        if(!r.getEndTime().isAfter(r.getStartTime()))throw BusinessException.of(ResultCode.BAD_REQUEST,"结束时间须晚于开始时间");
        if(!StudioTime.now().isBefore(LocalDateTime.of(r.getCourseDate(),r.getStartTime())))throw BusinessException.of(ResultCode.BAD_REQUEST,"必须安排未来时段");
        CourseEntity c=courseQueryService.requireCourse(r.getCourseId());
        if ("PRIVATE".equals(c.getType()) && r.getMaxCapacity() != 1)
            throw BusinessException.of(ResultCode.BAD_REQUEST, "一对一私教课的人数上限必须为1");
        if(!"ON_SHELF".equals(c.getStatus()))throw BusinessException.of(ResultCode.CONFLICT,"课程已下架，不能排课");
        courseQueryService.requireCoach(r.getCoachId());
        ClassroomEntity room=classroomMapper.selectById(r.getClassroomId());
        if(room==null||!"ACTIVE".equals(room.getStatus()))throw BusinessException.of(ResultCode.CONFLICT,"教室不存在或已停用");
        if(room.getCapacity()==null||r.getMaxCapacity()>room.getCapacity())throw BusinessException.of(ResultCode.BAD_REQUEST,"人数上限不能超过教室容量("+room.getCapacity()+")");
        if(!courseCoachService.hasQualification(r.getCourseId(),r.getCoachId()))throw BusinessException.of(ResultCode.FORBIDDEN,"该教练尚无有效授课资格");
        if(leaveMapper.exists(new LambdaQueryWrapper<CoachLeaveEntity>().eq(CoachLeaveEntity::getCoachId,r.getCoachId()).eq(CoachLeaveEntity::getLeaveDate,r.getCourseDate())
                .eq(CoachLeaveEntity::getStatus,"APPROVED").lt(CoachLeaveEntity::getStartTime,r.getEndTime()).gt(CoachLeaveEntity::getEndTime,r.getStartTime())))
            throw BusinessException.of(ResultCode.CONFLICT,"该教练在此时段已批准请假");
        var base=new LambdaQueryWrapper<CourseScheduleEntity>().eq(CourseScheduleEntity::getCourseDate,r.getCourseDate()).eq(CourseScheduleEntity::getStatus,"SCHEDULED")
                .lt(CourseScheduleEntity::getStartTime,r.getEndTime()).gt(CourseScheduleEntity::getEndTime,r.getStartTime()).ne(exclude!=null,CourseScheduleEntity::getId,exclude);
        if(scheduleMapper.exists(base.clone().eq(CourseScheduleEntity::getCoachId,r.getCoachId())))throw BusinessException.of(ResultCode.CONFLICT,"教练时间冲突");
        if(scheduleMapper.exists(base.clone().eq(CourseScheduleEntity::getClassroomId,r.getClassroomId())))throw BusinessException.of(ResultCode.CONFLICT,"教室时间冲突");
    }
    public CourseScheduleEntity requireSchedule(Long id) {var s=scheduleMapper.selectById(id);if(s==null)throw BusinessException.of(ResultCode.SCHEDULE_NOT_FOUND);return s;}
    public Page<CourseScheduleEntity> pageAdmin(LocalDate from,LocalDate to,Long coach,Long course,String status,long page,long size) {
        return scheduleMapper.selectPage(Page.of(page,size),new LambdaQueryWrapper<CourseScheduleEntity>().ge(from!=null,CourseScheduleEntity::getCourseDate,from)
                .le(to!=null,CourseScheduleEntity::getCourseDate,to).eq(coach!=null,CourseScheduleEntity::getCoachId,coach).eq(course!=null,CourseScheduleEntity::getCourseId,course)
                .eq(status!=null&&!status.isBlank(),CourseScheduleEntity::getStatus,status).orderByAsc(CourseScheduleEntity::getCourseDate).orderByAsc(CourseScheduleEntity::getStartTime));
    }
    public ScheduleView adminView(CourseScheduleEntity s) {
        ScheduleView v=courseQueryService.scheduleDetail(s.getId());
        boolean future="SCHEDULED".equals(s.getStatus())&&StudioTime.now().isBefore(LocalDateTime.of(s.getCourseDate(),s.getStartTime()));
        v.setEditable(future);v.setHasBookingHistory(bookingMapper.countHistory(s.getId())>0);
        boolean signed=bookingMapper.exists(new LambdaQueryWrapper<BookingEntity>().eq(BookingEntity::getScheduleId,s.getId()).eq(BookingEntity::getStatus,"SIGNED_IN"));
        v.setCancellable(future&&!signed);v.setManagementReason(signed?"已有会员签到，不能直接取消":v.isHasBookingHistory()?"已有预约历史，仅可调整容量":"");return v;
    }
}
