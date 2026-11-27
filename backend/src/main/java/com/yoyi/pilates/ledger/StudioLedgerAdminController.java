package com.yoyi.pilates.ledger;

import com.yoyi.pilates.booking.interfaces.dto.ScheduledBookingRequest;
import com.yoyi.pilates.common.result.Result;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/studio-ledger")
@RequiredArgsConstructor
class StudioLedgerAdminController {
    private final StudioLedgerAccess access;
    private final LedgerStore db;
    private final StudioOfflineMembers members;
    private final StudioWalletService wallet;
    private final StudioLessonService lessons;
    private final StudioBookingFees fees;

    record DefaultMode(String mode, String reason) {}

    @PostMapping("/members")
    Result<Map<String, Object>> create(@RequestHeader("X-Studio-Command") String key, @RequestBody StudioOfflineMembers.Create input) {
        return Result.ok(members.member(members.create(access.admin(), key, input)));
    }

    @GetMapping("/members/{id}")
    Result<Map<String, Object>> member(@PathVariable long id) {
        access.admin();
        return Result.ok(Map.of("member", members.member(id), "wallet", wallet.overview(id, true)));
    }

    @GetMapping("/members/{id}/entries")
    Result<List<Map<String, Object>>> entries(@PathVariable long id, @RequestParam(defaultValue = "9223372036854775807") long before) {
        access.admin();
        return Result.ok(wallet.entries(id, before, true));
    }

    @GetMapping("/members/{id}/receipts")
    Result<List<Map<String, Object>>> receipts(@PathVariable long id, @RequestParam(defaultValue = "9223372036854775807") long before) {
        access.admin();
        return Result.ok(wallet.receipts(id, before));
    }

    @GetMapping("/members/{id}/lessons")
    Result<List<Map<String, Object>>> lessons(@PathVariable long id, @RequestParam(defaultValue = "9223372036854775807") long before) {
        access.admin();
        return Result.ok(lessons.lessons(id, before, true));
    }

    @GetMapping("/review")
    Result<Map<String, Object>> review(@RequestParam(defaultValue = "9223372036854775807") long receiptBefore,
            @RequestParam(defaultValue = "9223372036854775807") long lessonBefore) {
        access.admin();
        return Result.ok(Map.of("receipts", db.view("""
                SELECT r.id,r.member_id,r.kind,r.amount_fen,r.business_at,m.member_no
                FROM studio_receipt r JOIN member m ON m.id=r.member_id AND m.deleted=0
                WHERE r.state='PENDING_VERIFY' AND r.id<? ORDER BY r.id DESC LIMIT 30
                """, receiptBefore), "lessons", db.view("""
                SELECT l.id,l.member_id,l.settlement,l.outcome,s.course_name,s.start_at,m.member_no
                FROM studio_member_lesson l JOIN studio_lesson_session s ON s.id=l.session_id
                JOIN member m ON m.id=l.member_id AND m.deleted=0
                WHERE (l.state='PENDING_VERIFY' OR l.settlement='WAITING_BALANCE') AND l.id<?
                ORDER BY l.id DESC LIMIT 30
                """, lessonBefore)));
    }

    @GetMapping("/default-mode")
    Result<String> defaultMode() {
        access.admin();
        return Result.ok(wallet.defaultMode());
    }

    @PostMapping("/default-mode")
    Result<Long> defaultMode(@RequestHeader("X-Studio-Command") String key, @RequestBody DefaultMode input) {
        return Result.ok(wallet.setDefault(access.admin(), key, input.mode(), input.reason()));
    }

    @PostMapping("/members/{id}/mode")
    Result<Long> mode(@PathVariable long id, @RequestHeader("X-Studio-Command") String key,
            @RequestBody StudioWalletService.ModeChange input) {
        return Result.ok(wallet.changeMode(id, access.admin(), key, input));
    }

    @PostMapping("/members/{id}/import")
    Result<Long> importMode(@PathVariable long id, @RequestHeader("X-Studio-Command") String key,
            @RequestBody StudioWalletService.ImportSetup input) {
        return Result.ok(wallet.setImport(id, access.admin(), key, input));
    }

    @PostMapping("/members/{id}/receipts")
    Result<Long> saveReceipt(@PathVariable long id, @RequestHeader("X-Studio-Command") String key,
            @RequestBody StudioWalletService.ReceiptInput input) {
        return Result.ok(wallet.saveReceipt(id, access.admin(), key, input));
    }

    @PostMapping("/members/{id}/receipts/{receiptId}/verify")
    Result<Long> verifyReceipt(@PathVariable long id, @PathVariable long receiptId, @RequestHeader("X-Studio-Command") String key,
            @RequestBody StudioWalletService.VersionAction input) {
        return Result.ok(wallet.verifyReceipt(id, receiptId, access.admin(), key, input, false));
    }

    @PostMapping("/members/{id}/receipts/{receiptId}/return")
    Result<Long> returnReceipt(@PathVariable long id, @PathVariable long receiptId, @RequestHeader("X-Studio-Command") String key,
            @RequestBody StudioWalletService.VersionAction input) {
        return Result.ok(wallet.verifyReceipt(id, receiptId, access.admin(), key, input, true));
    }

    @PostMapping("/members/{id}/receipts/{receiptId}/reverse")
    Result<Long> reverseReceipt(@PathVariable long id, @PathVariable long receiptId, @RequestHeader("X-Studio-Command") String key,
            @RequestBody StudioWalletService.ReverseInput input) {
        return Result.ok(wallet.reverseReceipt(id, receiptId, access.admin(), key, input));
    }

    @GetMapping("/rules")
    Result<List<Map<String, Object>>> rules(@RequestParam(defaultValue = "9223372036854775807") long before) {
        access.admin();
        return Result.ok(lessons.rules(before));
    }

    @PostMapping("/rules")
    Result<Long> rule(@RequestHeader("X-Studio-Command") String key, @RequestBody StudioLessonService.RuleInput input) {
        return Result.ok(lessons.addRule(access.admin(), key, input));
    }

    @GetMapping("/sessions")
    Result<List<Map<String, Object>>> sessions(@RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "9223372036854775807") long before) {
        access.admin();
        return Result.ok(lessons.sessions(keyword, before));
    }

    @PostMapping("/sessions")
    Result<Long> session(@RequestHeader("X-Studio-Command") String key, @RequestBody StudioLessonService.SessionInput input) {
        return Result.ok(lessons.saveSession(access.admin(), key, input));
    }

    @PostMapping("/members/{id}/lessons")
    Result<Long> saveLesson(@PathVariable long id, @RequestHeader("X-Studio-Command") String key,
            @RequestBody StudioLessonService.LessonInput input) {
        return Result.ok(lessons.saveLesson(id, access.admin(), key, input));
    }

    @PostMapping("/members/{id}/lessons/preview")
    Result<StudioLessonService.DebitPreview> previewLesson(@PathVariable long id,
            @RequestBody StudioLessonService.LessonInput input) {
        access.admin();
        return Result.ok(lessons.preview(id, input));
    }

    @GetMapping("/members/{id}/lessons/{lessonId}/quote")
    Result<StudioLessonService.Quote> quote(@PathVariable long id, @PathVariable long lessonId) {
        access.admin();
        return Result.ok(lessons.quote(id, lessonId));
    }

    @PostMapping("/members/{id}/lessons/{lessonId}/verify")
    Result<Long> verifyLesson(@PathVariable long id, @PathVariable long lessonId, @RequestHeader("X-Studio-Command") String key,
            @RequestBody StudioLessonService.Review input) {
        return Result.ok(lessons.verify(id, lessonId, access.admin(), key, input));
    }

    @PostMapping("/members/{id}/lessons/{lessonId}/settle")
    Result<Long> settleLesson(@PathVariable long id, @PathVariable long lessonId, @RequestHeader("X-Studio-Command") String key,
            @RequestBody StudioWalletService.VersionAction input) {
        return Result.ok(lessons.settle(id, lessonId, access.admin(), key, input));
    }

    @PostMapping("/members/{id}/lessons/{lessonId}/return")
    Result<Long> returnLesson(@PathVariable long id, @PathVariable long lessonId, @RequestHeader("X-Studio-Command") String key,
            @RequestBody StudioWalletService.VersionAction input) {
        return Result.ok(lessons.returnLesson(id, lessonId, access.admin(), key, input));
    }

    @PostMapping("/members/{id}/lessons/{lessonId}/reverse")
    Result<Long> reverseLesson(@PathVariable long id, @PathVariable long lessonId, @RequestHeader("X-Studio-Command") String key,
            @RequestBody StudioWalletService.ReverseInput input) {
        return Result.ok(lessons.reverse(id, lessonId, access.admin(), key, input));
    }

    @GetMapping("/booking-terms/{scheduleId}")
    Result<StudioBookingFees.Terms> terms(@PathVariable long scheduleId) {
        access.admin();
        return Result.ok(fees.terms(scheduleId, false));
    }

    @PostMapping("/members/{id}/bookings")
    Result<Long> book(@PathVariable long id, @RequestHeader("X-Studio-Command") String key,
            @RequestBody StudioBookingFees.BookingInput input) {
        return Result.ok(fees.book(id, access.admin(), key, input));
    }

    @PostMapping("/members/{id}/scheduled-bookings")
    Result<Long> bookWithSchedule(@PathVariable long id, @RequestHeader("X-Studio-Command") String key,
                                 @Valid @RequestBody ScheduledBookingRequest input) {
        return Result.ok(fees.bookWithSchedule(id, access.admin(), key, input));
    }

    @GetMapping("/members/{id}/invites")
    Result<List<Map<String, Object>>> invites(@PathVariable long id) {
        access.admin();
        return Result.ok(members.invites(id));
    }

    @PostMapping("/members/{id}/invites")
    Result<StudioOfflineMembers.Invite> invite(@PathVariable long id) {
        return Result.ok(members.invite(id, access.admin()));
    }

    @PostMapping("/members/{id}/invites/{inviteId}/approve")
    Result<Long> approve(@PathVariable long id, @PathVariable long inviteId, @RequestHeader("X-Studio-Command") String key,
            @RequestBody StudioOfflineMembers.Approve input) {
        return Result.ok(members.approve(id, inviteId, access.admin(), key, input));
    }

    @PostMapping("/members/{id}/invites/{inviteId}/revoke")
    Result<Long> revoke(@PathVariable long id, @PathVariable long inviteId, @RequestHeader("X-Studio-Command") String key) {
        return Result.ok(members.revoke(id, inviteId, access.admin(), key));
    }

    @GetMapping("/late-fee-grants")
    Result<List<Map<String, Object>>> grants() {
        access.admin();
        return Result.ok(fees.grants());
    }

    @PostMapping("/late-fee-grants/{operatorId}")
    Result<Long> grant(@PathVariable long operatorId, @RequestHeader("X-Studio-Command") String key, @RequestBody StudioBookingFees.Grant input) {
        return Result.ok(fees.grant(operatorId, access.admin(), key, input));
    }

    @PostMapping("/late-fees/{bookingId}/{action:settle|waive|reverse}")
    Result<Long> finish(@PathVariable long bookingId, @PathVariable String action, @RequestHeader("X-Studio-Command") String key,
            @RequestBody StudioWalletService.ReverseInput input) {
        return Result.ok(fees.finish(bookingId, access.admin(), key, input, "LATE_FEE_" + action.toUpperCase(java.util.Locale.ROOT)));
    }
}
