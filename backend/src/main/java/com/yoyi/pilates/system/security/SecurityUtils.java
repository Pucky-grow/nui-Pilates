package com.yoyi.pilates.system.security;

import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

/**
 * 当前登录用户工具.
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Optional<LoginUser> current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser user) {
            return Optional.of(user);
        }
        return Optional.empty();
    }

    /** 仅返回经过主体类型校验的会员 user.id；后台账号不能通过 ID 重合冒充会员。 */
    public static Long requireMemberId() {
        LoginUser user = requireUser();
        if (!"MP_USER".equals(user.getType())) {
            throw com.yoyi.pilates.common.exception.BusinessException.of(
                    com.yoyi.pilates.common.result.ResultCode.FORBIDDEN, "请使用会员身份访问");
        }
        return user.getId();
    }

    /** 兼容旧调用；语义始终是会员 ID，不是 sys_user.id。 */
    public static Long requireUserId() {
        return requireMemberId();
    }

    public static LoginUser requireUser() {
        return current()
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException("未登录"));
    }
}
