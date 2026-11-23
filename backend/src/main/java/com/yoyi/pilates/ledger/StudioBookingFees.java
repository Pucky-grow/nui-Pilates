package com.yoyi.pilates.ledger;

import static com.yoyi.pilates.ledger.LedgerRules.*;

import com.yoyi.pilates.booking.application.BookingService;
import com.yoyi.pilates.booking.application.ScheduleExecution;
import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.JsonUtils;
import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.system.security.LoginUser;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class StudioBookingFees {
    private final LedgerStore db;
    private final StudioWalletService wallet;
    private final StudioLessonService lessons;
    private final StudioOfflineMembers members;
    private final BookingService bookings;
    private final ScheduleExecution execution;

    record BookingInput(Long scheduleId, String reason, String quote) {}
    record Terms(String quote, long sessionId, String courseType, LocalDateTime startAt, long priceFen,
                 long ruleId, LocalDateTime cutoffAt, long lateFen) {}
    record Grant(boolean enabled) {}
    record Decision(String decision, Long expectedFen, String reason) {}

    Terms terms(long scheduleId, boolean lock) {
        var linked = db.find("SELECT id FROM studio_lesson_session WHERE schedule_id=?", scheduleId);
        if (linked == null) return null;
        var session = lessons.session(linked.number("id"), lock);
        var schedule = lessons.schedule(scheduleId);
        var start = StudioLessonService.scheduleTime(schedule, "start_time");
        if (!"SCHEDULED".equals(schedule.text("status")) || !start.equals(session.time("start_at"))) {
            throw conflict("原排课已变化，请核对收费场次");
        }
        var rule = lessons.rule(session.text("course_type"), session.id("rule_id"), start);
        if (rule == null) return null;
        long price = session.number("price_fen");
        long ruleId = rule.number("id");
        var cutoff = start.minusMinutes(rule.number("free_minutes"));
        long late = fee(rule.text("late_kind"), (int) rule.number("late_value"), price);
        String token = LedgerStore.digest(JsonUtils.toJson(List.of(scheduleId, session.number("id"), session.number("version"),
                start, price, ruleId, cutoff, late)));
        return new Terms(token, session.number("id"), session.text("course_type"), start, price, ruleId, cutoff, late);
    }

    long book(long memberId, long actor, String key, BookingInput input) {
        if (input.scheduleId() == null) throw invalid("请选择排课");
        String reason = required(input.reason(), 255, "代约原因");
        long userId = members.userId(memberId);
        return bookings.bookWithCommand(userId, input.scheduleId(), null, null, create ->
                db.command(actor, key, "OFFLINE_BOOK", List.of(memberId, input), () -> {
                    db.lockMember(memberId);
                    Terms terms = input.quote() == null || input.quote().isBlank() ? null : terms(input.scheduleId(), true);
                    if (input.quote() != null && !input.quote().isBlank()
                            && (terms == null || !terms.quote().equals(input.quote()))) {
                        throw conflict("预约条款已变化，请重新核对");
                    }
                    long bookingId = create.get();
                    var booked = bookings.requireBooking(bookingId);
                    if (terms != null) {
                        if (booked.getUserPackageId() != null) throw conflict("课包预约不可同时约定余额扣费");
                        db.update("""
                                INSERT INTO studio_booking_terms(booking_id,member_id,schedule_id,session_id,course_type,
                                    start_at,price_fen,rule_id,cutoff_at,late_fen) VALUES(?,?,?,?,?,?,?,?,?,?)
                                """, bookingId, memberId, input.scheduleId(), terms.sessionId(), terms.courseType(),
                                terms.startAt(), terms.priceFen(), terms.ruleId(), terms.cutoffAt(), terms.lateFen());
                    }
                    db.audit(actor, "OFFLINE_BOOK", bookingId, Map.of("memberId", memberId, "reason", reason,
                            "scheduleId", input.scheduleId(), "agreedTerms", terms != null));
                    return bookingId;
                }));
    }

    @Transactional
    public long grant(long operatorId, long admin, String key, Grant input) {
        return db.command(admin, key, "LATE_FEE_GRANT", List.of(operatorId, input), () -> {
            if (db.find("""
                    SELECT u.id FROM sys_user u JOIN sys_user_role ur ON ur.user_id=u.id AND ur.deleted=0
                    JOIN sys_role r ON r.id=ur.role_id AND r.deleted=0 AND r.status='ACTIVE'
                    WHERE u.id=? AND u.deleted=0 AND r.code IN ('COACH','STAFF','STORE_MANAGER') LIMIT 1
                    """, operatorId) == null) throw invalid("请选择教练、前台或店长账号");
            db.update("""
                    INSERT INTO studio_late_fee_grant(operator_id,enabled,changed_by,changed_at) VALUES(?,?,?,?)
                    ON DUPLICATE KEY UPDATE enabled=?,changed_by=?,changed_at=?
                    """, operatorId, input.enabled() ? 1 : 0, admin, StudioTime.now(),
                    input.enabled() ? 1 : 0, admin, StudioTime.now());
            db.audit(admin, "LATE_FEE_GRANT", operatorId, input);
            return operatorId;
        });
    }

    List<Map<String, Object>> grants() {
        return db.view("""
                SELECT g.operator_id AS id,g.operator_id,g.enabled,g.changed_at,u.name FROM studio_late_fee_grant g
                JOIN sys_user u ON u.id=g.operator_id AND u.deleted=0 ORDER BY g.operator_id
                """);
    }

    boolean allowed(LoginUser actor, boolean lock) {
        if (actor.isAdminRole()) return true;
        if (!actor.isCoach() && !actor.getRoles().contains("STAFF") && !actor.getRoles().contains("STORE_MANAGER")) return false;
        if (actor.isCoach() && actor.getCoachId() == null) return false;
        var grant = db.find("""
                SELECT g.enabled FROM studio_late_fee_grant g
                JOIN sys_user u ON u.id=g.operator_id AND u.deleted=0 AND u.status='ACTIVE'
                WHERE g.operator_id=?
                """ + (lock ? " FOR UPDATE" : ""), actor.getId());
        return grant != null && grant.number("enabled") == 1;
    }

    List<Map<String, Object>> cases(LoginUser actor, long before) {
        requireAllowed(actor, false);
        Long coachId = scopedCoach(actor);
        return db.view("""
                SELECT t.booking_id,
                    CASE WHEN t.state='ELIGIBLE' AND cs.status='CANCELLED' THEN 'STORE_CANCEL' ELSE t.state END AS state,
                    CASE WHEN t.state='ELIGIBLE' AND t.start_at<=? AND cs.status<>'CANCELLED' THEN 1 ELSE 0 END AS ready,
                    t.start_at,t.cancelled_at,t.late_fen,t.course_type,s.course_name,s.coach_name,
                    CASE WHEN u.openid IS NULL THEN '线下会员' ELSE u.nickname END AS nickname
                FROM studio_booking_terms t JOIN studio_lesson_session s ON s.id=t.session_id
                JOIN course_schedule cs ON cs.id=t.schedule_id AND cs.deleted=0
                JOIN member m ON m.id=t.member_id AND m.deleted=0 JOIN `user` u ON u.id=m.user_id AND u.deleted=0
                WHERE t.booking_id<? AND t.state IN ('ELIGIBLE','WAITING_BALANCE','WAIVED','POSTED','REVERSED')
                    AND (? IS NULL OR cs.coach_id=?)
                ORDER BY t.booking_id DESC LIMIT 30
                """, StudioTime.now(), before, coachId, coachId);
    }

    List<Map<String, Object>> memberCases(long memberId, long before) {
        return db.view("""
                SELECT t.booking_id,t.state,t.start_at,t.late_fen,t.course_type,s.course_name,s.coach_name
                FROM studio_booking_terms t JOIN studio_lesson_session s ON s.id=t.session_id
                WHERE t.member_id=? AND t.booking_id<? AND t.state IN ('WAITING_BALANCE','WAIVED','POSTED','REVERSED')
                ORDER BY t.booking_id DESC LIMIT 30
                """, memberId, before);
    }

    long decide(long bookingId, LoginUser actor, String key, Decision input) {
        requireAllowed(actor, false);
        var initial = db.require("SELECT * FROM studio_booking_terms WHERE booking_id=?", bookingId);
        long memberId = initial.number("member_id");
        long scheduleId = initial.number("schedule_id");
        return execution.run(scheduleId, members.userId(memberId), false, () -> {
            requireAllowed(actor, true);
            checkScope(initial, actor);
            return db.command(actor.getId(), key, "LATE_FEE_DECIDE", List.of(bookingId, input), () -> {
                db.lockMember(memberId);
                var term = db.require("SELECT * FROM studio_booking_terms WHERE booking_id=? FOR UPDATE", bookingId);
                String decision = oneOf(input.decision(), "处理方式", "CHARGE", "WAIVE");
                String reason = required(input.reason(), 255, "处理原因");
                if (!"ELIGIBLE".equals(term.text("state"))) throw conflict("取消费已处理或当前不符合收费条件");
                assertEligible(term);
                long amount = term.number("late_fen");
                if (input.expectedFen() == null || input.expectedFen() != amount) throw conflict("费用已变化，请刷新");
                var profile = wallet.initProfile(memberId);
                String bucket = wallet.bucket(profile, term.text("course_type"));
                Long entry = null;
                String state = "WAIVED";
                if ("CHARGE".equals(decision)) {
                    if (wallet.balance(memberId, bucket) >= amount) {
                        entry = post(term, actor.getId(), bucket, reason);
                        state = "POSTED";
                    } else {
                        state = "WAITING_BALANCE";
                    }
                }
                db.update("""
                        UPDATE studio_booking_terms SET state=?,bucket=?,decision_by=?,decided_at=?,decision_reason=?,entry_id=?
                        WHERE booking_id=?
                        """, state, bucket, actor.getId(), StudioTime.now(), reason, entry, bookingId);
                db.audit(actor.getId(), "LATE_FEE_DECIDE", bookingId, Map.of("decision", decision, "state", state,
                        "amount", "CHARGE".equals(decision) ? amount : 0, "ruleId", term.number("rule_id"), "reason", reason));
                return bookingId;
            });
        });
    }

    long finish(long bookingId, long actor, String key, StudioWalletService.ReverseInput input, String action) {
        var initial = db.require("SELECT * FROM studio_booking_terms WHERE booking_id=?", bookingId);
        long memberId = initial.number("member_id");
        return execution.run(initial.number("schedule_id"), members.userId(memberId), false, () ->
                db.command(actor, key, action, List.of(bookingId, input), () -> {
                    db.lockMember(memberId);
                    var term = db.require("SELECT * FROM studio_booking_terms WHERE booking_id=? FOR UPDATE", bookingId);
                    String reason = required(input.reason(), 255, "原因");
                    switch (action) {
                        case "LATE_FEE_SETTLE" -> {
                            if (!"WAITING_BALANCE".equals(term.text("state"))) throw conflict("当前没有待结算取消费");
                            assertEligible(term);
                            long entry = post(term, actor, term.text("bucket"), reason);
                            db.update("UPDATE studio_booking_terms SET state='POSTED',entry_id=? WHERE booking_id=?", entry, bookingId);
                        }
                        case "LATE_FEE_WAIVE" -> {
                            if (!"WAITING_BALANCE".equals(term.text("state"))) throw conflict("当前没有待结算取消费");
                            db.update("UPDATE studio_booking_terms SET state='WAIVED' WHERE booking_id=?", bookingId);
                        }
                        case "LATE_FEE_REVERSE" -> {
                            if (!"POSTED".equals(term.text("state"))) throw conflict("当前没有可冲正取消费");
                            wallet.reverseEntry(memberId, term.number("entry_id"), actor, input.destination(), reason);
                            db.update("UPDATE studio_booking_terms SET state='REVERSED' WHERE booking_id=?", bookingId);
                        }
                        default -> throw invalid("处理方式无效");
                    }
                    db.audit(actor, action, bookingId, Map.of("reason", reason));
                    return bookingId;
                }));
    }

    private long post(LedgerStore.Row term, long actor, String bucket, String reason) {
        return wallet.post(term.number("member_id"), bucket, -term.number("late_fen"), "CANCELLATION", "BOOKING",
                term.number("booking_id"), null, term.time("cancelled_at"), actor, reason);
    }

    private Long scopedCoach(LoginUser actor) {
        return !actor.isAdminRole() && actor.isCoach() ? actor.getCoachId() : null;
    }

    private void checkScope(LedgerStore.Row term, LoginUser actor) {
        Long coach = scopedCoach(actor);
        if (coach != null && coach != db.require("SELECT coach_id FROM course_schedule WHERE id=? AND deleted=0 FOR UPDATE",
                term.number("schedule_id")).number("coach_id")) {
            throw BusinessException.of(ResultCode.FORBIDDEN, "只能处理本人授课的预约");
        }
    }

    private void requireAllowed(LoginUser actor, boolean lock) {
        if (!allowed(actor, lock)) throw BusinessException.of(ResultCode.FORBIDDEN, "未获临时取消费处理授权");
    }

    private void assertEligible(LedgerStore.Row term) {
        var booked = bookings.requireBooking(term.number("booking_id"));
        var schedule = lessons.schedule(term.number("schedule_id"));
        if (!"CANCELLED".equals(booked.getStatus()) || booked.getUserPackageId() != null
                || term.time("cancelled_at") == null || !term.time("cancelled_at").isAfter(term.time("cutoff_at"))
                || !term.time("cancelled_at").isBefore(term.time("start_at")) || term.number("late_fen") <= 0
                || "CANCELLED".equals(schedule.text("status"))
                || !term.time("start_at").equals(StudioLessonService.scheduleTime(schedule, "start_time"))) {
            throw conflict("预约或排课已变化，请交管理员核对");
        }
        if (StudioTime.now().isBefore(term.time("start_at"))) {
            throw conflict("请在原定开课时间之后确认取消费");
        }
    }
}
