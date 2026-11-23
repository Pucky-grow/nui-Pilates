package com.yoyi.pilates.course.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.course.infrastructure.entity.*;
import com.yoyi.pilates.course.infrastructure.mapper.*;
import com.yoyi.pilates.system.application.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.Map;

/** 公共可约条件；不包含会员重复预约、每日限额等个人限制。 */
@Service
@RequiredArgsConstructor
public class BookingPolicy {
    private final CourseMapper courses;
    private final CoachMapper coaches;
    private final ClassroomMapper rooms;
    private final CourseCoachMapper qualifications;
    private final SystemConfigService config;
    public record Decision(boolean bookable, String code, String reason) { }
    private Decision no(String code,String reason) { return new Decision(false,code,reason); }
    public int advanceDays() { return Math.max(0,config.getInt("booking.advance_days",7)); }
    public String mode() { return config.get("booking.paymentMode","FREE"); }
    public Map<String,Object> rules() {
        return Map.of("bookingMode",mode(),"advanceDays",advanceDays(),"deadlineMinutes",config.getInt("booking.deadline_minutes",30),
                "waitlistEnabled", waitlistEnabled,
                "serverDate",StudioTime.today().toString(),"timezone","Asia/Shanghai");
    }
    public Decision evaluate(CourseScheduleEntity s) {
        if (s == null) return no("NOT_FOUND","排课不存在");
        return evaluate(s,courses.selectById(s.getCourseId()),coaches.selectById(s.getCoachId()),rooms.selectById(s.getClassroomId()));
    }
    public Decision evaluate(CourseScheduleEntity s,CourseEntity course,CoachEntity coach,ClassroomEntity room) {
        LocalDateTime now=StudioTime.now();
        if ("CANCELLED".equals(s.getStatus())) return no("CANCELLED","排课已取消");
        if ("FINISHED".equals(s.getStatus()) || !now.isBefore(LocalDateTime.of(s.getCourseDate(),s.getEndTime()))) return no("ENDED","课程已结束");
        if (!"SCHEDULED".equals(s.getStatus())) return no("UNAVAILABLE","排课状态不可约");
        if (s.getCourseDate().isAfter(StudioTime.today().plusDays(advanceDays()))) return no("NOT_OPEN","尚未开放预约");
        if (!now.isBefore(LocalDateTime.of(s.getCourseDate(),s.getStartTime()).minusMinutes(config.getInt("booking.deadline_minutes",30)))) return no("DEADLINE","已过预约截止时间");
        if (course==null || !"ON_SHELF".equals(course.getStatus())) return no("COURSE_OFF_SHELF","课程已下架，暂停新增预约");
        if (coach==null || !"ACTIVE".equals(coach.getStatus())) return no("COACH_DISABLED","教练已停用，暂停新增预约");
        if (room==null || !"ACTIVE".equals(room.getStatus())) return no("ROOM_DISABLED","教室已停用，暂停新增预约");
        if (!qualifications.exists(new LambdaQueryWrapper<CourseCoachEntity>().eq(CourseCoachEntity::getCourseId,s.getCourseId())
                .eq(CourseCoachEntity::getCoachId,s.getCoachId()).eq(CourseCoachEntity::getStatus,"APPROVED"))) return no("QUALIFICATION_DISABLED","授课资格未生效，暂停新增预约");
        if (s.getBookedCount() >= capacity(s, course)) return no("FULL","预约名额已满");
        return new Decision(true,"AVAILABLE", "可预约");
    }

    @org.springframework.beans.factory.annotation.Value("${yoyi.waitlist.enabled:false}")
    private boolean waitlistEnabled;

    public static int capacity(CourseScheduleEntity schedule, CourseEntity course) {
        return course != null && "PRIVATE".equals(course.getType()) ? 1 : schedule.getMaxCapacity();
    }
    public void requireBookable(CourseScheduleEntity schedule) {
        Decision d=evaluate(schedule);
        if (!d.bookable()) {
            ResultCode code = switch (d.code()) {
                case "FULL" -> ResultCode.SCHEDULE_FULL;
                case "NOT_FOUND", "CANCELLED" -> ResultCode.SCHEDULE_NOT_FOUND;
                case "ENDED" -> ResultCode.SCHEDULE_FINISHED;
                case "DEADLINE" -> ResultCode.BOOKING_DEADLINE_PASSED;
                default -> ResultCode.CONFLICT;
            };
            throw BusinessException.of(code,d.reason());
        }
    }
}
