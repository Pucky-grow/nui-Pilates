package com.yoyi.pilates.booking.interfaces;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.booking.application.BookingQueryService;
import com.yoyi.pilates.booking.application.BookingService;
import com.yoyi.pilates.booking.application.AttendanceCodeService;
import com.yoyi.pilates.booking.interfaces.dto.StudentView;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.system.security.LoginUser;
import com.yoyi.pilates.system.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 教练工作台接口(小程序教练端, 需 COACH 角色).
 * 数据权限: 教练仅能操作自己授课的排课.
 */
@RestController
@RequestMapping("/api/coach-end")
@RequiredArgsConstructor
public class CoachEndController {

    private final com.yoyi.pilates.course.application.CourseQueryService courseQueries;
    private final CourseScheduleMapper scheduleMapper;
    private final BookingQueryService bookingQueryService;
    private final BookingService bookingService;
    private final AttendanceCodeService attendanceCodes;

    /** 今日课程 */
    @GetMapping("/today")
    public Result<List<com.yoyi.pilates.course.interfaces.dto.ScheduleView>> today() {
        LoginUser coach = requireCoach();
        return Result.ok(scheduleMapper.selectList(new LambdaQueryWrapper<CourseScheduleEntity>()
                .eq(CourseScheduleEntity::getCoachId, coach.getCoachId())
                .eq(CourseScheduleEntity::getCourseDate, com.yoyi.pilates.common.util.StudioTime.today())
                .ne(CourseScheduleEntity::getStatus, "CANCELLED")
                .orderByAsc(CourseScheduleEntity::getStartTime)).stream().map(s -> courseQueries.scheduleDetail(s.getId())).toList());
    }

    /** 我的课表(日期范围) */
    @GetMapping("/schedules")
    public Result<List<com.yoyi.pilates.course.interfaces.dto.ScheduleView>> mySchedules(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate) {
        LoginUser coach = requireCoach();
        return Result.ok(scheduleMapper.selectList(new LambdaQueryWrapper<CourseScheduleEntity>()
                .eq(CourseScheduleEntity::getCoachId, coach.getCoachId())
                .ge(CourseScheduleEntity::getCourseDate, fromDate)
                .le(CourseScheduleEntity::getCourseDate, toDate)
                .ne(CourseScheduleEntity::getStatus, "CANCELLED")
                .orderByAsc(CourseScheduleEntity::getCourseDate)
                .orderByAsc(CourseScheduleEntity::getStartTime)).stream().map(s -> courseQueries.scheduleDetail(s.getId())).toList());
    }

    /** 某节课的学员列表 */
    @GetMapping("/schedules/{scheduleId}/students")
    public Result<List<StudentView>> students(@PathVariable Long scheduleId) {
        checkScheduleOwnership(scheduleId);
        return Result.ok(bookingQueryService.studentsOfSchedule(scheduleId));
    }

    /** 教练代签到 */
    @PostMapping("/bookings/{bookingId}/check-in")
    public Result<Void> coachCheckIn(@PathVariable Long bookingId) {
        LoginUser coach = requireCoach();
        var booking = bookingService.requireBooking(bookingId);
        checkScheduleOwnership(booking.getScheduleId());
        bookingService.checkIn(bookingId, "COACH", coach.getId());
        return Result.ok();
    }

    @PostMapping("/schedules/{scheduleId}/attendance-code")
    public Result<AttendanceCodeService.Ticket> attendanceCode(@PathVariable Long scheduleId) {
        checkScheduleOwnership(scheduleId);
        return Result.ok(attendanceCodes.issue(scheduleMapper.selectById(scheduleId)));
    }

    // ---------------- 数据权限 ----------------

    private LoginUser requireCoach() {
        LoginUser user = SecurityUtils.requireUser();
        if (!user.isCoach() && !user.isAdminRole()) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "仅教练账号可访问");
        }
        if (user.getCoachId() == null && !user.isAdminRole()) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "账号未关联教练档案");
        }
        return user;
    }

    private void checkScheduleOwnership(Long scheduleId) {
        LoginUser user = requireCoach();
        if (user.isAdminRole()) {
            return;
        }
        CourseScheduleEntity schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null) {
            throw BusinessException.of(ResultCode.SCHEDULE_NOT_FOUND);
        }
        if (!schedule.getCoachId().equals(user.getCoachId())) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "只能查看本人授课的课程");
        }
    }
}
