package com.yoyi.pilates.course.interfaces;

import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.course.application.CourseQueryService;
import com.yoyi.pilates.course.infrastructure.entity.CourseCategoryEntity;
import com.yoyi.pilates.course.infrastructure.entity.ClassroomEntity;
import com.yoyi.pilates.course.interfaces.dto.CoachView;
import com.yoyi.pilates.course.interfaces.dto.CourseView;
import com.yoyi.pilates.course.interfaces.dto.ScheduleView;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 课程公开查询接口(小程序游客可浏览).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CoursePublicController {

    private final CourseQueryService courseQueryService;
    private final com.yoyi.pilates.course.application.BookingPolicy bookingPolicy;
    @GetMapping("/booking-rules")
    public Result<java.util.Map<String,Object>> bookingRules() { return Result.ok(bookingPolicy.rules()); }

    @GetMapping("/course-categories")
    public Result<List<CourseCategoryEntity>> categories() {
        return Result.ok(courseQueryService.listActiveCategories());
    }

    /** 教室列表(创建排课选择) */
    @GetMapping("/classrooms")
    public Result<List<ClassroomEntity>> classrooms() {
        return Result.ok(courseQueryService.listActiveClassrooms());
    }

    @GetMapping("/courses")
    public Result<List<CourseView>> courses(@RequestParam(required = false) Long categoryId,
                                            @RequestParam(required = false) String type,
                                            @RequestParam(required = false) String keyword) {
        return Result.ok(courseQueryService.listCourses(categoryId, type, keyword));
    }

    @GetMapping("/courses/{id}")
    public Result<CourseView> courseDetail(@PathVariable Long id) {
        return Result.ok(courseQueryService.courseDetail(id));
    }

    /** 课程详情页: 该课程未来7天排期 */
    @GetMapping("/courses/{id}/schedules")
    public Result<List<ScheduleView>> courseSchedules(@PathVariable Long id) {
        return Result.ok(courseQueryService.schedulesOfCourse(id));
    }

    @GetMapping("/coaches")
    public Result<List<CoachView>> coaches() {
        return Result.ok(courseQueryService.listCoaches());
    }

    @GetMapping("/coaches/{id}")
    public Result<CoachView> coachDetail(@PathVariable Long id) {
        return Result.ok(courseQueryService.coachDetail(id));
    }

    /** 教练的可约排期(私教预约选时间) */
    @GetMapping("/coaches/{id}/schedules")
    public Result<List<ScheduleView>> coachSchedules(@PathVariable Long id) {
        return Result.ok(courseQueryService.listSchedules(null, null, null, id, null, null, null, false));
    }

    /**
     * 排课列表(课程 Tab 课程卡片流).
     *
     * @param date      指定日期(默认今天起)
     * @param days      跨度天数(默认7)
     * @param timeOfDay MORNING/AFTERNOON/EVENING
     */
    @GetMapping("/course-schedules")
    public Result<List<ScheduleView>> schedules(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) String timeOfDay,
            @RequestParam(required = false) Long coachId,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String difficulty,
            @RequestParam(required = false) String courseType,
            @RequestParam(defaultValue = "false") boolean onlyAvailable) {
        if (days < 1 || days > 366) throw com.yoyi.pilates.common.exception.BusinessException.of(com.yoyi.pilates.common.result.ResultCode.BAD_REQUEST, "日期跨度须为1至366天");
        LocalDate from = date != null ? date : com.yoyi.pilates.common.util.StudioTime.today();
        return Result.ok(courseQueryService.listSchedules(from, from.plusDays(days - 1),
                timeOfDay, coachId, categoryId, difficulty, courseType, onlyAvailable));
    }

    @GetMapping("/course-schedules/{id}")
    public Result<ScheduleView> scheduleDetail(@PathVariable Long id) {
        return Result.ok(courseQueryService.scheduleDetail(id));
    }
}
