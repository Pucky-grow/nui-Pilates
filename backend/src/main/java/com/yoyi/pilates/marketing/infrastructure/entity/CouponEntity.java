package com.yoyi.pilates.marketing.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 优惠券(coupon 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("coupon")
public class CouponEntity extends BaseEntity {

    private String name;
    /** NEW_USER / FULL_REDUCE / DISCOUNT / COURSE / PRIVATE / ACTIVITY */
    private String type;
    private BigDecimal discountAmount;
    private BigDecimal discountRate;
    private BigDecimal minAmount;
    /** ALL / CATEGORY / COURSE / PACKAGE */
    private String applyType;
    private String applyIds;
    private Integer totalCount;
    private Integer issuedCount;
    private Integer perLimit;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    private String status;
}
