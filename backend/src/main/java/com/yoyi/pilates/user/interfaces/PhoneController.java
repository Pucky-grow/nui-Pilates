package com.yoyi.pilates.user.interfaces;

import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.system.security.SecurityUtils;
import com.yoyi.pilates.user.application.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;

/**
 * 手机号绑定/更换: 短信验证码.
 * 开发模式(mock)下验证码直接返回给前端便于联调; 生产需替换为真实短信服务商发送.
 */
@RestController
@RequestMapping("/api/auth/phone")
@RequiredArgsConstructor
public class PhoneController {

    private static final String CODE_KEY = "pilates:sms:code:";
    private static final String RATE_KEY = "pilates:sms:rate:";

    private final StringRedisTemplate redisTemplate;
    private final UserService userService;
    private final com.yoyi.pilates.common.config.DevelopmentCapabilities capabilities;
    @org.springframework.beans.factory.annotation.Value("${yoyi.sms.mock-enabled:false}")
    private boolean mockEnabled;

    /** 发送验证码(60秒1次) */
    @PostMapping("/send-code")
    public Result<Map<String, Object>> sendCode(@RequestBody Map<String, String> body) {
        SecurityUtils.requireMemberId();
        capabilities.requireMock(mockEnabled);
        String phone = body.get("phone");
        if (phone == null || !phone.matches("^1\\d{10}$")) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "手机号格式不正确");
        }
        if (Boolean.TRUE.equals(redisTemplate.hasKey(RATE_KEY + phone))) {
            throw BusinessException.of(ResultCode.RATE_LIMITED, "发送过于频繁，请稍后再试");
        }
        String code = String.valueOf(100000 + new SecureRandom().nextInt(900000));
        redisTemplate.opsForValue().set(CODE_KEY + phone, code, Duration.ofMinutes(5));
        redisTemplate.opsForValue().set(RATE_KEY + phone, "1", Duration.ofSeconds(60));
        // TODO 生产环境: 调用短信服务商发送验证码, 不要返回 code
        return Result.ok(Map.of("mock", true, "code", code));
    }

    /** 校验验证码并绑定/更换手机号 */
    @PostMapping("/bind")
    public Result<Void> bind(@RequestBody Map<String, String> body) {
        capabilities.requireMock(mockEnabled);
        Long userId = SecurityUtils.requireMemberId();
        String phone = body.get("phone");
        String code = body.get("code");
        if (phone == null || !phone.matches("^1\\d{10}$")) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "手机号格式不正确");
        }
        if (code == null || code.isBlank()) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "请输入验证码");
        }
        String cached = redisTemplate.opsForValue().get(CODE_KEY + phone);
        if (cached == null || !cached.equals(code)) {
            throw BusinessException.of(ResultCode.BAD_REQUEST, "验证码错误或已过期");
        }
        redisTemplate.delete(CODE_KEY + phone);
        userService.bindPhone(userId, phone);
        return Result.ok();
    }
}
