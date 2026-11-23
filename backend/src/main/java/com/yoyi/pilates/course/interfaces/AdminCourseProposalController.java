package com.yoyi.pilates.course.interfaces;

import com.yoyi.pilates.common.annotation.OpLog;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.course.application.CourseCoachService;
import com.yoyi.pilates.course.application.CourseProposalService;
import com.yoyi.pilates.course.infrastructure.entity.CourseCoachEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseProposalEntity;
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
 * 课程申请审核 / 授课教练管理(店长/管理员).
 */
@RestController
@RequestMapping({"/admin", "/api/admin"})
@RequiredArgsConstructor
public class AdminCourseProposalController {

    private final CourseProposalService proposalService;
    private final CourseCoachService courseCoachService;

    // ---------------- 课程方案审核 ----------------

    @GetMapping("/course-proposals")
    @RequiresPerm("manager:course:proposal:view")
    public Result<List<CourseProposalEntity>> proposals(@RequestParam(required = false) String status) {
        if ("PENDING_REVIEW".equals(status)) {
            return Result.ok(proposalService.pendingProposals());
        }
        return Result.ok(proposalService.allProposals());
    }

    @GetMapping("/course-proposals/{id}")
    @RequiresPerm("manager:course:proposal:view")
    public Result<CourseProposalEntity> proposalDetail(@PathVariable Long id) {
        return Result.ok(proposalService.detail(null, id));
    }

    @PostMapping("/course-proposals/{id}/approve")
    @RequiresPerm("manager:course:proposal:approve")
    @OpLog(module = "course", action = "proposal_approve", targetId = "#id")
    public Result<Void> approve(@PathVariable Long id) {
        proposalService.approve(id, currentId());
        return Result.ok();
    }

    @PostMapping("/course-proposals/{id}/reject")
    @RequiresPerm("manager:course:proposal:reject")
    @OpLog(module = "course", action = "proposal_reject", targetId = "#id", detail = "#reason")
    public Result<Void> reject(@PathVariable Long id, @RequestParam String reason) {
        proposalService.reject(id, currentId(), reason);
        return Result.ok();
    }

    // ---------------- 授课教练管理 ----------------

    @GetMapping("/courses/{id}/coaches")
    @RequiresPerm("manager:course:coach:manage")
    public Result<List<CourseCoachEntity>> courseCoaches(@PathVariable Long id) {
        return Result.ok(courseCoachService.coachesOfCourse(id));
    }

    @PostMapping("/courses/{id}/coaches/{coachId}/approve")
    @RequiresPerm("manager:course:coach:manage")
    @OpLog(module = "course", action = "coach_qualification_approve", detail = "#id + ':' + #coachId")
    public Result<Void> approveCoach(@PathVariable Long id, @PathVariable Long coachId) {
        courseCoachService.approveApplication(id, coachId, currentId(), true);
        return Result.ok();
    }

    @PostMapping("/courses/{id}/coaches/{coachId}/disable")
    @RequiresPerm("manager:course:coach:manage")
    @OpLog(module = "course", action = "coach_qualification_disable", detail = "#id + ':' + #coachId")
    public Result<Void> disableCoach(@PathVariable Long id, @PathVariable Long coachId) {
        courseCoachService.disable(id, coachId);
        return Result.ok();
    }

    @org.springframework.web.bind.annotation.PutMapping("/courses/{id}/coaches/{coachId}")
    @RequiresPerm("manager:course:coach:manage")
    @OpLog(module = "course", action = "assign_qualification")
    public Result<Void> assignCoach(@PathVariable Long id, @PathVariable Long coachId) {
        courseCoachService.assign(id, coachId, currentId()); return Result.ok();
    }

    private Long currentId() {
        return SecurityUtils.current().map(u -> u.getId()).orElse(null);
    }
}
