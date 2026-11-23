package com.yoyi.pilates.booking.application;

import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.course.application.BookingPolicy;
import com.yoyi.pilates.course.infrastructure.entity.CourseScheduleEntity;
import com.yoyi.pilates.course.infrastructure.mapper.CourseMapper;
import com.yoyi.pilates.course.infrastructure.mapper.CourseScheduleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.redisson.api.RedissonClient;
import org.redisson.api.RLock;

/** 已提交数据库人数的缓存。校准也获取同一排课锁，不能覆盖正在提交的库存。 */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class StockService {

    private final StringRedisTemplate redisTemplate;
    private final CourseScheduleMapper scheduleMapper;
    private final RedissonClient redissonClient;
    private final CourseMapper courseMapper;

    /**
     * 预占一个名额.
     *
     * @return true=占位成功; false=已满
     */
    @Deprecated // 兼容旧调用；新预约不依赖 Redis 预占，最终使用数据库条件更新。
    public boolean tryOccupy(Long scheduleId) {
        String key = RedisKeys.stock(scheduleId);
        if (!Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            initFromDb(scheduleId);
        }
        Long remain = redisTemplate.opsForValue().decrement(key);
        if (remain != null && remain < 0) {
            redisTemplate.opsForValue().increment(key);
            return false;
        }
        return true;
    }

    /** 释放一个名额(取消/事务失败回补) */
    public void release(Long scheduleId) {
        String key = RedisKeys.stock(scheduleId);
        if (Boolean.TRUE.equals(redisTemplate.hasKey(key))) {
            redisTemplate.opsForValue().increment(key);
        }
    }

    /** 以 DB 为准重新校准库存 */
    public void initFromDb(Long scheduleId) {
        RLock lock = redissonClient.getLock(RedisKeys.bookingLock(scheduleId));
        lock.lock();
        try {
            CourseScheduleEntity schedule = scheduleMapper.selectById(scheduleId);
            if (schedule != null && "SCHEDULED".equals(schedule.getStatus())) {
                int capacity = BookingPolicy.capacity(schedule, courseMapper.selectById(schedule.getCourseId()));
                int remain = Math.max(0, capacity - schedule.getBookedCount());
                redisTemplate.opsForValue().set(RedisKeys.stock(scheduleId), String.valueOf(remain));
            } else redisTemplate.delete(RedisKeys.stock(scheduleId));
        } finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
    }

    public void evict(Long scheduleId) {
        redisTemplate.delete(RedisKeys.stock(scheduleId));
    }

    /** 按主键遍历，包含取消/结束课程以清理失败遗留的缓存；不清空 Redis。 */
    public int reconcileAll() {
        long after = 0;
        int checked = 0;
        while (true) {
            var page = scheduleMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CourseScheduleEntity>()
                    .gt(CourseScheduleEntity::getId, after).orderByAsc(CourseScheduleEntity::getId).last("LIMIT 500"));
            if (page.isEmpty()) return checked;
            for (var schedule : page) {
                try { initFromDb(schedule.getId()); checked++; }
                catch (RuntimeException error) { log.error("库存校准失败 scheduleId={}", schedule.getId(), error); }
                after = schedule.getId();
            }
        }
    }
}
