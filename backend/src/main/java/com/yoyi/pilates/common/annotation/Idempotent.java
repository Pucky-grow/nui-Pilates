package com.yoyi.pilates.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等提交注解: 方法执行前在 Redis SETNX 占位, 重复请求直接拒绝.
 *
 * <p>key 支持 SpEL, 如 "#userId + ':' + #scheduleId".</p>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 幂等键 SpEL 表达式(基于方法参数) */
    String key();

    /** 占位时长(秒), 默认 10s 覆盖一次事务周期 */
    int expireSeconds() default 10;

    /** 重复提交时的提示 */
    String message() default "请勿重复提交";
}
