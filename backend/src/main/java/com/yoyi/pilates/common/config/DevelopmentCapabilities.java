package com.yoyi.pilates.common.config;

import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.util.Arrays;

/** Mock 能力仅限显式开发或隔离 JUnit 环境，prod/test 混合 profile 也不能开启。 */
@Component
@RequiredArgsConstructor
public class DevelopmentCapabilities {
    private final Environment environment;

    public boolean allowsMocks() {
        var profiles = Arrays.asList(environment.getActiveProfiles());
        return !profiles.isEmpty() && profiles.stream().allMatch(p -> p.equals("dev") || p.equals("junit"));
    }

    public void requireMock(boolean enabled) {
        if (!enabled || !allowsMocks()) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "当前环境未配置此验证能力");
        }
    }
}
