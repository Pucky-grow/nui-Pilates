package com.yoyi.pilates.common.aspect;

import com.yoyi.pilates.common.annotation.RateLimit;
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
 * {@link RateLimit} 切面: 固定窗口计数限流.
 */
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final StringRedisTemplate redisTemplate;
    private final SpelKeyResolver spelKeyResolver;

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        String key = RedisKeys.rateLimit(spelKeyResolver.resolve(pjp, rateLimit.key()));
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redisTemplate.expire(key, Duration.ofSeconds(rateLimit.windowSeconds()));
        }
        if (count != null && count > rateLimit.limit()) {
            throw BusinessException.of(ResultCode.RATE_LIMITED);
        }
        return pjp.proceed();
    }
}
