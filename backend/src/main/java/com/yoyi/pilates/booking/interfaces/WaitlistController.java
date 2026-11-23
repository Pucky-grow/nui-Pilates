package com.yoyi.pilates.booking.interfaces;

import com.yoyi.pilates.booking.application.WaitlistService;
import com.yoyi.pilates.booking.infrastructure.entity.WaitlistEntity;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.system.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 候补接口(学员).
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class WaitlistController {

    private final WaitlistService waitlistService;

    /** 加入候补(课程满员) */
    @PostMapping("/waitlist/{scheduleId}/join")
    public Result<Long> join(@PathVariable Long scheduleId) {
        return Result.ok(waitlistService.join(SecurityUtils.requireMemberId(), scheduleId));
    }

    /** 退出候补 */
    @PostMapping("/waitlist/{scheduleId}/leave")
    public Result<Void> leave(@PathVariable Long scheduleId) {
        waitlistService.leave(SecurityUtils.requireMemberId(), scheduleId);
        return Result.ok();
    }

    /** 我的候补 */
    @GetMapping("/my/waitlist")
    public Result<List<WaitlistEntity>> myWaitlist() {
        return Result.ok(waitlistService.myWaitlist(SecurityUtils.requireMemberId()));
    }
}
