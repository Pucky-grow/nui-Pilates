package com.yoyi.pilates.training.interfaces;

import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import com.yoyi.pilates.coach.application.CoachStudentService;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.PageResult;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.system.security.LoginUser;
import com.yoyi.pilates.system.security.SecurityUtils;
import com.yoyi.pilates.training.application.AssessmentService;
import com.yoyi.pilates.training.application.TrainingService;
import com.yoyi.pilates.training.infrastructure.entity.BodyAssessmentEntity;
import com.yoyi.pilates.training.infrastructure.entity.TrainingRecordEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 教练端训练接口: 待填记录工作台 / 填写训练记录 / 学员历史 / 创建身体评估.
 */
@RestController
@RequestMapping("/api/coach-end")
@RequiredArgsConstructor
public class CoachTrainingController {

    private final TrainingService trainingService;
    private final AssessmentService assessmentService;
    private final CoachStudentService studentService;

    /** 待填写训练记录的签到学员 */
    @GetMapping("/pending-records")
    public Result<List<BookingEntity>> pendingRecords() {
        LoginUser coach = requireCoach();
        return Result.ok(trainingService.pendingRecords(coach.getCoachId()));
    }

    /** 填写训练记录 */
    @PostMapping("/training-records")
    public Result<Map<String, Long>> createRecord(@RequestBody TrainingRecordEntity record) {
        LoginUser coach = requireCoach();
        Long id = trainingService.createRecord(coach.getCoachId(), coach.isAdminRole(), record);
        return Result.ok(Map.of("id", id));
    }

    /** 某学员在我这里的训练记录 */
    @GetMapping("/students/{userId}/training-records")
    public Result<PageResult<TrainingRecordEntity>> studentRecords(@PathVariable Long userId,
                                                                   @RequestParam(defaultValue = "1") long page,
                                                                   @RequestParam(defaultValue = "10") long size) {
        LoginUser coach = requireCoach();
        checkStudentScope(coach, userId);
        return Result.ok(PageResult.of(
                trainingService.pageStudentRecords(coach.getCoachId(), userId, page, size), r -> r));
    }

    /** 为学员创建身体评估 */
    @PostMapping("/students/{userId}/assessments")
    public Result<Map<String, Long>> createAssessment(@PathVariable Long userId,
                                                      @RequestBody BodyAssessmentEntity assessment) {
        LoginUser coach = requireCoach();
        checkStudentScope(coach, userId);
        if (!coach.isAdminRole()) studentService.requireAccess(coach.getCoachId(), userId, true);
        Long id = assessmentService.createAssessment(userId, coach.getCoachId(), assessment);
        return Result.ok(Map.of("id", id));
    }

    /** 查看学员评估列表 */
    @GetMapping("/students/{userId}/assessments")
    public Result<List<BodyAssessmentEntity>> studentAssessments(@PathVariable Long userId) {
        LoginUser coach = requireCoach();
        checkStudentScope(coach, userId);
        if (!coach.isAdminRole()) studentService.requireAccess(coach.getCoachId(), userId, true);
        return Result.ok(assessmentService.myAssessments(userId));
    }

    /** 数据权限: 非管理员只能操作自己服务过的学员(复用 CoachStudentService.hasServed) */
    private void checkStudentScope(LoginUser coach, Long userId) {
        if (!coach.isAdminRole() && !studentService.hasServed(coach.getCoachId(), userId)) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "只能操作自己服务过的学员");
        }
    }

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
}
