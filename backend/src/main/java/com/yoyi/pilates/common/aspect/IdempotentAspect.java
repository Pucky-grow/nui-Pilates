package com.yoyi.pilates.common.aspect;

import com.yoyi.pilates.common.annotation.Idempotent;
import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * {@link Idempotent} 切面: Redis SETNX 占位, 防重复提交/防连点.
 * 注意: 这是第一道防线, 最终一致性仍由数据库唯一索引保证.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private final StringRedisTemplate redisTemplate;
    private final SpelKeyResolver spelKeyResolver;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        String key = spelKeyResolver.resolve(pjp, idempotent.key());
        Boolean acquired = redisTemplate.opsForValue()
                .setIfAbsent(RedisKeys.idempotent(key), "1", Duration.ofSeconds(idempotent.expireSeconds()));
        if (!Boolean.TRUE.equals(acquired)) {
            throw BusinessException.of(ResultCode.CONFLICT, idempotent.message());
        }
        try {
            return pjp.proceed();
        } catch (Throwable error) {
            redisTemplate.delete(RedisKeys.idempotent(key));
            throw error;
        }
    }
}
