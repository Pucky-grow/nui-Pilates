package com.yoyi.pilates.training.interfaces;

import com.yoyi.pilates.common.result.PageResult;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.system.security.SecurityUtils;
import com.yoyi.pilates.training.application.AssessmentService;
import com.yoyi.pilates.training.application.ReviewService;
import com.yoyi.pilates.training.application.TrainingService;
import com.yoyi.pilates.training.infrastructure.entity.BodyAssessmentEntity;
import com.yoyi.pilates.training.infrastructure.entity.BodyMeasurementEntity;
import com.yoyi.pilates.training.infrastructure.entity.CourseReviewEntity;
import com.yoyi.pilates.training.infrastructure.entity.TrainingRecordEntity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
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
 * 训练/评估/评价接口(小程序用户端).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class TrainingController {

    private final TrainingService trainingService;
    private final AssessmentService assessmentService;
    private final ReviewService reviewService;

    // ---------------- 训练记录 ----------------

    @GetMapping("/training-records")
    public Result<PageResult<TrainingRecordEntity>> myRecords(@RequestParam(defaultValue = "1") long page,
                                                              @RequestParam(defaultValue = "10") long size) {
        return Result.ok(PageResult.of(
                trainingService.pageMyRecords(SecurityUtils.requireMemberId(), page, size), r -> r));
    }

    /** 训练统计图表数据 */
    @GetMapping("/training-records/stats")
    public Result<Map<String, Object>> stats() {
        return Result.ok(trainingService.stats(SecurityUtils.requireMemberId()));
    }

    // ---------------- 身体评估 ----------------

    @GetMapping("/assessments")
    public Result<List<BodyAssessmentEntity>> myAssessments() {
        return Result.ok(assessmentService.myAssessments(SecurityUtils.requireMemberId()));
    }

    @GetMapping("/assessments/{id}")
    public Result<BodyAssessmentEntity> assessmentDetail(@PathVariable Long id) {
        return Result.ok(assessmentService.assessmentDetail(SecurityUtils.requireMemberId(), id));
    }

    /** 围度/体重趋势 */
    @GetMapping("/measurements/trend")
    public Result<List<BodyMeasurementEntity>> measurementTrend() {
        return Result.ok(assessmentService.measurementTrend(SecurityUtils.requireMemberId()));
    }

    /** 自助记录一次身体数据 */
    @PostMapping("/measurements")
    public Result<Map<String, Long>> recordMeasurement(@RequestBody BodyMeasurementEntity measurement) {
        Long id = assessmentService.recordMeasurement(SecurityUtils.requireMemberId(), measurement);
        return Result.ok(Map.of("id", id));
    }

    // ---------------- 课程评价 ----------------

    @PostMapping("/reviews")
    public Result<Map<String, Long>> createReview(@Valid @RequestBody ReviewCreateRequest request) {
        Long id = reviewService.createReview(SecurityUtils.requireMemberId(), request.getBookingId(),
                request.getCourseScore(), request.getCoachScore(), request.getEnvScore(),
                request.getContent(), request.getImages(), request.getTags());
        return Result.ok(Map.of("reviewId", id));
    }

    @GetMapping("/my/reviews")
    public Result<PageResult<CourseReviewEntity>> myReviews(@RequestParam(defaultValue = "1") long page,
                                                            @RequestParam(defaultValue = "10") long size) {
        return Result.ok(PageResult.of(
                reviewService.pageMine(SecurityUtils.requireMemberId(), page, size), r -> r));
    }

    /** 课程的评价列表(公开) */
    @GetMapping("/courses/{courseId}/reviews")
    public Result<PageResult<CourseReviewEntity>> courseReviews(@PathVariable Long courseId,
                                                                @RequestParam(defaultValue = "1") long page,
                                                                @RequestParam(defaultValue = "10") long size) {
        return Result.ok(PageResult.of(reviewService.pageByCourse(courseId, page, size), r -> r));
    }

    /** 教练的评价列表(公开) */
    @GetMapping("/coaches/{coachId}/reviews")
    public Result<PageResult<CourseReviewEntity>> coachReviews(@PathVariable Long coachId,
                                                               @RequestParam(defaultValue = "1") long page,
                                                               @RequestParam(defaultValue = "10") long size) {
        return Result.ok(PageResult.of(reviewService.pageByCoach(coachId, page, size), r -> r));
    }

    /** 评价请求 */
    @Data
    public static class ReviewCreateRequest {
        @NotNull(message = "预约不能为空")
        private Long bookingId;
        @NotNull(message = "课程评分不能为空")
        private Integer courseScore;
        @NotNull(message = "教练评分不能为空")
        private Integer coachScore;
        @NotNull(message = "环境评分不能为空")
        private Integer envScore;
        private String content;
        private String images;
        private String tags;
    }
}
