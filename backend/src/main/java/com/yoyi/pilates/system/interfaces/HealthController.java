package com.yoyi.pilates.system.interfaces;

import com.yoyi.pilates.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 健康检查(部署探活).
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public Result<Map<String, Object>> health() {
        return Result.ok(Map.of(
                "status", "UP",
                "service", "pilates-server",
                "time", LocalDateTime.now().toString()));
    }
}
