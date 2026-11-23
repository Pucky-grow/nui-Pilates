package com.yoyi.pilates.course.interfaces;

import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.course.application.CourseCoachService;
import com.yoyi.pilates.course.application.CourseProposalService;
import com.yoyi.pilates.course.application.CourseQueryService;
import com.yoyi.pilates.course.application.ScheduleService;
import com.yoyi.pilates.course.infrastructure.entity.CourseEntity;
import com.yoyi.pilates.course.infrastructure.entity.CourseProposalEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.interfaces.dto.CourseView;
import com.yoyi.pilates.system.security.LoginUser;
import com.yoyi.pilates.system.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
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
 * 教练课程中心接口: 查看课程 / 我的可授课课程 / 课程方案 / 申请授课资格.
 */
@RestController
@RequestMapping("/api/coach-end")
@RequiredArgsConstructor
public class CoachCourseController {

    private final CourseQueryService courseQueryService;
    private final CourseCoachService courseCoachService;
    private final CourseProposalService proposalService;
    private final CourseMapper courseMapper;
    private final ScheduleService scheduleService;

    /** 工作室全部开放课程 */
    @GetMapping("/courses")
    public Result<List<CourseView>> courses() {
        return Result.ok(courseQueryService.listCourses(null, null, null));
    }

    /** 我可授课/申请中的课程 */
    @GetMapping("/courses/my")
    public Result<List<Map<String, Object>>> myCourses() {
        return Result.ok(courseCoachService.myCourses(requireCoach("coach:course:view").getCoachId()));
    }

    /** 我的课程方案列表 */
    @GetMapping("/course-proposals")
    public Result<List<CourseProposalEntity>> myProposals() {
        return Result.ok(proposalService.myProposals(requireCoach("coach:course:view").getCoachId()));
    }

    @GetMapping("/course-proposals/{id}")
    public Result<CourseProposalEntity> proposalDetail(@PathVariable Long id) {
        return Result.ok(proposalService.detail(requireCoach("coach:course:view").getCoachId(), id));
    }

    /** 创建课程方案(草稿) */
    @PostMapping("/course-proposals")
    public Result<Long> createProposal(@RequestBody CourseProposalEntity proposal) {
        return Result.ok(proposalService.create(requireCoach("coach:course:proposal:create").getCoachId(), proposal));
    }

    /** 修改草稿/被驳回的方案 */
    @PutMapping("/course-proposals/{id}")
    public Result<Void> updateProposal(@PathVariable Long id, @RequestBody CourseProposalEntity proposal) {
        proposalService.update(requireCoach("coach:course:proposal:update").getCoachId(), id, proposal);
        return Result.ok();
    }

    /** 提交审核 */
    @PostMapping("/course-proposals/{id}/submit")
    public Result<Void> submitProposal(@PathVariable Long id) {
        proposalService.submit(requireCoach("coach:course:proposal:submit").getCoachId(), id);
        return Result.ok();
    }

    /** 申请成为某正式课程的授课教练 */
    @PostMapping("/courses/{id}/apply")
    public Result<Long> applyCourse(@PathVariable Long id) {
        return Result.ok(courseCoachService.applyToCourse(requireCoach("coach:course:apply").getCoachId(), id));
    }

    /**
     * 教练编辑自己负责课程的运营展示信息(封面/介绍/难度/适合人群/注意事项等),
     * 不可改价格/状态/删除.
     */
    @PutMapping("/courses/{id}/display")
    public Result<Void> updateCourseDisplay(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long coachId = requireCoach("coach:course:update").getCoachId();
        if (!courseCoachService.hasQualification(id, coachId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "只能编辑自己负责的课程");
        }
        CourseEntity course = courseMapper.selectById(id);
        if (course == null) {
            throw BusinessException.of(ResultCode.COURSE_NOT_FOUND);
        }
        // 仅允许修改运营展示字段, 禁止价格/状态
        if (body.get("cover") != null) course.setCover((String) body.get("cover"));
        if (body.get("description") != null) course.setDescription((String) body.get("description"));
        if (body.get("difficulty") != null) course.setDifficulty((String) body.get("difficulty"));
        if (body.get("targetUsers") != null) course.setTargetUsers((String) body.get("targetUsers"));
        if (body.get("trainingFocus") != null) course.setTrainingFocus((String) body.get("trainingFocus"));
        if (body.get("notices") != null) course.setNotices((String) body.get("notices"));
        courseMapper.updateById(course);
        return Result.ok();
    }

    /** 教练创建排课(归属强制本人 + 需授课资格) */
    @PostMapping("/schedules")
    public Result<Long> createSchedule(@jakarta.validation.Valid @RequestBody com.yoyi.pilates.course.interfaces.dto.ScheduleCreateRequest request) {
        if (!requireCoach().hasPerm("coach:schedule:create")) throw BusinessException.of(ResultCode.FORBIDDEN, "没有创建排课权限");
        return Result.ok(scheduleService.create(request));
    }

    /** 教练取消自己的排课 */
    @PostMapping("/schedules/{id}/cancel")
    public Result<Void> cancelSchedule(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, String> body) {
        if (!requireCoach().hasPerm("coach:schedule:cancel")) throw BusinessException.of(ResultCode.FORBIDDEN, "没有取消排课权限");
        scheduleService.cancel(id, body == null ? null : body.get("reason"));
        return Result.ok();
    }

    private LoginUser requireCoach(String permission) {
        LoginUser actor = requireCoach();
        if (!actor.hasPerm(permission)) throw BusinessException.of(ResultCode.FORBIDDEN, "没有此项教练权限");
        return actor;
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
