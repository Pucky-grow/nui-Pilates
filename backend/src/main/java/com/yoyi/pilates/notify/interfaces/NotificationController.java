package com.yoyi.pilates.notify.interfaces;

import com.yoyi.pilates.common.result.PageResult;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.notify.application.NotificationService;
import com.yoyi.pilates.notify.infrastructure.entity.NotificationEntity;
import com.yoyi.pilates.system.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 消息中心接口.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/notifications")
    public Result<PageResult<NotificationEntity>> list(@RequestParam(defaultValue = "1") long page,
                                                       @RequestParam(defaultValue = "20") long size) {
        return Result.ok(PageResult.of(
                notificationService.pageMy(SecurityUtils.requireMemberId(), page, size), n -> n));
    }

    @GetMapping("/notifications/unread-count")
    public Result<Map<String, Long>> unreadCount() {
        return Result.ok(Map.of("count", notificationService.unreadCount(SecurityUtils.requireMemberId())));
    }

    @PostMapping("/notifications/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        notificationService.markRead(SecurityUtils.requireMemberId(), id);
        return Result.ok();
    }

    @PostMapping("/notifications/read-all")
    public Result<Void> readAll() {
        notificationService.markAllRead(SecurityUtils.requireMemberId());
        return Result.ok();
    }
}
