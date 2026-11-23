package com.yoyi.pilates.marketing.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.marketing.infrastructure.entity.CouponEntity;
import com.yoyi.pilates.marketing.infrastructure.entity.UserCouponEntity;
import com.yoyi.pilates.marketing.infrastructure.mapper.CouponMapper;
import com.yoyi.pilates.marketing.infrastructure.mapper.UserCouponMapper;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 优惠券服务: 领取 / 新人礼包 / 下单核销 / 关闭退回 / 折扣计算.
 */
@Service
@RequiredArgsConstructor
public class CouponService {

    private final CouponMapper couponMapper;
    private final UserCouponMapper userCouponMapper;
    private final RedissonClient redissonClient;

    // ---------------- 领取 ----------------

    @Transactional(rollbackFor = Exception.class)
    public void receive(Long userId, Long couponId) {
        // 按用户加锁串行, 保证"每人限领"校验在并发下不超领
        RLock lock = redissonClient.getLock("pilates:lock:coupon:" + userId);
        try {
            lock.lock();
            CouponEntity coupon = couponMapper.selectById(couponId);
            validateReceivable(coupon);
            Long received = userCouponMapper.selectCount(new LambdaQueryWrapper<UserCouponEntity>()
                    .eq(UserCouponEntity::getUserId, userId)
                    .eq(UserCouponEntity::getCouponId, couponId));
            if (received >= coupon.getPerLimit()) {
                throw BusinessException.of(ResultCode.COUPON_UNAVAILABLE, "已达领取上限");
            }
            // 库存乐观扣减(0=不限量)
            if (coupon.getTotalCount() > 0) {
                int rows = couponMapper.deductStock(couponId);
                if (rows == 0) {
                    throw BusinessException.of(ResultCode.COUPON_UNAVAILABLE, "优惠券已领完");
                }
            }
            UserCouponEntity uc = new UserCouponEntity();
            uc.setCouponId(couponId);
            uc.setUserId(userId);
            uc.setStatus("UNUSED");
            uc.setReceivedAt(LocalDateTime.now());
            userCouponMapper.insert(uc);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 新人礼包: 发放全部在效 NEW_USER 券(每种一张) */
    @Transactional(rollbackFor = Exception.class)
    public int issueNewUserGift(Long userId) {
        List<CouponEntity> gifts = couponMapper.selectList(new LambdaQueryWrapper<CouponEntity>()
                .eq(CouponEntity::getType, "NEW_USER")
                .eq(CouponEntity::getStatus, "ACTIVE")
                .le(CouponEntity::getValidFrom, LocalDateTime.now())
                .ge(CouponEntity::getValidUntil, LocalDateTime.now()));
        int issued = 0;
        for (CouponEntity coupon : gifts) {
            Long received = userCouponMapper.selectCount(new LambdaQueryWrapper<UserCouponEntity>()
                    .eq(UserCouponEntity::getUserId, userId)
                    .eq(UserCouponEntity::getCouponId, coupon.getId()));
            if (received > 0) {
                continue;
            }
            if (coupon.getTotalCount() > 0 && couponMapper.deductStock(coupon.getId()) == 0) {
                continue;
            }
            UserCouponEntity uc = new UserCouponEntity();
            uc.setCouponId(coupon.getId());
            uc.setUserId(userId);
            uc.setStatus("UNUSED");
            uc.setReceivedAt(LocalDateTime.now());
            userCouponMapper.insert(uc);
            issued++;
        }
        return issued;
    }

    // ---------------- 查询 ----------------

    public List<UserCouponEntity> myCoupons(Long userId, String status) {
        return userCouponMapper.selectList(new LambdaQueryWrapper<UserCouponEntity>()
                .eq(UserCouponEntity::getUserId, userId)
                .eq(status != null && !status.isBlank(), UserCouponEntity::getStatus, status)
                .orderByDesc(UserCouponEntity::getId));
    }

    public CouponEntity couponOf(Long couponId) {
        return couponMapper.selectById(couponId);
    }

    // ---------------- 核销与退回(订单事务内) ----------------

    /** 下单核销: 乐观状态推进防重复使用 */
    @Transactional(rollbackFor = Exception.class)
    public void markUsed(Long userCouponId, Long userId, Long orderId) {
        int rows = userCouponMapper.markUsedIfUnused(userCouponId, userId, orderId, LocalDateTime.now());
        if (rows == 0) {
            throw BusinessException.of(ResultCode.COUPON_UNAVAILABLE);
        }
    }

    /** 订单关闭退回 */
    @Transactional(rollbackFor = Exception.class)
    public void restore(Long userCouponId, Long orderId) {
        userCouponMapper.restoreIfUsed(userCouponId, orderId);
    }

    /**
     * 计算券抵扣金额.
     *
     * @param userCouponId 用户券ID(可空)
     * @param userId       用户
     * @param packageId    购买的课包
     * @param amount       订单金额(券前)
     * @return 抵扣金额
     */
    public BigDecimal computeDiscount(Long userCouponId, Long userId, Long packageId, BigDecimal amount) {
        if (userCouponId == null) {
            return BigDecimal.ZERO;
        }
        UserCouponEntity uc = userCouponMapper.selectById(userCouponId);
        if (uc == null || !uc.getUserId().equals(userId) || !"UNUSED".equals(uc.getStatus())) {
            throw BusinessException.of(ResultCode.COUPON_UNAVAILABLE);
        }
        CouponEntity coupon = couponMapper.selectById(uc.getCouponId());
        LocalDateTime now = LocalDateTime.now();
        if (coupon == null || !"ACTIVE".equals(coupon.getStatus())
                || now.isBefore(coupon.getValidFrom()) || now.isAfter(coupon.getValidUntil())) {
            throw BusinessException.of(ResultCode.COUPON_UNAVAILABLE, "优惠券已过期");
        }
        if (amount.compareTo(coupon.getMinAmount()) < 0) {
            throw BusinessException.of(ResultCode.COUPON_UNAVAILABLE,
                    "未满足优惠券使用门槛(满" + coupon.getMinAmount() + "元可用)");
        }
        // 适用范围: PACKAGE 限定课包
        if ("PACKAGE".equals(coupon.getApplyType()) && coupon.getApplyIds() != null
                && !List.of(coupon.getApplyIds().split(",")).contains(String.valueOf(packageId))) {
            throw BusinessException.of(ResultCode.COUPON_UNAVAILABLE, "该券不适用于此课包");
        }
        BigDecimal discount;
        if (coupon.getDiscountRate() != null) {
            discount = amount.multiply(BigDecimal.ONE.subtract(coupon.getDiscountRate()))
                    .setScale(2, RoundingMode.HALF_UP);
        } else {
            discount = coupon.getDiscountAmount() == null ? BigDecimal.ZERO : coupon.getDiscountAmount();
        }
        return discount.min(amount);
    }

    private void validateReceivable(CouponEntity coupon) {
        LocalDateTime now = LocalDateTime.now();
        if (coupon == null || !"ACTIVE".equals(coupon.getStatus())) {
            throw BusinessException.of(ResultCode.COUPON_NOT_FOUND);
        }
        if (now.isBefore(coupon.getValidFrom()) || now.isAfter(coupon.getValidUntil())) {
            throw BusinessException.of(ResultCode.COUPON_UNAVAILABLE, "不在领取时间内");
        }
    }
}
