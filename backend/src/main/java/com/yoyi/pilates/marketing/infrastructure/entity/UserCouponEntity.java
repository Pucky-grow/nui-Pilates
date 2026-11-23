package com.yoyi.pilates.marketing.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 用户优惠券(user_coupon 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_coupon")
public class UserCouponEntity extends BaseEntity {

    private Long couponId;
    private Long userId;
    /** UNUSED / USED / EXPIRED */
    private String status;
    private LocalDateTime receivedAt;
    private LocalDateTime usedAt;
    private Long orderId;
}
