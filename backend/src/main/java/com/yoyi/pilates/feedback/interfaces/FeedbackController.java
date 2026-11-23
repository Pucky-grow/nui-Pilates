package com.yoyi.pilates.feedback.interfaces;

import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.feedback.application.FeedbackService;
import com.yoyi.pilates.feedback.infrastructure.entity.FeedbackEntity;
import com.yoyi.pilates.system.security.RequiresPerm;
import com.yoyi.pilates.system.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 意见反馈接口.
 */
@RestController
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService feedbackService;

    /** 用户提交反馈 */
    @PostMapping("/api/feedback")
    public Result<Long> submit(@RequestBody FeedbackEntity feedback) {
        return Result.ok(feedbackService.submit(SecurityUtils.requireMemberId(), feedback));
    }

    /** 我的反馈 */
    @GetMapping("/api/my/feedback")
    public Result<List<FeedbackEntity>> myFeedbacks() {
        return Result.ok(feedbackService.myFeedbacks(SecurityUtils.requireMemberId()));
    }

    /** 后台: 反馈列表 */
    @GetMapping({"/admin/feedback", "/api/admin/feedback"})
    @RequiresPerm("content:manage")
    public Result<List<FeedbackEntity>> list(@RequestParam(required = false) String status) {
        return Result.ok(feedbackService.listByStatus(status));
    }

    /** 后台: 处理反馈 */
    @PostMapping({"/admin/feedback/{id}/handle", "/api/admin/feedback/{id}/handle"})
    @RequiresPerm("content:manage")
    public Result<Void> handle(@PathVariable Long id, @RequestBody Map<String, String> body) {
        feedbackService.handle(id, body.getOrDefault("status", "DONE"), body.get("reply"),
                SecurityUtils.current().map(u -> u.getId()).orElse(null));
        return Result.ok();
    }
}
