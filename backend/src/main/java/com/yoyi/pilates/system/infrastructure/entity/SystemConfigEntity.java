package com.yoyi.pilates.system.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 系统配置(system_config 表): 预约/取消/积分等业务规则, 后台可改.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("system_config")
public class SystemConfigEntity extends BaseEntity {

    private String configKey;
    private String configValue;
    private String description;
}
