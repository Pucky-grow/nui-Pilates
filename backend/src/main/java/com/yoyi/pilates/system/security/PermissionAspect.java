package com.yoyi.pilates.system.security;

import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.stereotype.Component;

/**
 * {@link RequiresPerm} 切面: 无权限抛 403.
 */
@Aspect
@Component
public class PermissionAspect {

    @Before("@annotation(requiresPerm)")
    public void check(RequiresPerm requiresPerm) {
        LoginUser user = SecurityUtils.current()
                .orElseThrow(() -> BusinessException.of(ResultCode.UNAUTHORIZED));
        if (!user.hasPerm(requiresPerm.value())) {
            throw BusinessException.of(ResultCode.FORBIDDEN);
        }
    }
}
