package com.yoyi.pilates.marketing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.marketing.infrastructure.entity.PointsRecordEntity;
import com.yoyi.pilates.marketing.infrastructure.mapper.PointsRecordMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 积分服务: 余额 = 最新流水 balance_after; 增减按用户加锁串行, 业务键幂等.
 */
@Service
@RequiredArgsConstructor
public class PointsService {

    private final PointsRecordMapper pointsRecordMapper;
    private final RedissonClient redissonClient;

    public int balanceOf(Long userId) {
        PointsRecordEntity latest = pointsRecordMapper.selectOne(
                new LambdaQueryWrapper<PointsRecordEntity>()
                        .eq(PointsRecordEntity::getUserId, userId)
                        .orderByDesc(PointsRecordEntity::getId)
                        .last("LIMIT 1"));
        return latest != null ? latest.getBalanceAfter() : 0;
    }

    /**
     * 积分增加(幂等: 同一 bizType+bizId+reason 只入账一次).
     */
    @Transactional(rollbackFor = Exception.class)
    public int earn(Long userId, int points, String reason, String bizType, String bizId) {
        if (points <= 0) {
            return balanceOf(userId);
        }
        if (bizId != null && alreadyRecorded(userId, "EARN", reason, bizType, bizId)) {
            return balanceOf(userId);
        }
        RLock lock = redissonClient.getLock(RedisKeys.bookingLock(0) + ":points:" + userId);
        try {
            lock.lock();
            if (bizId != null && alreadyRecorded(userId, "EARN", reason, bizType, bizId)) {
                return balanceOf(userId);
            }
            int balance = balanceOf(userId) + points;
            insertRecord(userId, "EARN", points, balance, reason, bizType, bizId);
            return balance;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 积分消耗(订单抵扣等). 余额不足抛异常.
     */
    @Transactional(rollbackFor = Exception.class)
    public int spend(Long userId, int points, String reason, String bizType, String bizId) {
        if (points <= 0) {
            return balanceOf(userId);
        }
        RLock lock = redissonClient.getLock(RedisKeys.bookingLock(0) + ":points:" + userId);
        try {
            lock.lock();
            if (bizId != null && alreadyRecorded(userId, "SPEND", reason, bizType, bizId)) {
                return balanceOf(userId);
            }
            int current = balanceOf(userId);
            if (current < points) {
                throw BusinessException.of(ResultCode.POINTS_NOT_ENOUGH);
            }
            int balance = current - points;
            insertRecord(userId, "SPEND", points, balance, reason, bizType, bizId);
            return balance;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 订单取消/关闭返还积分 */
    @Transactional(rollbackFor = Exception.class)
    public void refund(Long userId, int points, String bizId) {
        earn(userId, points, "订单关闭返还", "ORDER", bizId + ":refund");
    }

    public Page<PointsRecordEntity> pageRecords(Long userId, long page, long size) {
        return pointsRecordMapper.selectPage(Page.of(page, size),
                new LambdaQueryWrapper<PointsRecordEntity>()
                        .eq(PointsRecordEntity::getUserId, userId)
                        .orderByDesc(PointsRecordEntity::getId));
    }

    private boolean alreadyRecorded(Long userId, String changeType, String reason,
                                    String bizType, String bizId) {
        return pointsRecordMapper.selectCount(new LambdaQueryWrapper<PointsRecordEntity>()
                .eq(PointsRecordEntity::getUserId, userId)
                .eq(PointsRecordEntity::getChangeType, changeType)
                .eq(PointsRecordEntity::getReason, reason)
                .eq(PointsRecordEntity::getBizType, bizType)
                .eq(PointsRecordEntity::getBizId, bizId)) > 0;
    }

    private void insertRecord(Long userId, String changeType, int points, int balance,
                              String reason, String bizType, String bizId) {
        PointsRecordEntity record = new PointsRecordEntity();
        record.setUserId(userId);
        record.setChangeType(changeType);
        record.setPoints(points);
        record.setBalanceAfter(balance);
        record.setReason(reason);
        record.setBizType(bizType);
        record.setBizId(bizId);
        pointsRecordMapper.insert(record);
    }
}
