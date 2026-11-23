package com.yoyi.pilates.marketing.interfaces;

import com.yoyi.pilates.common.annotation.Idempotent;
import com.yoyi.pilates.common.result.PageResult;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.marketing.application.CouponService;
import com.yoyi.pilates.marketing.application.PointsService;
import com.yoyi.pilates.marketing.infrastructure.entity.CouponEntity;
import com.yoyi.pilates.marketing.infrastructure.entity.PointsRecordEntity;
import com.yoyi.pilates.marketing.infrastructure.entity.UserCouponEntity;
import com.yoyi.pilates.marketing.infrastructure.mapper.CouponMapper;
import com.yoyi.pilates.system.security.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 营销接口: 优惠券 / 积分.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MarketingController {

    private final CouponService couponService;
    private final PointsService pointsService;
    private final CouponMapper couponMapper;

    /** 可领取的优惠券(领券中心) */
    @GetMapping("/coupons")
    public Result<List<CouponEntity>> receivable() {
        return Result.ok(couponMapper.selectList(new LambdaQueryWrapper<CouponEntity>()
                .eq(CouponEntity::getStatus, "ACTIVE")
                .le(CouponEntity::getValidFrom, LocalDateTime.now())
                .ge(CouponEntity::getValidUntil, LocalDateTime.now())));
    }

    /** 领取优惠券(幂等防连点) */
    @PostMapping("/coupons/{id}/receive")
    @Idempotent(key = "T(com.yoyi.pilates.system.security.SecurityUtils).requireMemberId() + ':coupon:' + #id",
            expireSeconds = 5, message = "领取中, 请勿重复点击")
    public Result<Void> receive(@PathVariable Long id) {
        couponService.receive(SecurityUtils.requireMemberId(), id);
        return Result.ok();
    }

    /** 我的优惠券(含券面信息) */
    @GetMapping("/my/coupons")
    public Result<List<Map<String, Object>>> myCoupons(@RequestParam(required = false) String status) {
        Long userId = SecurityUtils.requireMemberId();
        List<UserCouponEntity> userCoupons = couponService.myCoupons(userId, status);
        Map<Long, CouponEntity> couponMap = couponMapper.selectBatchIds(
                        userCoupons.stream().map(UserCouponEntity::getCouponId).distinct().toList())
                .stream().collect(Collectors.toMap(CouponEntity::getId, Function.identity()));
        List<Map<String, Object>> views = userCoupons.stream().map(uc -> {
            Map<String, Object> v = new java.util.HashMap<>();
            v.put("id", uc.getId());
            v.put("status", uc.getStatus());
            v.put("receivedAt", uc.getReceivedAt());
            CouponEntity c = couponMap.get(uc.getCouponId());
            if (c != null) {
                v.put("name", c.getName());
                v.put("type", c.getType());
                v.put("discountAmount", c.getDiscountAmount());
                v.put("discountRate", c.getDiscountRate());
                v.put("minAmount", c.getMinAmount());
                v.put("validUntil", c.getValidUntil());
            }
            return v;
        }).toList();
        return Result.ok(views);
    }

    /** 积分余额 */
    @GetMapping("/my/points")
    public Result<Map<String, Integer>> myPoints() {
        return Result.ok(Map.of("balance", pointsService.balanceOf(SecurityUtils.requireMemberId())));
    }

    /** 积分流水 */
    @GetMapping("/my/points/records")
    public Result<PageResult<PointsRecordEntity>> pointsRecords(@RequestParam(defaultValue = "1") long page,
                                                                @RequestParam(defaultValue = "20") long size) {
        return Result.ok(PageResult.of(
                pointsService.pageRecords(SecurityUtils.requireMemberId(), page, size), r -> r));
    }
}
