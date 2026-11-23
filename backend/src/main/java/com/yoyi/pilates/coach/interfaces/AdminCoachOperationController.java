package com.yoyi.pilates.coach.interfaces;

import com.yoyi.pilates.coach.application.CoachLeaveService;
import com.yoyi.pilates.coach.application.ScheduleChangeRequestService;
import com.yoyi.pilates.coach.infrastructure.entity.CoachLeaveEntity;
import com.yoyi.pilates.coach.infrastructure.entity.ScheduleChangeRequestEntity;
import com.yoyi.pilates.common.annotation.OpLog;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.system.security.RequiresPerm;
import com.yoyi.pilates.system.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 请假/调课审批接口(管理员/店长).
 */
@RestController
@RequestMapping({"/admin", "/api/admin"})
@RequiredArgsConstructor
public class AdminCoachOperationController {

    private final CoachLeaveService leaveService;
    private final ScheduleChangeRequestService changeRequestService;

    // ---------------- 请假审批 ----------------

    @GetMapping("/leaves")
    @RequiresPerm("member:update")
    public Result<List<CoachLeaveEntity>> pendingLeaves() {
        return Result.ok(leaveService.pendingLeaves());
    }

    @PostMapping("/leaves/{id}/approve")
    @RequiresPerm("member:update")
    @OpLog(module = "coach", action = "leave_approve", targetId = "#id")
    public Result<Void> approveLeave(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean approve,
                                     @RequestParam(required = false) String rejectReason) {
        leaveService.approve(id, currentId(), approve, rejectReason);
        return Result.ok();
    }

    // ---------------- 调课审批 ----------------

    @GetMapping("/change-requests")
    @RequiresPerm("schedule:update")
    public Result<List<ScheduleChangeRequestEntity>> pendingChangeRequests() {
        return Result.ok(changeRequestService.pendingRequests());
    }

    @GetMapping("/change-requests/{id}/preview")
    @RequiresPerm("schedule:update")
    public Result<ScheduleChangeRequestService.Preview> previewChange(@PathVariable Long id) {
        return Result.ok(changeRequestService.preview(id));
    }

    @PostMapping("/change-requests/{id}/approve")
    @RequiresPerm("schedule:update")
    @OpLog(module = "schedule", action = "change_approve", targetId = "#id")
    public Result<Void> approveChange(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean approve,
                                      @RequestParam(required = false) String rejectReason,
                                      @RequestParam(required = false) Integer scheduleVersion,
                                      @RequestParam(required = false) Integer affectedCount) {
        changeRequestService.approve(id, currentId(), approve, rejectReason, scheduleVersion, affectedCount);
        return Result.ok();
    }

    private Long currentId() {
        return SecurityUtils.current().map(u -> u.getId()).orElse(null);
    }
}
