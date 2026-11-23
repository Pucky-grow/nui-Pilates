package com.yoyi.pilates.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 简单限流注解: 滑动窗口计数(Redis INCR + EXPIRE).
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /** 限流维度 SpEL, 如 "#userId" 或 "'ip:' + 常量" */
    String key();

    /** 窗口内允许次数 */
    int limit();

    /** 窗口时长(秒) */
    int windowSeconds() default 60;
}
