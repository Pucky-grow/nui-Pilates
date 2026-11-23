package com.yoyi.pilates.booking.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.booking.infrastructure.entity.WaitlistEntity;
import com.yoyi.pilates.booking.infrastructure.mapper.WaitlistMapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import com.yoyi.pilates.infra.mq.DomainEvent;
import com.yoyi.pilates.infra.mq.EventPublisher;
import com.yoyi.pilates.infra.mq.Topics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 候补队列服务: 满员加入候补, 取消后自动转正.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WaitlistService {

    private final WaitlistMapper waitlistMapper;
    private final CourseScheduleMapper scheduleMapper;
    private final BookingService bookingService;
    private final EventPublisher eventPublisher;
    private final com.yoyi.pilates.course.application.BookingPolicy bookingPolicy;

    /**
     * 加入候补(课程满员时).
     */
    @org.springframework.beans.factory.annotation.Value("${yoyi.waitlist.enabled:false}") private boolean enabled;

    @Transactional(rollbackFor = Exception.class)
    public Long join(Long userId, Long scheduleId) {
        if(!enabled) throw BusinessException.of(ResultCode.FORBIDDEN,"当前未开放候补，请刷新课表直接预约");
        CourseScheduleEntity schedule = scheduleMapper.selectById(scheduleId);
        if (schedule == null || !"SCHEDULED".equals(schedule.getStatus())) {
            throw BusinessException.of(ResultCode.SCHEDULE_NOT_FOUND);
        }
        var availability = bookingPolicy.evaluate(schedule);
        if (!"FULL".equals(availability.code())) {
            throw BusinessException.of(ResultCode.CONFLICT, availability.bookable() ? "课程还有名额，请直接预约" : availability.reason());
        }
        int rank = waitlistMapper.selectCount(new LambdaQueryWrapper<WaitlistEntity>()
                .eq(WaitlistEntity::getScheduleId, scheduleId)
                .eq(WaitlistEntity::getStatus, "WAITING")).intValue() + 1;
        WaitlistEntity waitlist = new WaitlistEntity();
        waitlist.setScheduleId(scheduleId);
        waitlist.setUserId(userId);
        waitlist.setRank(rank);
        waitlist.setStatus("WAITING");
        try {
            waitlistMapper.insert(waitlist);
        } catch (DuplicateKeyException e) {
            throw BusinessException.of(ResultCode.CONFLICT, "您已在候补队列中");
        }
        return waitlist.getId();
    }

    /** 用户退出候补 */
    @Transactional(rollbackFor = Exception.class)
    public void leave(Long userId, Long scheduleId) {
        WaitlistEntity waitlist = waitlistMapper.selectOne(new LambdaQueryWrapper<WaitlistEntity>()
                .eq(WaitlistEntity::getScheduleId, scheduleId)
                .eq(WaitlistEntity::getUserId, userId)
                .eq(WaitlistEntity::getStatus, "WAITING"));
        if (waitlist != null) {
            waitlist.setStatus("CANCELLED");
            waitlistMapper.updateById(waitlist);
        }
    }

    /** 我的候补 */
    public List<WaitlistEntity> myWaitlist(Long userId) {
        return waitlistMapper.selectList(new LambdaQueryWrapper<WaitlistEntity>()
                .eq(WaitlistEntity::getUserId, userId)
                .in(WaitlistEntity::getStatus, List.of("WAITING", "CONVERTED"))
                .orderByDesc(WaitlistEntity::getId));
    }

    /**
     * 有人取消后自动转正: 队首候补直接转为预约(扣课时).
     * 由 BOOKING_CANCELLED 事件消费者调用(取消事务之外, 避免死锁).
     */
    public void promote(Long scheduleId) {
        WaitlistEntity first = waitlistMapper.selectOne(new LambdaQueryWrapper<WaitlistEntity>()
                .eq(WaitlistEntity::getScheduleId, scheduleId)
                .eq(WaitlistEntity::getStatus, "WAITING")
                .orderByAsc(WaitlistEntity::getRank)
                .orderByAsc(WaitlistEntity::getId)
                .last("LIMIT 1"));
        if (first == null) {
            return;
        }
        try {
            Long bookingId = bookingService.book(first.getUserId(), scheduleId, null, null);
            first.setStatus("CONVERTED");
            first.setConvertedAt(LocalDateTime.now());
            waitlistMapper.updateById(first);
            log.info("候补转正成功: userId={} scheduleId={} bookingId={}",
                    first.getUserId(), scheduleId, bookingId);
            eventPublisher.publish(Topics.BOOKING_EVENT, DomainEvent.of(
                    "WAITLIST_CONVERTED", String.valueOf(first.getId()),
                    Map.of("userId", first.getUserId(), "scheduleId", scheduleId)));
        } catch (BusinessException e) {
            // 转正失败(课时不足/已预约等), 标记失效
            first.setStatus("EXPIRED");
            waitlistMapper.updateById(first);
            log.info("候补转正失败, 已失效: userId={} scheduleId={} 原因={}",
                    first.getUserId(), scheduleId, e.getMessage());
        }
    }
}
