package com.yoyi.pilates.system.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 权限点(sys_permission 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_permission")
public class SysPermissionEntity extends BaseEntity {

    private Long parentId;
    private String code;
    private String name;
    /** MENU / BUTTON / API */
    private String type;
    private String path;
    private Integer sort;
}
