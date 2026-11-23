package com.yoyi.pilates.system.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级权限校验: 标注在 Controller 方法上.
 * SUPER_ADMIN 角色直接放行.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPerm {

    /** 权限编码, 如 "course:create" */
    String value();
}
