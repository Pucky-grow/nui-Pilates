package com.yoyi.pilates.product.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 用户课包实例(user_package 表).
 * remaining_credits 扣减走"条件 UPDATE + 流水"双保险, 见 PackageService.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("user_package")
public class UserPackageEntity extends BaseEntity {

    private Long userId;
    private Long packageId;
    private Long orderId;
    private String name;
    /** 类型快照 TIMES / PERIOD / PRIVATE */
    private String type;
    private Integer totalCredits;
    private Integer remainingCredits;
    private Integer usedCredits;
    private LocalDate validFrom;
    private LocalDate validUntil;
    /** ACTIVE / USED_UP / EXPIRED / REFUNDED */
    private String status;
    private Integer version;
}
