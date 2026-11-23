package com.yoyi.pilates.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解: 标注在后台敏感操作上(调课时/退款/改课/删数据等), 自动落 operation_log.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OpLog {

    /** 模块, 如 member / course / order */
    String module();

    /** 动作, 如 adjust_credits / refund */
    String action();

    /** 业务对象ID SpEL, 如 "#memberId", 无则留空 */
    String targetId() default "";

    /** 操作详情 SpEL(可拼接变更内容), 默认记录方法参数JSON */
    String detail() default "";
}
