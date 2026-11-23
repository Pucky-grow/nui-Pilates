package com.yoyi.pilates.marketing.interfaces;

import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.marketing.application.InvitationService;
import com.yoyi.pilates.marketing.infrastructure.entity.InvitationEntity;
import com.yoyi.pilates.system.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 邀请好友接口.
 */
@RestController
@RequestMapping("/api/invitation")
@RequiredArgsConstructor
public class InvitationController {

    private final InvitationService invitationService;

    /** 我的邀请码 */
    @GetMapping("/my-code")
    public Result<InvitationEntity> myCode() {
        return Result.ok(invitationService.myCode(SecurityUtils.requireMemberId()));
    }

    /** 绑定邀请码(新用户注册后调用) */
    @PostMapping("/bind")
    public Result<Void> bind(@RequestBody Map<String, String> body) {
        invitationService.bindInvitee(SecurityUtils.requireMemberId(), body.get("inviteCode"));
        return Result.ok();
    }
}
