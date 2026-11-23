package com.yoyi.pilates.system.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 操作日志(对应 operation_log 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("operation_log")
public class OperationLogEntity extends BaseEntity {

    private Long operatorId;
    private String operatorName;
    private String module;
    private String action;
    private String targetType;
    private String targetId;
    private String detail;
    private String ip;
}
