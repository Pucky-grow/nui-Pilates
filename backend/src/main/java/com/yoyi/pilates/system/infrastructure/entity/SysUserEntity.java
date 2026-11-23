package com.yoyi.pilates.system.infrastructure.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.yoyi.pilates.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 后台用户(sys_user 表).
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUserEntity extends BaseEntity {

    private Long authVersion;

    private String username;
    private String password;
    private String name;
    private String avatar;
    private String phone;
    private String email;
    private String status;
    private LocalDateTime lastLoginAt;
}
