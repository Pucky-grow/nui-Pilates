package com.yoyi.pilates.marketing.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 积分流水(points_record 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("points_record")
public class PointsRecordEntity extends BaseEntity {

    private Long userId;
    /** EARN / SPEND */
    private String changeType;
    private Integer points;
    private Integer balanceAfter;
    private String reason;
    private String bizType;
    private String bizId;
}
