package com.yoyi.pilates.product.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 课时流水(package_credit_log 表): 一切课时变动的审计依据.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("package_credit_log")
public class PackageCreditLogEntity extends BaseEntity {

    private Long userPackageId;
    private Long userId;
    /** GRANT发放 / DEDUCT预约扣减 / REFUND取消返还 / ADJUST后台调整 / EXPIRE过期清零 */
    private String changeType;
    private Integer changeAmount;
    private Integer balanceAfter;
    private String bizType;
    private String bizId;
    private String operator;
    private String remark;
}
