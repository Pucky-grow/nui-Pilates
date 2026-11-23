package com.yoyi.pilates.ledger;

import com.yoyi.pilates.common.annotation.RateLimit;
import com.yoyi.pilates.common.result.Result;
import com.yoyi.pilates.system.security.SecurityUtils;
import com.yoyi.pilates.user.application.WxMpClient;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
class StudioLedgerController {
    private final StudioLedgerAccess access;
    private final StudioOfflineMembers members;
    private final LedgerStore db;
    private final WxMpClient wechat;
    private final StudioWalletService wallet;
    private final StudioLessonService lessons;
    private final StudioBookingFees fees;

    record Claim(String inviteCode, String code) {}

    @PostMapping("/api/auth/offline-member-claims")
    @RateLimit(key = "'ledger-claim:' + T(com.yoyi.pilates.common.util.IpUtils).getClientIp(#request)", limit = 10)
    public ResponseEntity<Result<Map<String, Object>>> claim(@RequestBody Claim input, HttpServletRequest request) {
        access.requireEnabled();
        members.checkInvite(input.inviteCode());
        var session = wechat.code2session(LedgerRules.required(input.code(), 128, "微信登录凭证"));
        return personal(members.requestClaim(input.inviteCode(), session));
    }

    @GetMapping("/api/my/lesson-history")
    ResponseEntity<Result<List<Map<String, Object>>>> history(@RequestParam(defaultValue = "9223372036854775807") long before) {
        return personal(lessons.lessons(member(), before, false));
    }

    @GetMapping("/api/my/wallet")
    ResponseEntity<Result<Map<String, Object>>> wallet() {
        return personal(wallet.overview(member(), false));
    }

    @GetMapping("/api/my/wallet/entries")
    ResponseEntity<Result<List<Map<String, Object>>>> entries(@RequestParam(defaultValue = "9223372036854775807") long before) {
        return personal(wallet.entries(member(), before, false));
    }

    @GetMapping("/api/my/wallet/cancellation-fees")
    ResponseEntity<Result<List<Map<String, Object>>>> myFees(@RequestParam(defaultValue = "9223372036854775807") long before) {
        return personal(fees.memberCases(member(), before));
    }

    @GetMapping("/api/studio/late-fees/access")
    Result<Boolean> lateFeeAccess() {
        return Result.ok(access.enabled() && fees.allowed(access.operator(), false));
    }

    @GetMapping("/api/studio/late-fees")
    Result<List<Map<String, Object>>> fees(@RequestParam(defaultValue = "9223372036854775807") long before) {
        return Result.ok(fees.cases(access.operator(), before));
    }

    @PostMapping("/api/studio/late-fees/{id}/decision")
    Result<Long> decide(@PathVariable long id, @RequestHeader("X-Studio-Command") String key, @RequestBody StudioBookingFees.Decision input) {
        return Result.ok(fees.decide(id, access.operator(), key, input));
    }

    private long member() {
        access.requireEnabled();
        return db.memberForUser(SecurityUtils.requireMemberId());
    }

    private static <T> ResponseEntity<Result<T>> personal(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Result.ok(data));
    }
}
