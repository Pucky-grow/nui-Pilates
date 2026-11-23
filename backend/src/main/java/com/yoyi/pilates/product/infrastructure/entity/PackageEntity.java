package com.yoyi.pilates.product.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * 课包定义(package 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("package")
public class PackageEntity extends BaseEntity {

    private String name;
    private String cover;
    /** TIMES次卡 / PERIOD期限卡 / PRIVATE私教课包 */
    private String type;
    private BigDecimal originalPrice;
    private BigDecimal salePrice;
    /** 总课时, PERIOD 为 0(不限次) */
    private Integer totalCredits;
    private Integer validDays;
    private Integer isNewUserOnly;
    private Integer allowTransfer;
    private Integer allowRefund;
    private Integer purchaseLimit;
    private String description;
    private Integer sort;
    /** ON_SHELF / OFF_SHELF */
    private String status;
}
