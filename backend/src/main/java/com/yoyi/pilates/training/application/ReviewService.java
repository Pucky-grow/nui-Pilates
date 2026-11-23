package com.yoyi.pilates.training.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoyi.pilates.booking.infrastructure.entity.BookingEntity;
import com.yoyi.pilates.booking.infrastructure.mapper.BookingMapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.entity.CoachEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CoachMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.infra.mq.DomainEvent;
import com.yoyi.pilates.infra.mq.EventPublisher;
import com.yoyi.pilates.infra.mq.Topics;
import com.yoyi.pilates.training.infrastructure.entity.CourseReviewEntity;
import com.yoyi.pilates.training.infrastructure.mapper.CourseReviewMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * 课程评价服务: 完成课程后评价(booking 唯一约束防重复),
 * 评价后重算教练评分, 发事件送积分.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final CourseReviewMapper reviewMapper;
    private final BookingMapper bookingMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final CoachMapper coachMapper;
    private final EventPublisher eventPublisher;

    @Transactional(rollbackFor = Exception.class)
    public Long createReview(Long userId, Long bookingId, Integer courseScore, Integer coachScore,
                             Integer envScore, String content, String images, String tags) {
        BookingEntity booking = bookingMapper.selectById(bookingId);
        if (booking == null || !booking.getUserId().equals(userId)) {
            throw BusinessException.of(ResultCode.BOOKING_NOT_FOUND);
        }
        if (!"COMPLETED".equals(booking.getStatus())) {
            throw BusinessException.of(ResultCode.BOOKING_STATUS_ILLEGAL, "课程完成后才能评价");
        }
        validateScore(courseScore);
        validateScore(coachScore);
        validateScore(envScore);

        CourseScheduleEntity schedule = scheduleMapper.selectById(booking.getScheduleId());
        CourseReviewEntity review = new CourseReviewEntity();
        review.setBookingId(bookingId);
        review.setUserId(userId);
        review.setScheduleId(booking.getScheduleId());
        review.setCourseId(schedule.getCourseId());
        review.setCoachId(schedule.getCoachId());
        review.setCourseScore(courseScore);
        review.setCoachScore(coachScore);
        review.setEnvScore(envScore);
        review.setContent(content);
        review.setImages(images);
        review.setTags(tags);
        review.setStatus("VISIBLE");
        try {
            reviewMapper.insert(review);
        } catch (DuplicateKeyException e) {
            throw BusinessException.of(ResultCode.CONFLICT, "该课程已评价过");
        }

        recalcCoachRating(schedule.getCoachId());

        eventPublisher.publish(Topics.TRAINING_EVENT, DomainEvent.of(
                "REVIEW_CREATED", String.valueOf(review.getId()),
                Map.of("reviewId", review.getId(), "userId", userId, "bookingId", bookingId)));
        return review.getId();
    }

    public Page<CourseReviewEntity> pageByCourse(Long courseId, long page, long size) {
        return reviewMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<CourseReviewEntity>()
                        .eq(CourseReviewEntity::getCourseId, courseId)
                        .eq(CourseReviewEntity::getStatus, "VISIBLE")
                        .orderByDesc(CourseReviewEntity::getId));
    }

    public Page<CourseReviewEntity> pageByCoach(Long coachId, long page, long size) {
        return reviewMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<CourseReviewEntity>()
                        .eq(CourseReviewEntity::getCoachId, coachId)
                        .eq(CourseReviewEntity::getStatus, "VISIBLE")
                        .orderByDesc(CourseReviewEntity::getId));
    }

    public Page<CourseReviewEntity> pageMine(Long userId, long page, long size) {
        return reviewMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<CourseReviewEntity>()
                        .eq(CourseReviewEntity::getUserId, userId)
                        .orderByDesc(CourseReviewEntity::getId));
    }

    /** 重算教练评分(全部可见评价的 coach_score 均值) */
    private void recalcCoachRating(Long coachId) {
        var reviews = reviewMapper.selectList(new LambdaQueryWrapper<CourseReviewEntity>()
                .eq(CourseReviewEntity::getCoachId, coachId)
                .eq(CourseReviewEntity::getStatus, "VISIBLE"));
        if (reviews.isEmpty()) {
            return;
        }
        double avg = reviews.stream().mapToInt(CourseReviewEntity::getCoachScore).average().orElse(5.0);
        CoachEntity coach = coachMapper.selectById(coachId);
        if (coach != null) {
            coach.setRating(BigDecimal.valueOf(avg).setScale(1, RoundingMode.HALF_UP));
            coachMapper.updateById(coach);
        }
    }

    private void validateScore(Integer score) {
        if (score == null || score < 1 || score > 5) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "评分须为1-5分");
        }
    }
}
