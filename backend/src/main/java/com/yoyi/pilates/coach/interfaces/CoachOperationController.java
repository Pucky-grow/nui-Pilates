package com.yoyi.pilates.coach.interfaces;

import com.yoyi.pilates.coach.application.CoachDashboardService;
import com.yoyi.pilates.coach.application.CoachLeaveService;
import com.yoyi.pilates.coach.application.CoachStudentService;
import com.yoyi.pilates.coach.application.ScheduleChangeRequestService;
import com.yoyi.pilates.coach.infrastructure.entity.CoachLeaveEntity;
import com.yoyi.pilates.coach.infrastructure.entity.ScheduleChangeRequestEntity;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.system.security.LoginUser;
import com.yoyi.pilates.system.security.SecurityUtils;
import com.yoyi.pilates.training.application.TrainingTemplateService;
import com.yoyi.pilates.training.infrastructure.entity.TrainingRecordTemplateEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 教练端二期接口: 工作台概览/请假/调课申请/训练模板/学员档案.
 * 数据权限: 全部以当前登录教练 coachId 为准(后端强制).
 */
@RestController
@RequestMapping("/api/coach-end")
@RequiredArgsConstructor
public class CoachOperationController {

    private final CoachDashboardService dashboardService;
    private final CoachLeaveService leaveService;
    private final ScheduleChangeRequestService changeRequestService;
    private final TrainingTemplateService templateService;
    private final CoachStudentService studentService;

    // ---------------- 工作台概览 ----------------

    @GetMapping("/dashboard")
    public Result<Map<String, Object>> dashboard() {
        return Result.ok(dashboardService.dashboard(requireCoach().getCoachId()));
    }

    // ---------------- 请假 ----------------

    @PostMapping("/leaves")
    public Result<Long> submitLeave(@RequestBody CoachLeaveEntity leave) {
        return Result.ok(leaveService.submit(requireCoach().getCoachId(), leave));
    }

    @GetMapping("/leaves")
    public Result<List<CoachLeaveEntity>> myLeaves() {
        return Result.ok(leaveService.myLeaves(requireCoach().getCoachId()));
    }

    @PostMapping("/leaves/{id}/cancel")
    public Result<Void> cancelLeave(@PathVariable Long id) {
        leaveService.cancel(requireCoach().getCoachId(), id);
        return Result.ok();
    }

    // ---------------- 调课申请 ----------------

    @PostMapping("/change-requests")
    public Result<Long> submitChange(@RequestBody ScheduleChangeRequestEntity request) {
        return Result.ok(changeRequestService.submit(requireCoach().getCoachId(), request));
    }

    @GetMapping("/change-requests")
    public Result<List<ScheduleChangeRequestEntity>> myChangeRequests() {
        return Result.ok(changeRequestService.myRequests(requireCoach().getCoachId()));
    }

    // ---------------- 训练模板 ----------------

    @GetMapping("/training-templates")
    public Result<List<TrainingRecordTemplateEntity>> templates() {
        return Result.ok(templateService.myTemplates(requireCoach().getCoachId()));
    }

    @PostMapping("/training-templates")
    public Result<Long> createTemplate(@RequestBody TrainingRecordTemplateEntity template) {
        return Result.ok(templateService.create(requireCoach().getCoachId(), template));
    }

    @PutMapping("/training-templates/{id}")
    public Result<Void> updateTemplate(@PathVariable Long id, @RequestBody TrainingRecordTemplateEntity template) {
        templateService.update(requireCoach().getCoachId(), id, template);
        return Result.ok();
    }

    @DeleteMapping("/training-templates/{id}")
    public Result<Void> deleteTemplate(@PathVariable Long id) {
        templateService.delete(requireCoach().getCoachId(), id);
        return Result.ok();
    }

    // ---------------- 学员档案 ----------------

    @GetMapping("/students/{userId}")
    public Result<Map<String, Object>> studentDetail(@PathVariable Long userId) {
        return Result.ok(studentService.studentDetail(requireCoach().getCoachId(), userId));
    }

    private LoginUser requireCoach() {
        LoginUser user = SecurityUtils.requireUser();
        if (!user.isCoach() && !user.isAdminRole()) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "仅教练账号可访问");
        }
        if (user.getCoachId() == null) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "账号未关联教练档案");
        }
        return user;
    }
}
