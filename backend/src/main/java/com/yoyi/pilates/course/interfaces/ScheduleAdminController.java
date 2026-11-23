package com.yoyi.pilates.course.interfaces;

import com.yoyi.pilates.common.annotation.OpLog;
import com.yoyi.pilates.common.result.PageResult;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.course.application.ScheduleService;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.interfaces.dto.RecurringScheduleRequest;
import com.yoyi.pilates.course.interfaces.dto.ScheduleCreateRequest;
import com.yoyi.pilates.system.security.RequiresPerm;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 排课管理接口(后台).
 */
@RestController
@RequestMapping({"/admin/schedules", "/api/admin/schedules"})
@RequiredArgsConstructor
public class ScheduleAdminController {

    private final ScheduleService scheduleService;
    private final com.yoyi.pilates.course.application.CourseQueryService queries;
    private final com.yoyi.pilates.course.application.CourseCoachService qualifications;
    @GetMapping("/options")
    @RequiresPerm("schedule:view")
    public Result<Map<String,Object>> options() {
        return Result.ok(Map.of("courses",queries.listCourses(null,null,null),"coaches",queries.listCoaches(),
                "classrooms",queries.listActiveClassrooms(),"qualifications",qualifications.approvedQualifications()));
    }

    @GetMapping
    @RequiresPerm("schedule:view")
    public Result<PageResult<com.yoyi.pilates.course.interfaces.dto.ScheduleView>> page(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) Long coachId,
            @RequestParam(required = false) Long courseId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size) {
        return Result.ok(PageResult.of(
                scheduleService.pageAdmin(fromDate, toDate, coachId, courseId, status, page, size), scheduleService::adminView));
    }

    @GetMapping("/{id}")
    @RequiresPerm("schedule:view")
    public Result<com.yoyi.pilates.course.interfaces.dto.ScheduleView> detail(@PathVariable Long id) {
        return Result.ok(scheduleService.adminView(scheduleService.requireSchedule(id)));
    }

    /** 单次排课 */
    @PostMapping
    @RequiresPerm("schedule:create")
    @OpLog(module = "schedule", action = "create", detail = "#request.courseDate + ' ' + #request.startTime")
    public Result<Long> create(@Valid @RequestBody ScheduleCreateRequest request) {
        return Result.ok(scheduleService.create(request));
    }

    /** 周期排课(每周固定) */
    @PostMapping("/recurring")
    @RequiresPerm("schedule:create")
    @OpLog(module = "schedule", action = "create_recurring",
            detail = "#request.fromDate + '~' + #request.toDate")
    public Result<Map<String, Integer>> createRecurring(@Valid @RequestBody RecurringScheduleRequest request) {
        int created = scheduleService.createRecurring(request);
        return Result.ok(Map.of("created", created));
    }

    @PutMapping("/{id}")
    @RequiresPerm("schedule:update")
    @OpLog(module = "schedule", action = "update", targetId = "#id")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ScheduleCreateRequest request) {
        scheduleService.update(id, request);
        return Result.ok();
    }

    /** 取消排课(自动批量退课, 见预约模块) */
    @PostMapping("/{id}/cancel")
    @RequiresPerm("schedule:cancel")
    @OpLog(module = "schedule", action = "cancel", targetId = "#id", detail = "#reason")
    public Result<Void> cancel(@PathVariable Long id, @RequestParam(required = false) String reason) {
        scheduleService.cancel(id, reason);
        return Result.ok();
    }
}
