package com.yoyi.pilates.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yoyi.pilates.common.constant.RedisKeys;
import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.member.application.MemberService;
import com.yoyi.pilates.support.BookingFixture;
import com.yoyi.pilates.system.security.JwtService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "yoyi.studio-ledger.enabled=true")
class StudioLedgerHttpTest extends BookingFixture {
    private static final String ROOT = "/api/admin/studio-ledger";
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired MemberService members;
    @Autowired LedgerStore store;
    private final HttpClient client = HttpClient.newHttpClient();

    @org.junit.jupiter.api.BeforeEach
    void clearLedger() {
        for (String table : List.of("studio_booking_terms", "studio_late_fee_grant", "studio_admin_audit", "studio_admin_command",
                "studio_claim_invite", "studio_member_lesson", "studio_lesson_session", "studio_fee_rule", "studio_receipt",
                "studio_wallet_entry", "studio_wallet", "studio_wallet_profile")) {
            jdbc.update("DELETE FROM " + table);
        }
    }

    @Test
    void offlineMemberCanBookNewScheduleOnceAndKeepTheLedgerAudit() throws Exception {
        Actor admin = actor("ADMIN");
        var member = create(admin, "SHARED");
        String memberPath = ROOT + "/members/" + member.path("id").asLong();
        String bookingPath = memberPath + "/scheduled-bookings";
        var schedule = Map.of("courseId", courseId, "coachId", coachId, "classroomId", roomId,
                "courseDate", StudioTime.today().plusDays(2).toString(),
                "startTime", "16:43", "endTime", "17:43", "maxCapacity", 4);
        var request = Map.of("schedule", schedule, "reason", "会员已确认日期");
        String key = UUID.randomUUID().toString();
        long id = ok("POST", bookingPath, admin.token(), request, key).asLong();
        assertEquals(id, ok("POST", bookingPath, admin.token(), request, key).asLong());
        assertEquals(member.path("user_id").asLong(), bookingMapper.selectById(id).getUserId());
        assertEquals(2, schedules.selectCount(null));
        assertEquals(1, bookingMapper.selectCount(null));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM studio_admin_audit WHERE action='OFFLINE_SCHEDULE_BOOK' AND target_id=?", Integer.class, id));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM studio_booking_terms WHERE booking_id=?", Integer.class, id));
        assertEquals(0, balance(admin, memberPath));
    }

    @Test
    void splitWalletHistoryIdempotencyAndClaim() throws Exception {
        Actor admin = actor("ADMIN");
        var member = create(admin, "SPLIT");
        long memberId = member.path("id").asLong();
        long offlineUser = member.path("user_id").asLong();
        String path = ROOT + "/members/" + memberId;
        var past = StudioTime.now().minusDays(7).truncatedTo(ChronoUnit.SECONDS);
        long recharge = receipt(admin, path, "200", "200", "0", past);
        assertEquals(0, balance(admin, path));
        var verify = Map.of("version", 1, "reason", "核对原收款");
        String key = UUID.randomUUID().toString();
        assertEquals(recharge, ok("POST", path + "/receipts/" + recharge + "/verify", admin.token(), verify, key).asLong());
        assertEquals(recharge, ok("POST", path + "/receipts/" + recharge + "/verify", admin.token(), verify, key).asLong());
        assertEquals(40900, call("POST", path + "/receipts/" + recharge + "/verify", admin.token(),
                Map.of("version", 1, "reason", "不同内容"), key).path("code").asInt());
        assertEquals(20_000, balance(admin, path));
        var duplicate = Map.<String, Object>of("kind", "TOPUP", "amount", "200", "allocation",
                Map.of("group", "200"), "channel", "现金", "businessAt", past.plusMinutes(20), "reason", "两笔分别签收");
        assertEquals(40900, call("POST", path + "/receipts", admin.token(), duplicate, UUID.randomUUID().toString()).path("code").asInt());
        var confirmedDuplicate = new java.util.HashMap<>(duplicate);
        confirmedDuplicate.put("duplicateConfirmed", true);
        post(path + "/receipts", admin, confirmedDuplicate);
        assertEquals(20_000, balance(admin, path));

        long session = post(ROOT + "/sessions", admin, Map.of("courseName", "历史私教", "coachName", "测试教练",
                "courseType", "PRIVATE", "startAt", past.plusDays(1), "endAt", past.plusDays(1).plusHours(1), "price", "280"));
        post(ROOT + "/rules", admin, Map.of("courseType", "PRIVATE", "effectiveAt", past, "freeMinutes", 120,
                "lateKind", "PERCENT", "lateValue", 3000, "absentKind", "NONE", "absentValue", 0));
        long lesson = post(path + "/lessons", admin, Map.of("sessionId", session, "outcome", "LATE_CANCEL",
                "cancelledAt", past.plusDays(1).minusMinutes(20), "decision", "RULE", "reason", "内部旧课凭据"));
        var quote = ok("GET", path + "/lessons/" + lesson + "/quote", admin.token(), null, null);
        assertEquals(8400, quote.path("amountFen").asLong());
        assertEquals("PRIVATE", quote.path("bucket").asText());
        post(path + "/lessons/" + lesson + "/verify", admin, Map.of("quote", quote.path("token").asText(), "reason", "核实课次"));
        assertEquals("WAITING_BALANCE", ok("GET", path + "/lessons", admin.token(), null, null).get(0).path("settlement").asText());
        assertEquals(20_000, balance(admin, path));
        long second = receipt(admin, path, "100", "0", "100", past.plusHours(1));
        post(path + "/receipts/" + second + "/verify", admin, verify);
        post(path + "/lessons/" + lesson + "/settle", admin, Map.of("version", 2, "reason", "补充私教余额"));
        assertEquals(21_600, balance(admin, path));

        assertEquals(40100, call("GET", "/api/my/wallet", memberToken(offlineUser), null, null).path("code").asInt());
        Actor staff = actor("STAFF");
        assertEquals(40300, call("GET", path, staff.token(), null, null).path("code").asInt());
        var invite = ok("POST", path + "/invites", admin.token(), Map.of(), null);
        String syntheticCode = "ledger_" + UUID.randomUUID().toString().substring(0, 12);
        var claim = ok("POST", "/api/auth/offline-member-claims", null,
                Map.of("inviteCode", invite.path("code").asText(), "code", syntheticCode), null);
        assertEquals("REQUESTED", claim.path("state").asText());
        assertFalse(claim.has("memberId"));
        assertEquals(40900, call("POST", "/api/auth/wx-login", null, Map.of("code", syntheticCode), null).path("code").asInt());
        post(path + "/invites/" + invite.path("id").asLong() + "/approve", admin,
                Map.of("proof", claim.path("proof").asText(), "reason", "现场本人确认"));
        assertNotEquals(0, call("POST", "/api/auth/offline-member-claims", null,
                Map.of("inviteCode", invite.path("code").asText(), "code", syntheticCode), null).path("code").asInt());
        var login = ok("POST", "/api/auth/wx-login", null, Map.of("code", syntheticCode), null);
        assertEquals(offlineUser, login.path("userId").asLong());
        String memberToken = login.path("token").asText();
        var history = ok("GET", "/api/my/lesson-history", memberToken, null, null);
        assertEquals(8400, history.get(0).path("amount_fen").asLong());
        assertFalse(history.get(0).has("reason"));
        assertEquals(0, ok("GET", "/api/my/lesson-history?before=" + lesson, memberToken, null, null).size());
        assertEquals(21_600, ok("GET", "/api/my/wallet", memberToken, null, null).path("balance_fen").asLong());
        members.ensureMember(userId);
        assertEquals(0, ok("GET", "/api/my/lesson-history?memberId=" + memberId, memberToken(userId), null, null).size());
        post(path + "/lessons/" + lesson + "/reverse", admin, Map.of("reason", "核对后冲正"));
        assertEquals(30_000, balance(admin, path));
        assertEquals("REVERSED", ok("GET", "/api/my/lesson-history", memberToken, null, null).get(0).path("settlement").asText());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM booking", Integer.class));
    }

    @Test
    void accountCreatedByWechatLoginCanClaimExistingOfflineMember() throws Exception {
        Actor admin = actor("ADMIN");
        var member = create(admin, "SHARED");
        long offlineUser = member.path("user_id").asLong();
        String memberPath = ROOT + "/members/" + member.path("id").asLong();
        String detailPath = "/api/admin/members/" + offlineUser;
        assertFalse(ok("GET", detailPath, admin.token(), null, null).path("wechatBound").asBoolean());
        long receipt = post(memberPath + "/receipts", admin, Map.of("kind", "TOPUP", "amount", "350", "channel", "现金",
                "businessAt", StudioTime.now().minusDays(2), "reason", "原始收款单"));
        post(memberPath + "/receipts/" + receipt + "/verify", admin, Map.of("version", 1, "reason", "核对线下收款"));

        String code = "first_" + UUID.randomUUID().toString().substring(0, 12);
        var login = ok("POST", "/api/auth/wx-login", null, Map.of("code", code), null);
        long onlineUser = login.path("userId").asLong();
        String oldToken = login.path("token").asText();
        assertNotEquals(offlineUser, onlineUser);
        Integer welcome = jdbc.queryForObject("SELECT SUM(points) FROM points_record WHERE user_id=? AND reason='注册'", Integer.class, onlineUser);
        assertNotNull(welcome);
        assertTrue(welcome > 0);
        var invite = ok("POST", memberPath + "/invites", admin.token(), Map.of(), null);
        var claim = ok("POST", "/api/auth/offline-member-claims", oldToken,
                Map.of("inviteCode", invite.path("code").asText(), "code", code), null);
        assertEquals("REQUESTED", claim.path("state").asText());
        assertEquals(onlineUser, ok("GET", "/api/auth/me", oldToken, null, null).path("userId").asLong());
        post(memberPath + "/invites/" + invite.path("id").asLong() + "/approve", admin,
                Map.of("proof", claim.path("proof").asText(), "reason", "本人核对"));

        assertTrue(ok("GET", detailPath, admin.token(), null, null).path("wechatBound").asBoolean());
        assertEquals(40100, call("GET", "/api/my/wallet", oldToken, null, null).path("code").asInt());
        var refreshed = ok("POST", "/api/auth/wx-login", null, Map.of("code", code), null);
        assertEquals(offlineUser, refreshed.path("userId").asLong());
        String token = refreshed.path("token").asText();
        assertEquals(member.path("member_no").asText(), ok("GET", "/api/auth/me", token, null, null).path("memberNo").asText());
        assertEquals(35_000, ok("GET", "/api/my/wallet", token, null, null).path("balance_fen").asLong());
        assertEquals(welcome, jdbc.queryForObject("SELECT SUM(points) FROM points_record WHERE user_id=? AND reason='注册'", Integer.class, offlineUser));
        assertEquals(1, jdbc.queryForObject("SELECT deleted FROM member WHERE user_id=?", Integer.class, onlineUser));
    }

    @Test
    void approvalRechecksOnlineBookingAndLeavesBothMembersIntactOnConflict() throws Exception {
        Actor admin = actor("ADMIN");
        var offline = create(admin, "SHARED");
        String path = ROOT + "/members/" + offline.path("id").asLong();
        String code = "booked_" + UUID.randomUUID().toString().substring(0, 12);
        var login = ok("POST", "/api/auth/wx-login", null, Map.of("code", code), null);
        long onlineUser = login.path("userId").asLong();
        var invite = ok("POST", path + "/invites", admin.token(), Map.of(), null);
        var claimBody = Map.of("inviteCode", invite.path("code").asText(), "code", code);
        var claim = ok("POST", "/api/auth/offline-member-claims", null, claimBody, null);
        long booking = bookings.book(onlineUser, scheduleId, null, null);

        var rejected = call("POST", path + "/invites/" + invite.path("id").asLong() + "/approve", admin.token(),
                Map.of("proof", claim.path("proof").asText(), "reason", "本人核对"), UUID.randomUUID().toString());
        assertEquals(40900, rejected.path("code").asInt());
        assertTrue(rejected.path("message").asText().contains("独立预约"));
        assertEquals("UNBOUND", ok("GET", path, admin.token(), null, null).path("member").path("binding").asText());
        assertEquals(onlineUser, bookingMapper.selectById(booking).getUserId());
        assertEquals(onlineUser, ok("POST", "/api/auth/wx-login", null, Map.of("code", code), null).path("userId").asLong());
        assertEquals(40900, call("POST", "/api/auth/offline-member-claims", null, claimBody, null).path("code").asInt());
    }

    @Test
    void editingLegacySessionWithoutEndKeepsItsKnownEndAndRejectsFutureManualStart() throws Exception {
        Actor admin = actor("ADMIN");
        var at = StudioTime.now().minusDays(1).truncatedTo(ChronoUnit.SECONDS);
        var body = new java.util.HashMap<String, Object>(Map.of("courseName", "旧场次", "coachName", "测试教练",
                "courseType", "GROUP", "startAt", at, "endAt", at.plusMinutes(50), "price", "200"));
        String path = ROOT + "/sessions";
        long id = post(path, admin, body);
        body.put("id", id);
        body.put("version", 1);
        body.remove("endAt");
        assertEquals(id, post(path, admin, body));
        assertEquals(at.plusMinutes(50), store.require("SELECT end_at FROM studio_lesson_session WHERE id=?", id).time("end_at"));
        body.remove("id");
        body.remove("version");
        body.put("startAt", at.plusDays(2));
        assertEquals(40000, call("POST", path, admin.token(), body, UUID.randomUUID().toString()).path("code").asInt());
    }

    @Test
    void replayPreviewExplainsLaterRechargeAndRetryUsesEarlierReceipt() throws Exception {
        Actor admin = actor("ADMIN");
        String path = ROOT + "/members/" + create(admin, "SHARED").path("id").asLong();
        var classAt = StudioTime.now().minusDays(4).truncatedTo(ChronoUnit.SECONDS);
        post(path + "/import", admin, Map.of("mode", "REPLAY", "cutoffAt", classAt.plusDays(3), "reason", "逐笔核对旧账"));
        var receiptBody = Map.of("kind", "TOPUP", "amount", "1000", "channel", "现金", "reason", "原始收款单",
                "businessAt", classAt.plusDays(1));
        var verifyReceipt = Map.of("version", 1, "reason", "已核对收款凭据");
        long later = post(path + "/receipts", admin, receiptBody);
        post(path + "/receipts/" + later + "/verify", admin, verifyReceipt);
        long session = post(ROOT + "/sessions", admin, Map.of("courseName", "历史小班", "coachName", "测试教练",
                "courseType", "GROUP", "startAt", classAt, "price", "500"));
        assertNull(store.require("SELECT NULLIF(end_at,start_at) AS end_at FROM studio_lesson_session WHERE id=?", session).time("end_at"));
        var lessonBody = Map.of("sessionId", session, "outcome", "ATTENDED", "decision", "MANUAL", "manualAmount", "500", "reason", "核对实际课费");
        var draft = ok("POST", path + "/lessons/preview", admin.token(), lessonBody, null);
        assertEquals("HISTORICAL_BALANCE_LOW", draft.path("status").asText());
        assertEquals(100_000, draft.path("balanceFen").asLong());
        assertEquals(0, draft.path("balanceAtFen").asLong(-1));
        assertEquals(50_000, draft.path("shortageFen").asLong());
        assertEquals(0, ok("GET", path + "/lessons", admin.token(), null, null).size());
        long id = post(path + "/lessons", admin, lessonBody);
        String lessonPath = path + "/lessons/" + id;
        var quote = ok("GET", lessonPath + "/quote", admin.token(), null, null);
        assertEquals(draft, quote.path("preview"));
        post(lessonPath + "/verify", admin, Map.of("quote", quote.path("token").asText(), "reason", "已核实上课，等核实旧收款"));
        assertEquals(100_000, balance(admin, path));
        assertEquals(1, ok("GET", path + "/entries", admin.token(), null, null).size());
        var settle = Map.of("version", 2, "reason", "确认结算");
        assertEquals(40900, call("POST", lessonPath + "/settle", admin.token(), settle, UUID.randomUUID().toString()).path("code").asInt());

        var earlierReceipt = new java.util.HashMap<>(receiptBody);
        earlierReceipt.put("businessAt", classAt.minusDays(1));
        long earlier = post(path + "/receipts", admin, earlierReceipt);
        post(path + "/receipts/" + earlier + "/verify", admin, verifyReceipt);
        var ready = ok("GET", lessonPath + "/quote", admin.token(), null, null).path("preview");
        assertEquals("READY", ready.path("status").asText());
        assertEquals(100_000, ready.path("balanceAtFen").asLong());
        post(lessonPath + "/settle", admin, settle);
        var entries = ok("GET", path + "/entries", admin.token(), null, null);
        assertEquals(3, entries.size());
        assertEquals(-50_000, entries.get(0).path("delta_fen").asLong());
        assertEquals(150_000, balance(admin, path));
    }

    @Test
    void openingBalanceAndModeTransferDoNotReplayOldFees() throws Exception {
        Actor admin = actor("ADMIN");
        long memberId = create(admin, "SHARED").path("id").asLong();
        String path = ROOT + "/members/" + memberId;
        var cutoff = StudioTime.now().minusDays(3).truncatedTo(ChronoUnit.SECONDS);
        post(path + "/import", admin, Map.of("mode", "OPENING", "cutoffAt", cutoff, "reason", "原台账已结余核实"));
        long opening = post(path + "/receipts", admin, Map.of("kind", "OPENING", "amount", "600", "channel", "原台账",
                "businessAt", cutoff, "reason", "剩余余额"));
        post(path + "/receipts/" + opening + "/verify", admin, Map.of("version", 1, "reason", "核对期初"));
        long session = post(ROOT + "/sessions", admin, Map.of("courseName", "期初覆盖课", "coachName", "测试教练",
                "courseType", "GROUP", "startAt", cutoff.minusDays(1), "endAt", cutoff.minusDays(1).plusHours(1), "price", "120"));
        long lesson = post(path + "/lessons", admin, Map.of("sessionId", session, "outcome", "ATTENDED", "decision", "RULE", "reason", "已计入旧余额"));
        var quote = ok("GET", path + "/lessons/" + lesson + "/quote", admin.token(), null, null);
        assertEquals("OPENING_COVERED", quote.path("basis").asText());
        assertEquals("NO_WALLET_DEBIT", quote.path("preview").path("status").asText());
        post(path + "/lessons/" + lesson + "/verify", admin, Map.of("quote", quote.path("token").asText(), "reason", "核对旧课"));
        assertEquals(60_000, balance(admin, path));
        long version = ok("GET", path, admin.token(), null, null).path("wallet").path("version").asLong();
        post(path + "/mode", admin, Map.of("mode", "SPLIT", "version", version, "reason", "按原余额分配",
                "allocation", Map.of("group", "200", "privateAmount", "400")));
        var wallet = ok("GET", path, admin.token(), null, null).path("wallet");
        assertEquals(60_000, wallet.path("balance_fen").asLong());
        assertEquals(20_000, wallet.path("accounts").get(0).path("balance_fen").asLong());
        for (var row : wallet.path("reconciliation")) assertEquals(row.path("ledger_fen").asLong(), row.path("balance_fen").asLong());
    }

    @Test
    void delegatedLateFeeUsesBookingTermsAndConcurrentDecisionPostsOnce() throws Exception {
        Actor admin = actor("ADMIN");
        Actor staff = actor("STAFF");
        Actor otherCoach = actor("COACH");
        long memberId = create(admin, "SHARED").path("id").asLong();
        String path = ROOT + "/members/" + memberId;
        long uid = ok("GET", path, admin.token(), null, null).path("member").path("user_id").asLong();
        jdbc.update("UPDATE `user` SET openid=? WHERE id=?", "fixture_" + UUID.randomUUID(), uid);
        long topup = post(path + "/receipts", admin, Map.of("kind", "TOPUP", "amount", "280", "channel", "现金",
                "businessAt", StudioTime.now().minusDays(3), "reason", "收款核对"));
        post(path + "/receipts/" + topup + "/verify", admin, Map.of("version", 1, "reason", "核对"));
        var start = StudioTime.now().plusMinutes(45).truncatedTo(ChronoUnit.MINUTES);
        var schedule = schedules.selectById(scheduleId);
        schedule.setCourseDate(start.toLocalDate());
        schedule.setStartTime(start.toLocalTime());
        schedule.setEndTime(start.plusHours(1).toLocalTime());
        schedules.updateById(schedule);
        config.set("booking.deadline_minutes", "0");
        post(ROOT + "/sessions", admin, Map.of("scheduleId", scheduleId, "courseType", "GROUP", "price", "280"));
        post(ROOT + "/rules", admin, Map.of("courseType", "GROUP", "effectiveAt", StudioTime.now().minusDays(7),
                "freeMinutes", 120, "lateKind", "PERCENT", "lateValue", 3000, "absentKind", "NONE", "absentValue", 0));
        var terms = ok("GET", ROOT + "/booking-terms/" + scheduleId, admin.token(), null, null);
        assertEquals(8400, terms.path("lateFen").asLong());
        var book = Map.of("scheduleId", scheduleId, "reason", "会员已确认收费约定", "quote", terms.path("quote").asText());
        String key = UUID.randomUUID().toString();
        long bid = ok("POST", path + "/bookings", admin.token(), book, key).asLong();
        assertEquals(bid, ok("POST", path + "/bookings", admin.token(), book, key).asLong());
        var disclosed = ok("GET", "/api/bookings/" + bid, memberToken(uid), null, null).path("cancellationTerms");
        assertTrue(disclosed.path("lateNow").asBoolean());
        assertEquals(8400, disclosed.path("lateFeeFen").asLong());
        ok("DELETE", "/api/bookings/" + bid, memberToken(uid), null, null);
        assertEquals("ELIGIBLE", store.require("SELECT state FROM studio_booking_terms WHERE booking_id=?", bid).text("state"));
        assertEquals(0, schedules.selectById(scheduleId).getBookedCount());

        String fee = "/api/studio/late-fees/" + bid + "/decision";
        var charge = Map.of("decision", "CHARGE", "expectedFen", 8400, "reason", "确认按约定收取");
        assertEquals(40300, call("POST", fee, staff.token(), charge, UUID.randomUUID().toString()).path("code").asInt());
        post(ROOT + "/late-fee-grants/" + staff.id(), admin, Map.of("enabled", true));
        assertEquals(40900, call("POST", fee, staff.token(), charge, UUID.randomUUID().toString()).path("code").asInt());

        // Move the isolated fixture forward without waiting for the original start time.
        var elapsed = StudioTime.now().minusMinutes(5).truncatedTo(ChronoUnit.MINUTES);
        schedule = schedules.selectById(scheduleId);
        schedule.setCourseDate(elapsed.toLocalDate());
        schedule.setStartTime(elapsed.toLocalTime());
        schedule.setEndTime(elapsed.plusHours(1).toLocalTime());
        schedules.updateById(schedule);
        jdbc.update("UPDATE studio_booking_terms SET start_at=?,cutoff_at=?,cancelled_at=? WHERE booking_id=?",
                elapsed, elapsed.minusHours(2), elapsed.minusMinutes(30), bid);
        jdbc.update("UPDATE booking SET cancelled_at=? WHERE id=?", elapsed.minusMinutes(30), bid);

        var another = coaches.selectById(coachId);
        another.setId(null);
        another.setName("另一位教练");
        another.setSysUserId(otherCoach.id());
        coaches.insert(another);
        post(ROOT + "/late-fee-grants/" + otherCoach.id(), admin, Map.of("enabled", true));
        assertEquals(40300, call("POST", fee, otherCoach.token(), charge, UUID.randomUUID().toString()).path("code").asInt());
        assertEquals(0, ok("GET", "/api/studio/late-fees", otherCoach.token(), null, null).size());

        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> task = () -> call("POST", fee, staff.token(), charge, UUID.randomUUID().toString()).path("code").asInt();
            assertEquals(List.of(0, 40900), pool.invokeAll(List.of(task, task)).stream().map(result -> {
                try { return result.get(); } catch (Exception e) { throw new AssertionError(e); }
            }).sorted().toList());
        } finally { pool.shutdownNow(); }
        assertEquals(19_600, balance(admin, path));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM studio_wallet_entry WHERE kind='CANCELLATION'", Integer.class));
        post(ROOT + "/late-fee-grants/" + staff.id(), admin, Map.of("enabled", false));
        assertEquals(40300, call("POST", fee, staff.token(), charge, UUID.randomUUID().toString()).path("code").asInt());
    }

    private JsonNode create(Actor admin, String mode) throws Exception {
        return ok("POST", ROOT + "/members", admin.token(), Map.of("nickname", "线下测试会员", "mode", mode), UUID.randomUUID().toString());
    }

    private long receipt(Actor admin, String path, String amount, String group, String privateAmount, LocalDateTime at) throws Exception {
        return post(path + "/receipts", admin, Map.of("kind", "TOPUP", "amount", amount, "allocation",
                Map.of("group", group, "privateAmount", privateAmount), "channel", "现金", "businessAt", at, "reason", "原收款"));
    }

    private long balance(Actor admin, String path) throws Exception {
        return ok("GET", path, admin.token(), null, null).path("wallet").path("balance_fen").asLong();
    }

    private long post(String path, Actor actor, Object body) throws Exception {
        return ok("POST", path, actor.token(), body, UUID.randomUUID().toString()).asLong();
    }

    private record Actor(long id, String token) {}

    private Actor actor(String role) {
        Long id = store.insert("INSERT INTO sys_user(username,password,name,status,auth_version) VALUES(?,?,?,'ACTIVE',0)",
                "test_" + UUID.randomUUID().toString().substring(0, 12), "unused-test-password", role);
        var row = store.find("SELECT id FROM sys_role WHERE code=?", role);
        long roleId = row == null ? store.insert("INSERT INTO sys_role(code,name,status) VALUES(?,?,'ACTIVE')", role, role) : row.number("id");
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", id, roleId);
        String token = jwt.createToken(id, "ADMIN", 0L);
        redis.opsForValue().set(RedisKeys.loginToken(token), id.toString());
        return new Actor(id, token);
    }

    private String memberToken(long uid) {
        String token = jwt.createToken(uid, "MP_USER");
        redis.opsForValue().set(RedisKeys.loginToken(token), Long.toString(uid));
        return token;
    }

    private JsonNode ok(String method, String path, String token, Object body, String key) throws Exception {
        var response = call(method, path, token, body, key);
        assertEquals(0, response.path("code").asInt(), () -> path + " " + response.path("message").asText());
        return response.path("data");
    }

    private JsonNode call(String method, String path, String token, Object body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json");
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (key != null) request.header("X-Studio-Command", key);
        var response = client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return json.readTree(response.body());
    }
}
