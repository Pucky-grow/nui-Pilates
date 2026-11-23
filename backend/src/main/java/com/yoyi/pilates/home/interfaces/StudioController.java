package com.yoyi.pilates.home.interfaces;

import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.system.application.SystemConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 工作室信息(关于优仪/导航/客服).
 */
@RestController
@RequiredArgsConstructor
public class StudioController {

    private final SystemConfigService configService;

    @GetMapping("/api/studio")
    public Result<Map<String, String>> studio() {
        Map<String, String> view = new HashMap<>();
        view.put("name", configService.get("studio.name", "门店资料待配置"));
        view.put("slogan", configService.get("studio.slogan", ""));
        view.put("phone", configService.get("studio.phone", ""));
        view.put("address", configService.get("studio.address", ""));
        view.put("hours", configService.get("studio.hours", ""));
        view.put("location", configService.get("studio.location", ""));
        view.put("intro", configService.get("studio.intro", ""));
        return Result.ok(view);
    }
}
