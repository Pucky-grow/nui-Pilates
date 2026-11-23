package com.yoyi.pilates.ledger;

import static com.yoyi.pilates.ledger.LedgerRules.*;

import com.yoyi.pilates.common.util.JsonUtils;
import com.yoyi.pilates.common.util.StudioTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
class StudioLessonService {
    private final LedgerStore db;
    private final StudioWalletService wallet;

    record RuleInput(String courseType, LocalDateTime effectiveAt, Integer freeMinutes,
                     String lateKind, Integer lateValue, String absentKind, Integer absentValue) {}
    record SessionInput(Long id, Integer version, Long scheduleId, String sourceRef, String courseName,
                        String coachName, String courseType, LocalDateTime startAt, LocalDateTime endAt, String price, Long ruleId) {}
    record LessonInput(Long id, Integer version, Long sessionId, Long bookingId, String outcome,
                       LocalDateTime cancelledAt, String decision, String manualAmount,
                       String reason, Boolean duplicateConfirmed) {}
    record Review(String quote, String reason) {}
    record Quote(String token, long amountFen, String bucket, String basis, Long ruleId, long priceFen, long balanceFen,
                 DebitPreview preview) {}
    record DebitPreview(String status, String bucket, String basis, LocalDateTime lessonAt, long amountFen,
                        long balanceFen, Long balanceAtFen, long shortageFen) {}
    private record LessonFacts(String outcome, String decision, Long manualFen, LocalDateTime cancelled, LedgerStore.Row booking) {}
    private record Fee(String basis, Long ruleId, long priceFen, long amountFen) {}

    @Transactional
    public long addRule(long actor, String key, RuleInput input) {
        return db.command(actor, key, "FEE_RULE_ADD", input, () -> {
            String type = oneOf(input.courseType(), "课程类型", "GROUP", "PRIVATE");
            var effective = date(input.effectiveAt(), "生效时间");
            if (input.freeMinutes() == null || input.freeMinutes() < 0 || input.freeMinutes() > 10_080) {
                throw invalid("免费取消时限须为 0 至 10080 分钟");
            }
            int late = feeValue(input.lateKind(), input.lateValue());
            int absent = feeValue(input.absentKind(), input.absentValue());
            try {
                long id = db.insert("""
                        INSERT INTO studio_fee_rule(course_type,effective_at,free_minutes,late_kind,late_value,
                            absent_kind,absent_value,operator_id) VALUES(?,?,?,?,?,?,?,?)
                        """, type, effective, input.freeMinutes(), input.lateKind(), late, input.absentKind(), absent, actor);
                db.audit(actor, "FEE_RULE_ADD", id, input);
                return id;
            } catch (DuplicateKeyException ex) {
                throw conflict("该课型已设置相同生效时点的规则");
            }
        });
    }

    List<Map<String, Object>> rules(long before) {
        return db.view("SELECT * FROM studio_fee_rule WHERE id<? ORDER BY id DESC LIMIT 30", before);
    }

    LedgerStore.Row rule(String type, Long chosen, LocalDateTime at) {
        if (chosen != null) {
            return db.require("SELECT * FROM studio_fee_rule WHERE id=? AND course_type=? AND effective_at<=?", chosen, type, at);
        }
        return db.find("""
                SELECT * FROM studio_fee_rule WHERE course_type=? AND effective_at<=?
                ORDER BY effective_at DESC LIMIT 1
                """, type, at);
    }

    @Transactional
    public long saveSession(long actor, String key, SessionInput input) {
        return db.command(actor, key, "SESSION_SAVE", input, () -> {
            String type = oneOf(input.courseType(), "课程类型", "GROUP", "PRIVATE");
            var schedule = input.scheduleId() == null ? null : schedule(input.scheduleId());
            String source = optional(input.sourceRef(), 128, "来源编号");
            String course = schedule == null ? required(input.courseName(), 64, "课程名称") : schedule.text("course_name");
            String coach = schedule == null ? required(input.coachName(), 64, "教练") : schedule.text("coach_name");
            String original = schedule == null ? null : schedule.text("original_type");
            if (List.of("GROUP", "PRIVATE").contains(Objects.requireNonNullElse(original, "")) && !original.equals(type)) {
                throw invalid("课程类型须与原排课一致");
            }
            var start = schedule == null ? past(input.startAt(), "开课时间") : scheduleTime(schedule, "start_time");
            var end = schedule != null ? scheduleTime(schedule, "end_time")
                    : input.endAt() == null ? null : past(input.endAt(), "下课时间");
            if (end != null && !end.isAfter(start)) throw invalid("下课时间须晚于开课时间");
            long price = fen(input.price());
            if (input.ruleId() != null) rule(type, input.ruleId(), start);
            // A start-only historical session is a point in time. Equal bounds fit the installed NOT NULL schema.
            var endBound = Objects.requireNonNullElse(end, start);
            long id;
            try {
                if (input.id() == null) {
                    id = db.insert("""
                            INSERT INTO studio_lesson_session(schedule_id,source_ref,course_name,coach_name,original_type,
                                course_type,start_at,end_at,price_fen,rule_id) VALUES(?,?,?,?,?,?,?,?,?,?)
                            """, input.scheduleId(), source, course, coach, original, type, start, endBound, price, input.ruleId());
                } else {
                    id = input.id();
                    var old = session(id, true);
                    if (!Objects.equals(old.id("schedule_id"), input.scheduleId())
                            || input.version() == null || old.number("version") != input.version()) throw conflict("场次已变化，请刷新");
                    if (db.find("SELECT id FROM studio_member_lesson WHERE session_id=? AND state='VERIFIED' LIMIT 1", id) != null
                            || db.find("SELECT booking_id FROM studio_booking_terms WHERE session_id=? LIMIT 1", id) != null) {
                        throw conflict("场次已有核实记录或预约约定，费用与时间已锁定");
                    }
                    if (end == null && start.equals(old.time("start_at")) && old.time("end_at") != null) {
                        endBound = old.time("end_at");
                    }
                    db.audit(actor, "SESSION_PRIOR", id, old.values());
                    db.update("""
                            UPDATE studio_lesson_session SET source_ref=?,course_name=?,coach_name=?,course_type=?,
                                start_at=?,end_at=?,price_fen=?,rule_id=?,version=version+1 WHERE id=?
                            """, source, course, coach, type, start, endBound, price, input.ruleId(), id);
                }
            } catch (DuplicateKeyException ex) {
                throw conflict("该排课或来源编号已建立场次，请选择原场次");
            }
            db.audit(actor, "SESSION_SAVE", id, session(id, false).values());
            return id;
        });
    }

    LedgerStore.Row schedule(long id) {
        return db.require("""
                SELECT s.id,s.course_date,s.start_time,s.end_time,s.status,s.coach_id,c.name AS course_name,
                    c.type AS original_type,h.name AS coach_name
                FROM course_schedule s JOIN course c ON c.id=s.course_id JOIN coach h ON h.id=s.coach_id
                WHERE s.id=? AND s.deleted=0
                """, id);
    }

    static LocalDateTime scheduleTime(LedgerStore.Row schedule, String key) {
        return LocalDateTime.of((LocalDate) schedule.values().get("course_date"), (LocalTime) schedule.values().get(key));
    }

    private static final String SESSION_SELECT = """
            SELECT id,schedule_id,source_ref,course_name,coach_name,original_type,course_type,start_at,
                NULLIF(end_at,start_at) AS end_at,price_fen,rule_id,version FROM studio_lesson_session
            """;

    LedgerStore.Row session(long id, boolean lock) {
        return db.require(SESSION_SELECT + "WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    }

    List<Map<String, Object>> sessions(String keyword, long before) {
        String search = "%" + Objects.requireNonNullElse(keyword, "") + "%";
        return db.view(SESSION_SELECT + """
                WHERE id<? AND (course_name LIKE ? OR coach_name LIKE ? OR source_ref LIKE ?)
                ORDER BY id DESC LIMIT 30
                """, before, search, search, search);
    }

    @Transactional
    public long saveLesson(long memberId, long actor, String key, LessonInput input) {
        return db.command(actor, key, "LESSON_SAVE", List.of(memberId, input), () -> {
            db.lockMember(memberId);
            var session = inputSession(input, true);
            var facts = inputFacts(memberId, session, input);
            Long bookingId = facts.booking() == null ? null : facts.booking().number("id");
            String reason = required(input.reason(), 255, "凭据或原因");
            if (bookingId == null && session.text("source_ref") == null) {
                var duplicate = db.find("""
                        SELECT l.id FROM studio_member_lesson l JOIN studio_lesson_session s ON s.id=l.session_id
                        WHERE l.member_id=? AND s.start_at=? AND s.coach_name=? AND l.session_id<>?
                        AND l.id<>? AND l.state NOT IN ('REVERSED','VOIDED') LIMIT 1
                        """, memberId, session.time("start_at"), session.text("coach_name"), input.sessionId(),
                        Objects.requireNonNullElse(input.id(), 0L));
                if (duplicate != null) {
                    if (!Boolean.TRUE.equals(input.duplicateConfirmed())) {
                        throw conflict("发现同一时间和教练的补录 #" + duplicate.number("id") + "，请核对。确属另一节时选中重复核对并注明依据");
                    }
                    db.audit(actor, "LESSON_DUPLICATE_CONFIRMED", memberId, Map.of("matchedId", duplicate.number("id"), "reason", reason));
                }
            }
            long id;
            try {
                if (input.id() == null) {
                    id = db.insert("""
                            INSERT INTO studio_member_lesson(member_id,session_id,booking_id,outcome,cancelled_at,
                                decision,manual_fen,reason,recorded_at) VALUES(?,?,?,?,?,?,?,?,?)
                            """, memberId, input.sessionId(), bookingId, facts.outcome(), facts.cancelled(),
                            facts.decision(), facts.manualFen(), reason, StudioTime.now());
                } else {
                    id = input.id();
                    var old = lesson(memberId, id);
                    if (!List.of("PENDING_VERIFY", "RETURNED", "REVERSED").contains(old.text("state"))
                            || input.version() == null || old.number("version") != input.version()) {
                        throw conflict("课程记录已变化，请刷新");
                    }
                    db.audit(actor, "LESSON_PRIOR", id, old.values());
                    db.update("""
                            UPDATE studio_member_lesson SET session_id=?,booking_id=?,outcome=?,cancelled_at=?,decision=?,
                                manual_fen=?,reason=?,state='PENDING_VERIFY',settlement='UNPOSTED',version=version+1,
                                rule_id=NULL,price_fen=NULL,amount_fen=NULL,bucket=NULL,entry_id=NULL,verified_at=NULL WHERE id=?
                            """, input.sessionId(), bookingId, facts.outcome(), facts.cancelled(), facts.decision(), facts.manualFen(), reason, id);
                }
            } catch (DuplicateKeyException ex) {
                throw conflict("该会员已有此场次或预约的补录，请核对原记录");
            }
            db.audit(actor, "LESSON_SAVE", id, lesson(memberId, id).values());
            return id;
        });
    }

    private LedgerStore.Row inputSession(LessonInput input, boolean lock) {
        if (input.sessionId() == null) throw invalid("请选择课程场次");
        var session = session(input.sessionId(), lock);
        var latest = Objects.requireNonNullElse(session.time("end_at"), session.time("start_at"));
        if (latest.isAfter(StudioTime.now())) throw invalid("只能补录已发生的历史场次");
        return session;
    }

    private LessonFacts inputFacts(long memberId, LedgerStore.Row session, LessonInput input) {
        var booking = findBooking(memberId, session, input.bookingId());
        String outcome = oneOf(input.outcome(), "结果", "ATTENDED", "NO_SHOW", "TIMELY_CANCEL", "LATE_CANCEL", "STORE_CANCEL", "UNKNOWN");
        String decision = oneOf(input.decision(), "结算方式", "RULE", "MANUAL", "WAIVE", "EXTERNAL", "RECORD_ONLY");
        Long manual = "MANUAL".equals(decision) || (input.manualAmount() != null && !input.manualAmount().isBlank())
                ? fen(input.manualAmount()) : null;
        var cancelled = input.cancelledAt() != null ? past(input.cancelledAt(), "实际取消时间")
                : booking == null ? null : booking.time("cancelled_at");
        if ("NO_SHOW".equals(outcome) && cancelled != null) throw invalid("已取消的记录不能标为爽约");
        return new LessonFacts(outcome, decision, manual, cancelled, booking);
    }

    private LedgerStore.Row findBooking(long memberId, LedgerStore.Row session, Long bookingId) {
        Long scheduleId = session.id("schedule_id");
        if (scheduleId == null) {
            if (bookingId != null) throw invalid("线下场次不能关联其他预约");
            return null;
        }
        var matching = db.list("""
                SELECT b.id,b.status,b.user_package_id,b.cancelled_at FROM booking b
                JOIN member m ON m.user_id=b.user_id WHERE m.id=? AND b.schedule_id=? AND b.deleted=0
                    AND (? IS NULL OR b.id=?) ORDER BY b.id DESC LIMIT 2
                """, memberId, scheduleId, bookingId, bookingId);
        if (matching.isEmpty() && bookingId != null) throw invalid("预约不属于该会员或场次");
        if (matching.size() > 1) throw invalid("该场次有多条预约，请填写原预约编号对应的 ID");
        return matching.stream().findFirst().orElse(null);
    }

    @Transactional(readOnly = true)
    public DebitPreview preview(long memberId, LessonInput input) {
        db.require("SELECT id FROM member WHERE id=? AND deleted=0", memberId);
        var session = inputSession(input, false);
        var profile = wallet.profile(memberId);
        var fee = calculateFee(profile, session, inputFacts(memberId, session, input));
        return debitPreview(profile, memberId, wallet.bucket(profile, session.text("course_type")), fee.basis(),
                fee.amountFen(), session.time("start_at"));
    }

    @Transactional(readOnly = true)
    public Quote quote(long memberId, long id) {
        var lesson = lesson(memberId, id);
        if ("WAITING_BALANCE".equals(lesson.text("settlement"))) {
            var preview = pendingDebit(memberId, lesson, false);
            return new Quote(null, preview.amountFen(), preview.bucket(), preview.basis(), lesson.id("rule_id"),
                    lesson.number("price_fen"), preview.balanceFen(), preview);
        }
        if (!"PENDING_VERIFY".equals(lesson.text("state"))) throw conflict("补录已处理");
        return quote(memberId, lesson, session(lesson.number("session_id"), false));
    }

    private Quote quote(long memberId, LedgerStore.Row lesson, LedgerStore.Row session) {
        var profile = wallet.profile(memberId);
        String bucket = wallet.bucket(profile, session.text("course_type"));
        var booking = findBooking(memberId, session, lesson.id("booking_id"));
        if (booking != null && !Objects.equals(lesson.id("booking_id"), booking.id("id"))) {
            throw conflict("发现原预约，请编辑补录以关联预约");
        }
        var fee = calculateFee(profile, session, new LessonFacts(lesson.text("outcome"), lesson.text("decision"),
                lesson.number("manual_fen"), lesson.time("cancelled_at"), booking));
        var preview = debitPreview(profile, memberId, bucket, fee.basis(), fee.amountFen(), session.time("start_at"));
        String token = LedgerStore.digest(JsonUtils.toJson(List.of(lesson.number("id"), lesson.number("version"),
                session.number("version"), profile == null ? 0 : profile.number("version"), Objects.requireNonNullElse(fee.ruleId(), 0L),
                bucket, fee.amountFen(), fee.basis(), fee.priceFen())));
        return new Quote(token, fee.amountFen(), bucket, fee.basis(), fee.ruleId(), fee.priceFen(), preview.balanceFen(), preview);
    }

    private DebitPreview pendingDebit(long memberId, LedgerStore.Row lesson, boolean lock) {
        var session = session(lesson.number("session_id"), lock);
        return debitPreview(wallet.profile(memberId), memberId, lesson.text("bucket"), lesson.text("settlement_basis"),
                lesson.number("amount_fen"), session.time("start_at"));
    }

    private DebitPreview debitPreview(LedgerStore.Row profile, long memberId, String bucket, String basis,
                                      long amount, LocalDateTime at) {
        if (amount == 0) return new DebitPreview("NO_WALLET_DEBIT", bucket, basis, at, amount, wallet.balance(memberId, bucket), null, 0);
        var available = wallet.debitAvailability(profile, memberId, bucket, at);
        String status = "READY";
        if (available.balanceAtFen() != null && available.balanceAtFen() < amount) status = "HISTORICAL_BALANCE_LOW";
        else if (available.historicalLimitFen() != null && available.historicalLimitFen() < amount) status = "LATER_HISTORICAL_BALANCE_LOW";
        else if (available.balanceFen() < amount) status = "CURRENT_BALANCE_LOW";
        return new DebitPreview(status, bucket, basis, at, amount, available.balanceFen(), available.balanceAtFen(),
                Math.max(0, amount - available.availableFen()));
    }

    private Fee calculateFee(LedgerStore.Row profile, LedgerStore.Row session, LessonFacts facts) {
        var at = session.time("start_at");
        String type = session.text("course_type");
        var rule = rule(type, session.id("rule_id"), at);
        Long ruleId = rule == null ? null : rule.number("id");
        String outcome = facts.outcome();
        String decision = facts.decision();
        var booking = facts.booking();
        var terms = booking == null ? null : db.find("SELECT * FROM studio_booking_terms WHERE booking_id=?", booking.number("id"));
        long price = terms == null ? session.number("price_fen") : terms.number("price_fen");
        String basis;
        long amount = 0;
        if (wallet.covered(profile, at)) {
            basis = "OPENING_COVERED";
        } else if (booking != null && booking.id("user_package_id") != null) {
            basis = "PACKAGE";
        } else if (booking != null && "LATE_CANCEL".equals(outcome) && session.id("schedule_id") != null
                && "CANCELLED".equals(schedule(session.number("schedule_id")).text("status"))) {
            basis = "STORE_CANCEL";
        } else if (terms != null && "LATE_CANCEL".equals(outcome) && !"BOOKED".equals(terms.text("state"))) {
            if (List.of("RULE", "MANUAL").contains(decision)) throw conflict("请在该预约的临时取消费记录处理");
            basis = "BOOKING_TERMS";
        } else if (!List.of("ATTENDED", "NO_SHOW", "LATE_CANCEL").contains(outcome)
                || !List.of("RULE", "MANUAL").contains(decision)) {
            basis = List.of("RULE", "MANUAL").contains(decision) ? "NO_CHARGE" : decision;
        } else {
            if (booking != null && !supportsCharge(outcome, booking.text("status"))) {
                throw conflict("核实结果与系统预约状态冲突，请核对后仅记录或更正");
            }
            if ("LATE_CANCEL".equals(outcome)) {
                var cancelled = facts.cancelled();
                if (booking != null && !Objects.equals(cancelled, booking.time("cancelled_at"))) {
                    throw conflict("取消时间与原预约不一致，请核对");
                }
                if (cancelled == null || !cancelled.isBefore(at) || (rule != null && !late(cancelled, at, (int) rule.number("free_minutes")))) {
                    throw invalid("请核对实际取消时间和免费取消时限");
                }
            }
            basis = decision;
            if ("MANUAL".equals(decision)) {
                amount = facts.manualFen();
                if (amount > price) throw invalid("扣费不能超过本节确认价");
            } else if ("ATTENDED".equals(outcome)) {
                amount = price;
            } else {
                if (rule == null) throw invalid("没有适用历史规则，请核对后选择手动金额或仅记录");
                String prefix = "NO_SHOW".equals(outcome) ? "absent" : "late";
                amount = fee(rule.text(prefix + "_kind"), (int) rule.number(prefix + "_value"), price);
            }
        }
        return new Fee(basis, ruleId, price, amount);
    }

    private boolean supportsCharge(String outcome, String status) {
        return switch (outcome) {
            case "ATTENDED" -> List.of("SIGNED_IN", "COMPLETED").contains(status);
            case "NO_SHOW" -> "NO_SHOW".equals(status);
            case "LATE_CANCEL" -> "CANCELLED".equals(status);
            default -> false;
        };
    }

    @Transactional
    public long verify(long memberId, long id, long actor, String key, Review input) {
        return db.command(actor, key, "LESSON_VERIFY", List.of(memberId, id, input), () -> {
            String reason = required(input.reason(), 255, "核实原因");
            db.lockMember(memberId);
            var lesson = lesson(memberId, id);
            if (!"PENDING_VERIFY".equals(lesson.text("state"))) throw conflict("补录已处理");
            var session = session(lesson.number("session_id"), true);
            Quote quote = quote(memberId, lesson, session);
            if (!quote.token().equals(input.quote())) throw conflict("费用、场次或账户已变化，请重新核对");
            wallet.initProfile(memberId);
            Long entry = null;
            String settlement = "NO_WALLET_DEBIT";
            if (quote.amountFen() > 0) {
                if ("READY".equals(quote.preview().status())) {
                    entry = post(memberId, id, actor, quote.bucket(), quote.amountFen(), session.time("start_at"), reason);
                    settlement = "POSTED";
                } else {
                    settlement = "WAITING_BALANCE";
                }
            }
            db.update("""
                    UPDATE studio_member_lesson SET state='VERIFIED',settlement=?,rule_id=?,price_fen=?,amount_fen=?,
                      bucket=?,entry_id=?,verified_at=?,version=version+1,settlement_basis=? WHERE id=?
                    """, settlement, quote.ruleId(), quote.priceFen(), quote.amountFen(), quote.bucket(), entry,
                    StudioTime.now(), quote.basis(), id);
            db.audit(actor, "LESSON_VERIFY", id, Map.of("reason", reason, "quote", quote, "settlement", settlement));
            return id;
        });
    }

    private long post(long memberId, long id, long actor, String bucket, long amount, LocalDateTime time, String reason) {
        return wallet.post(memberId, bucket, -amount, "COURSE", "LESSON", id, null, time, actor, reason);
    }

    @Transactional
    public long settle(long memberId, long id, long actor, String key, StudioWalletService.VersionAction input) {
        return db.command(actor, key, "LESSON_SETTLE", List.of(memberId, id, input), () -> {
            db.lockMember(memberId);
            var lesson = lesson(memberId, id);
            if (lesson.number("version") != input.version() || !"WAITING_BALANCE".equals(lesson.text("settlement"))) {
                throw conflict("课程不处于待结算状态");
            }
            String reason = required(input.reason(), 255, "续结算原因");
            var preview = pendingDebit(memberId, lesson, true);
            if (!"READY".equals(preview.status())) {
                throw conflict("对应账户余额或该时点历史余额不足");
            }
            long entry = post(memberId, id, actor, preview.bucket(), preview.amountFen(), preview.lessonAt(), reason);
            db.update("UPDATE studio_member_lesson SET settlement='POSTED',entry_id=?,version=version+1 WHERE id=?", entry, id);
            db.audit(actor, "LESSON_SETTLE", id, Map.of("entryId", entry, "reason", reason));
            return id;
        });
    }

    @Transactional
    public long returnLesson(long memberId, long id, long actor, String key, StudioWalletService.VersionAction input) {
        return db.command(actor, key, "LESSON_RETURN", List.of(memberId, id, input), () -> {
            db.lockMember(memberId);
            var lesson = lesson(memberId, id);
            if (lesson.number("version") != input.version()
                    || !("PENDING_VERIFY".equals(lesson.text("state")) || "WAITING_BALANCE".equals(lesson.text("settlement")))) {
                throw conflict("课程状态不能退回");
            }
            String reason = required(input.reason(), 255, "退回原因");
            db.audit(actor, "LESSON_RETURN", id, Map.of("reason", reason, "prior", lesson.values()));
            db.update("""
                    UPDATE studio_member_lesson SET state='RETURNED',settlement='UNPOSTED',verified_at=NULL,version=version+1
                    WHERE id=?
                    """, id);
            return id;
        });
    }

    @Transactional
    public long reverse(long memberId, long id, long actor, String key, StudioWalletService.ReverseInput input) {
        return db.command(actor, key, "LESSON_REVERSE", List.of(memberId, id, input), () -> {
            db.lockMember(memberId);
            var lesson = lesson(memberId, id);
            if (!"VERIFIED".equals(lesson.text("state")) || !"POSTED".equals(lesson.text("settlement"))) {
                throw conflict("该课程无有效扣费可冲正");
            }
            String reason = required(input.reason(), 255, "冲正原因");
            wallet.reverseEntry(memberId, lesson.number("entry_id"), actor, input.destination(), reason);
            db.update("UPDATE studio_member_lesson SET state='REVERSED',settlement='REVERSED',version=version+1 WHERE id=?", id);
            db.audit(actor, "LESSON_REVERSE", id, Map.of("reason", reason));
            return id;
        });
    }

    LedgerStore.Row lesson(long memberId, long id) {
        return db.require("SELECT * FROM studio_member_lesson WHERE member_id=? AND id=?", memberId, id);
    }

    List<Map<String, Object>> lessons(long memberId, long before, boolean admin) {
        if (admin) {
            return db.view("""
                    SELECT l.*,s.course_name,s.coach_name,s.course_type,s.start_at,s.schedule_id
                    FROM studio_member_lesson l JOIN studio_lesson_session s ON s.id=l.session_id
                    WHERE l.member_id=? AND l.id<? ORDER BY l.id DESC LIMIT 30
                    """, memberId, before);
        }
        LocalDateTime cursorTime = null;
        if (before != Long.MAX_VALUE) {
            cursorTime = db.require("""
                    SELECT s.start_at FROM studio_member_lesson l JOIN studio_lesson_session s ON s.id=l.session_id
                    WHERE l.member_id=? AND l.id=?
                    """, memberId, before).time("start_at");
        }
        return db.view("""
                SELECT l.id,l.booking_id,l.outcome,l.settlement,l.settlement_basis,l.amount_fen,l.bucket,
                    CASE WHEN l.settlement='NO_WALLET_DEBIT' THEN l.manual_fen ELSE NULL END AS reported_fen,
                    s.course_name,s.coach_name,s.course_type,s.start_at,NULLIF(s.end_at,s.start_at) AS end_at
                FROM studio_member_lesson l JOIN studio_lesson_session s ON s.id=l.session_id
                WHERE l.member_id=? AND l.state IN ('VERIFIED','REVERSED')
                    AND (? IS NULL OR s.start_at<? OR (s.start_at=? AND l.id<?))
                ORDER BY s.start_at DESC,l.id DESC LIMIT 30
                """, memberId, cursorTime, cursorTime, cursorTime, before);
    }
}
