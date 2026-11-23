package com.yoyi.pilates.trade.interfaces.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 创建订单请求.
 */
@Data
public class OrderCreateRequest {

    @NotNull(message = "课包不能为空")
    private Long packageId;

    /** 使用的用户优惠券ID(可选) */
    private Long userCouponId;

    /** 使用积分数量(可选) */
    private Integer pointsUsed;
}
