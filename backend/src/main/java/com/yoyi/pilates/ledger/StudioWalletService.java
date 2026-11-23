package com.yoyi.pilates.ledger;

import static com.yoyi.pilates.ledger.LedgerRules.*;

import com.yoyi.pilates.common.util.StudioTime;
import com.yoyi.pilates.system.application.SystemConfigService;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
class StudioWalletService {
    static final String DEFAULT_MODE_KEY = "ledger.default_wallet_mode";
    private final LedgerStore db;
    private final SystemConfigService config;

    record Allocation(String general, String group, String privateAmount) {
        Map<String, Long> amounts() {
            return Map.of("GENERAL", optionalFen(general), "GROUP", optionalFen(group), "PRIVATE", optionalFen(privateAmount));
        }
    }

    record ModeChange(String mode, int version, Allocation allocation, String reason) {}
    record ImportSetup(String mode, LocalDateTime cutoffAt, String reason) {}
    record ReceiptInput(Long id, Integer version, String kind, String amount, Allocation allocation,
                        String channel, String externalRef, Long originalId, LocalDateTime businessAt,
                        String reason, Boolean duplicateConfirmed) {}
    record VersionAction(int version, String reason) {}
    record ReverseInput(String destination, String reason) {}

    String defaultMode() {
        return "SPLIT".equals(config.get(DEFAULT_MODE_KEY, "SHARED")) ? "SPLIT" : "SHARED";
    }

    @Transactional
    public long setDefault(long actor, String key, String mode, String reason) {
        return db.command(actor, key, "DEFAULT_MODE", List.of(String.valueOf(mode), String.valueOf(reason)), () -> {
            String next = oneOf(mode, "余额模式", "SHARED", "SPLIT");
            String why = required(reason, 255, "原因");
            db.update("""
                    INSERT INTO system_config(config_key,config_value) VALUES(?,?)
                    ON DUPLICATE KEY UPDATE config_value=?,deleted=0
                    """, DEFAULT_MODE_KEY, next, next);
            config.evict(DEFAULT_MODE_KEY);
            db.audit(actor, "DEFAULT_MODE", 0, Map.of("mode", next, "reason", why));
            return 0;
        });
    }

    LedgerStore.Row profile(long memberId) {
        return db.find("SELECT * FROM studio_wallet_profile WHERE member_id=?", memberId);
    }

    LedgerStore.Row initProfile(long memberId) {
        LedgerStore.Row profile = profile(memberId);
        if (profile == null) {
            db.update("INSERT INTO studio_wallet_profile(member_id,mode) VALUES(?,?)", memberId, defaultMode());
            profile = profile(memberId);
        }
        return profile;
    }

    String mode(LedgerStore.Row profile) {
        return profile == null ? defaultMode() : profile.text("mode");
    }

    String bucket(long memberId, String courseType) {
        return bucket(profile(memberId), courseType);
    }

    String bucket(LedgerStore.Row profile, String courseType) {
        return "SHARED".equals(mode(profile)) ? "GENERAL" : oneOf(courseType, "课程类型", "GROUP", "PRIVATE");
    }

    private List<String> active(String mode) {
        return "SHARED".equals(mode) ? List.of("GENERAL") : List.of("GROUP", "PRIVATE");
    }

    Map<String, Object> overview(long memberId, boolean admin) {
        LedgerStore.Row profile = profile(memberId);
        String mode = mode(profile);
        var rows = new ArrayList<Map<String, Object>>();
        long sum = 0;
        for (String bucket : active(mode)) {
            long balance = balance(memberId, bucket);
            rows.add(Map.of("bucket", bucket, "balance_fen", balance));
            sum += balance;
        }
        var view = new LinkedHashMap<String, Object>();
        view.put("mode", mode);
        view.put("accounts", rows);
        view.put("balance_fen", sum);
        if (admin) {
            view.put("version", profile == null ? 0 : profile.number("version"));
            view.put("import_mode", profile == null ? null : profile.text("import_mode"));
            view.put("cutoff_at", profile == null ? null : profile.time("cutoff_at"));
            view.put("reconciliation", db.view("""
                    SELECT w.bucket,w.balance_fen,COALESCE(SUM(e.delta_fen),0) AS ledger_fen
                    FROM studio_wallet w LEFT JOIN studio_wallet_entry e
                    ON e.member_id=w.member_id AND e.bucket=w.bucket
                    WHERE w.member_id=? GROUP BY w.bucket,w.balance_fen ORDER BY w.bucket
                    """, memberId));
        }
        return view;
    }

    List<Map<String, Object>> entries(long memberId, long before, boolean admin) {
        if (admin) {
            return db.view("""
                    SELECT * FROM studio_wallet_entry WHERE member_id=? AND id<?
                    ORDER BY id DESC LIMIT 30
                    """, memberId, before);
        }
        return db.view("""
                SELECT e.id,e.bucket,e.kind,e.delta_fen,e.after_fen,e.business_at,e.posted_at,e.reversal_of,
                    (SELECT r.id FROM studio_wallet_entry r WHERE r.reversal_of=e.id) AS reversed_by
                FROM studio_wallet_entry e WHERE e.member_id=? AND e.id<?
                ORDER BY e.id DESC LIMIT 30
                """, memberId, before);
    }

    long balance(long memberId, String bucket) {
        var wallet = db.find("SELECT balance_fen FROM studio_wallet WHERE member_id=? AND bucket=?", memberId, bucket);
        return wallet == null ? 0 : wallet.number("balance_fen");
    }

    long post(long memberId, String bucket, long delta, String kind, String sourceKind,
              long sourceId, Long reversalOf, LocalDateTime businessAt, long actor, String reason) {
        if (delta == 0) throw new IllegalArgumentException("Zero ledger entry");
        // All ledger writes lock the member row before reaching this method.
        var wallet = db.find("SELECT balance_fen,sequence_no FROM studio_wallet WHERE member_id=? AND bucket=? FOR UPDATE",
                memberId, bucket);
        long before = wallet == null ? 0 : wallet.number("balance_fen");
        long after = Math.addExact(before, delta);
        if (after < 0) throw conflict("对应账户余额不足");
        long sequence = wallet == null ? 1 : wallet.number("sequence_no") + 1;
        if (wallet == null) {
            db.update("INSERT INTO studio_wallet(member_id,bucket,balance_fen,sequence_no) VALUES(?,?,?,?)",
                    memberId, bucket, after, sequence);
        } else {
            db.update("UPDATE studio_wallet SET balance_fen=?,sequence_no=? WHERE member_id=? AND bucket=?",
                    after, sequence, memberId, bucket);
        }
        return db.insert("""
                INSERT INTO studio_wallet_entry(member_id,bucket,sequence_no,kind,delta_fen,before_fen,after_fen,
                    source_kind,source_id,reversal_of,business_at,posted_at,operator_id,reason)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, memberId, bucket, sequence, kind, delta, before, after, sourceKind, sourceId,
                reversalOf, businessAt, StudioTime.now(), actor, reason);
    }

    @Transactional
    public long changeMode(long memberId, long actor, String key, ModeChange input) {
        return db.command(actor, key, "WALLET_MODE", List.of(memberId, input), () -> {
            String next = oneOf(input.mode(), "余额模式", "SHARED", "SPLIT");
            String reason = required(input.reason(), 255, "原因");
            db.lockMember(memberId);
            var previous = profile(memberId);
            int version = previous == null ? 0 : (int) previous.number("version");
            if (version != input.version()) throw conflict("余额模式已变化，请刷新");
            String current = mode(previous);
            if (current.equals(next)) return memberId;
            if (db.find("SELECT id FROM studio_member_lesson WHERE member_id=? AND settlement='WAITING_BALANCE' LIMIT 1", memberId) != null
                    || db.find("SELECT booking_id FROM studio_booking_terms WHERE member_id=? AND state='WAITING_BALANCE' LIMIT 1", memberId) != null) {
                throw conflict("请先处理已核实待结算的费用");
            }
            initProfile(memberId);
            long total = 0;
            var before = new LinkedHashMap<String, Long>();
            for (String bucket : active(current)) {
                long amount = balance(memberId, bucket);
                before.put(bucket, amount);
                total += amount;
            }
            Map<String, Long> after;
            if ("SHARED".equals(next)) {
                after = Map.of("GENERAL", total);
            } else {
                if (total != 0 && input.allocation() == null) throw invalid("请填写小班与私教余额分配");
                after = input.allocation() == null ? Map.of() : input.allocation().amounts();
                if (after.getOrDefault("GENERAL", 0L) != 0 || after.values().stream().mapToLong(Long::longValue).sum() != total) {
                    throw invalid("小班与私教分配合计须等于当前余额");
                }
            }
            long transferId = Math.max(version, 1) + 1L;
            var at = StudioTime.now();
            for (String bucket : BUCKETS) {
                long delta = after.getOrDefault(bucket, 0L) - before.getOrDefault(bucket, 0L);
                if (delta != 0) post(memberId, bucket, delta, "TRANSFER", "MODE", transferId, null, at, actor, reason);
            }
            db.update("UPDATE studio_wallet_profile SET mode=?,version=version+1 WHERE member_id=?", next, memberId);
            db.audit(actor, "WALLET_MODE", memberId, Map.of("before", before, "after", after, "reason", reason, "version", transferId));
            return memberId;
        });
    }

    @Transactional
    public long setImport(long memberId, long actor, String key, ImportSetup input) {
        return db.command(actor, key, "IMPORT_SETUP", List.of(memberId, input), () -> {
            String mode = oneOf(input.mode(), "旧账方式", "REPLAY", "OPENING");
            LocalDateTime cutoff = past(input.cutoffAt(), "切换时点");
            String reason = required(input.reason(), 255, "原因");
            db.lockMember(memberId);
            initProfile(memberId);
            if (db.find("SELECT id FROM studio_wallet_entry WHERE member_id=? LIMIT 1", memberId) != null
                    || db.find("SELECT id FROM studio_receipt WHERE member_id=? AND state IN ('VERIFIED','VERIFIED_COVERED') LIMIT 1", memberId) != null
                    || db.find("SELECT id FROM studio_member_lesson WHERE member_id=? AND state='VERIFIED' LIMIT 1", memberId) != null) {
                throw conflict("已有核实或入账记录，不能重新设定旧账切换方式");
            }
            db.update("UPDATE studio_wallet_profile SET import_mode=?,cutoff_at=?,version=version+1 WHERE member_id=?",
                    mode, cutoff, memberId);
            db.audit(actor, "IMPORT_SETUP", memberId, input);
            return memberId;
        });
    }

    boolean covered(LedgerStore.Row profile, LocalDateTime date) {
        return profile != null && "OPENING".equals(profile.text("import_mode"))
                && !date.isAfter(profile.time("cutoff_at"));
    }

    record DebitAvailability(long balanceFen, Long balanceAtFen, Long historicalLimitFen) {
        long availableFen() {
            return historicalLimitFen == null ? balanceFen : Math.min(balanceFen, historicalLimitFen);
        }
    }

    boolean enoughAt(LedgerStore.Row profile, long memberId, String bucket, long amount, LocalDateTime at) {
        return debitAvailability(profile, memberId, bucket, at).availableFen() >= amount;
    }

    DebitAvailability debitAvailability(LedgerStore.Row profile, long memberId, String bucket, LocalDateTime at) {
        long current = balance(memberId, bucket);
        if (profile == null || !"REPLAY".equals(profile.text("import_mode"))
                || at.isAfter(profile.time("cutoff_at"))) return new DebitAvailability(current, null, null);
        long historicalBalance = 0;
        Long balanceAt = null;
        long minimum = Long.MAX_VALUE;
        for (var row : db.list("""
                SELECT delta_fen,business_at FROM studio_wallet_entry
                WHERE member_id=? AND bucket=? AND kind<>'TRANSFER' AND business_at<=?
                ORDER BY business_at,id
                """, memberId, bucket, profile.time("cutoff_at"))) {
            if (row.time("business_at").isAfter(at)) {
                if (balanceAt == null) balanceAt = historicalBalance;
                minimum = Math.min(minimum, historicalBalance);
            }
            historicalBalance += row.number("delta_fen");
        }
        return new DebitAvailability(current, balanceAt == null ? historicalBalance : balanceAt,
                Math.min(minimum, historicalBalance));
    }

    @Transactional
    public long saveReceipt(long memberId, long actor, String key, ReceiptInput input) {
        return db.command(actor, key, "RECEIPT_SAVE", List.of(memberId, input), () -> {
            String kind = oneOf(input.kind(), "单据类型", "TOPUP", "OPENING", "REFUND");
            String reason = required(input.reason(), 255, "原因");
            String channel = required(input.channel(), 32, "收款或退款渠道");
            String reference = optional(input.externalRef(), 128, "凭据编号");
            LocalDateTime at = past(input.businessAt(), "业务时间");
            long amount = fen(input.amount());
            if (amount == 0 && !"OPENING".equals(kind)) throw invalid("请输入大于零的金额");
            if ("REFUND".equals(kind) != (input.originalId() != null)) throw invalid("退款需要关联原充值编号");
            db.lockMember(memberId);
            var profile = initProfile(memberId);
            var amounts = input.allocation() == null && "SHARED".equals(mode(profile))
                    ? Map.of("GENERAL", amount) : input.allocation() == null ? Map.<String, Long>of() : input.allocation().amounts();
            validateAmounts(mode(profile), amounts, amount);
            if (reference == null && !"OPENING".equals(kind)) {
                var day = at.toLocalDate().atStartOfDay();
                var duplicate = db.find("""
                        SELECT id FROM studio_receipt WHERE member_id=? AND kind=? AND amount_fen=? AND id<>?
                        AND business_at>=? AND business_at<? AND state NOT IN ('REVERSED','VOIDED') LIMIT 1
                        """, memberId, kind, amount, Objects.requireNonNullElse(input.id(), 0L), day, day.plusDays(1));
                if (duplicate != null) {
                    if (!Boolean.TRUE.equals(input.duplicateConfirmed())) {
                        throw conflict("发现同日同金额单据 #" + duplicate.number("id") + "，请核对。确属另一笔时选中重复核对并注明依据");
                    }
                    db.audit(actor, "RECEIPT_DUPLICATE_CONFIRMED", memberId, Map.of("matchedId", duplicate.number("id"), "reason", reason));
                }
            }
            long id;
            try {
                if (input.id() == null) {
                    id = db.insert("""
                            INSERT INTO studio_receipt(member_id,kind,amount_fen,general_fen,group_fen,private_fen,
                              channel,external_ref,original_id,business_at,reason,recorded_at)
                            VALUES(?,?,?,?,?,?,?,?,?,?,?,?)
                            """, memberId, kind, amount, amounts.getOrDefault("GENERAL", 0L), amounts.getOrDefault("GROUP", 0L),
                            amounts.getOrDefault("PRIVATE", 0L), channel, reference, input.originalId(), at, reason, StudioTime.now());
                } else {
                    id = input.id();
                    var old = receipt(memberId, id);
                    if (!List.of("PENDING_VERIFY", "RETURNED", "REVERSED").contains(old.text("state"))
                            || input.version() == null || old.number("version") != input.version()) {
                        throw conflict("单据状态已变化，请刷新");
                    }
                    db.audit(actor, "RECEIPT_PRIOR", id, old.values());
                    db.update("""
                            UPDATE studio_receipt SET kind=?,amount_fen=?,general_fen=?,group_fen=?,private_fen=?,
                              channel=?,external_ref=?,original_id=?,business_at=?,reason=?,state='PENDING_VERIFY',
                              verified_at=NULL,version=version+1 WHERE id=?
                            """, kind, amount, amounts.getOrDefault("GENERAL", 0L), amounts.getOrDefault("GROUP", 0L),
                            amounts.getOrDefault("PRIVATE", 0L), channel, reference, input.originalId(), at, reason, id);
                }
            } catch (DuplicateKeyException ex) {
                throw conflict("该凭据编号已存在，请核对原记录");
            }
            db.audit(actor, "RECEIPT_SAVE", id, receipt(memberId, id).values());
            return id;
        });
    }

    @Transactional
    public long verifyReceipt(long memberId, long id, long actor, String key, VersionAction input, boolean returned) {
        return db.command(actor, key, returned ? "RECEIPT_RETURN" : "RECEIPT_VERIFY", List.of(memberId, id, input), () -> {
            db.lockMember(memberId);
            var row = receipt(memberId, id);
            if (!"PENDING_VERIFY".equals(row.text("state")) || row.number("version") != input.version()) {
                throw conflict("单据已处理或修改，请刷新");
            }
            String reason = required(input.reason(), 255, "核实或退回原因");
            if (returned) {
                db.update("UPDATE studio_receipt SET state='RETURNED',version=version+1 WHERE id=?", id);
                db.audit(actor, "RECEIPT_RETURN", id, Map.of("reason", reason));
                return id;
            }
            var profile = initProfile(memberId);
            var at = row.time("business_at");
            String kind = row.text("kind");
            var amounts = Map.of("GENERAL", row.number("general_fen"), "GROUP", row.number("group_fen"),
                    "PRIVATE", row.number("private_fen"));
            boolean covered = covered(profile, at);
            if ("OPENING".equals(kind)) {
                if (!"OPENING".equals(profile.text("import_mode")) || !at.equals(profile.time("cutoff_at"))) {
                    throw conflict("期初单据须匹配已确认的期初切换时点");
                }
                if (db.find("SELECT id FROM studio_receipt WHERE member_id=? AND kind='OPENING' AND state='VERIFIED' LIMIT 1", memberId) != null) {
                    throw conflict("已核实过期初余额");
                }
            }
            if ("REFUND".equals(kind)) validateRefund(memberId, row);
            if (!covered || "OPENING".equals(kind)) {
                validateAmounts(mode(profile), amounts, row.number("amount_fen"));
                for (String bucket : BUCKETS) {
                    long amount = amounts.get(bucket);
                    if (amount == 0) continue;
                    long delta = "REFUND".equals(kind) ? -amount : amount;
                    if (delta < 0 && !enoughAt(profile, memberId, bucket, amount, at)) throw conflict("对应账户余额不足");
                    post(memberId, bucket, delta, kind, "RECEIPT", id, null, at, actor, reason);
                }
            }
            String state = covered && !"OPENING".equals(kind) ? "VERIFIED_COVERED" : "VERIFIED";
            db.update("UPDATE studio_receipt SET state=?,verified_at=?,version=version+1 WHERE id=?", state, StudioTime.now(), id);
            db.audit(actor, "RECEIPT_VERIFY", id, Map.of("reason", reason, "state", state, "amounts", amounts));
            return id;
        });
    }

    private void validateRefund(long memberId, LedgerStore.Row refund) {
        var original = receipt(memberId, refund.number("original_id"));
        if (!"TOPUP".equals(original.text("kind"))
                || !List.of("VERIFIED", "VERIFIED_COVERED").contains(original.text("state"))
                || refund.time("business_at").isBefore(original.time("business_at"))) {
            throw conflict("退款须关联有效的已核实充值，且时间不能早于充值");
        }
        long already = db.require("""
                SELECT COALESCE(SUM(amount_fen),0) AS amount FROM studio_receipt
                WHERE original_id=? AND state IN ('VERIFIED','VERIFIED_COVERED')
                """, original.number("id")).number("amount");
        if (already + refund.number("amount_fen") > original.number("amount_fen")) throw conflict("超过原充值未退金额");
    }

    @Transactional
    public long reverseReceipt(long memberId, long id, long actor, String key, ReverseInput input) {
        return db.command(actor, key, "RECEIPT_REVERSE", List.of(memberId, id, input), () -> {
            db.lockMember(memberId);
            var row = receipt(memberId, id);
            if (!List.of("VERIFIED", "VERIFIED_COVERED").contains(row.text("state"))) throw conflict("单据状态不能冲正");
            if (db.find("SELECT id FROM studio_receipt WHERE original_id=? AND state IN ('VERIFIED','VERIFIED_COVERED') LIMIT 1", id) != null) {
                throw conflict("请先处理该充值关联的退款");
            }
            String reason = required(input.reason(), 255, "冲正原因");
            for (var entry : db.list("""
                    SELECT e.* FROM studio_wallet_entry e WHERE e.member_id=? AND e.source_kind='RECEIPT' AND e.source_id=?
                      AND NOT EXISTS (SELECT 1 FROM studio_wallet_entry r WHERE r.reversal_of=e.id)
                    ORDER BY e.bucket
                    """, memberId, id)) {
                reverseEntry(memberId, entry.number("id"), actor, input.destination(), reason);
            }
            db.update("UPDATE studio_receipt SET state='REVERSED',version=version+1 WHERE id=?", id);
            db.audit(actor, "RECEIPT_REVERSE", id, Map.of("reason", reason));
            return id;
        });
    }

    void reverseEntry(long memberId, long entryId, long actor, String destination, String reason) {
        var entry = db.require("SELECT * FROM studio_wallet_entry WHERE member_id=? AND id=?", memberId, entryId);
        String bucket = entry.text("bucket");
        long delta = -entry.number("delta_fen");
        var active = active(mode(profile(memberId)));
        if (!active.contains(bucket)) {
            if (destination == null || !active.contains(destination)) throw invalid("请选择承接冲正金额的当前账户");
            if (delta < 0) transfer(memberId, destination, bucket, -delta, entryId, actor, reason);
        }
        post(memberId, bucket, delta, "REVERSAL", "REVERSAL", entryId, entryId, entry.time("business_at"), actor, reason);
        if (!active.contains(bucket) && delta > 0) transfer(memberId, bucket, destination, delta, entryId, actor, reason);
    }

    private void transfer(long memberId, String from, String to, long amount, long entryId, long actor, String reason) {
        var at = StudioTime.now();
        post(memberId, from, -amount, "TRANSFER", "REVERSAL", entryId, null, at, actor, reason);
        post(memberId, to, amount, "TRANSFER", "REVERSAL", entryId, null, at, actor, reason);
    }

    private void validateAmounts(String mode, Map<String, Long> amounts, long total) {
        if (amounts.values().stream().mapToLong(Long::longValue).sum() != total
                || amounts.entrySet().stream().anyMatch(e -> e.getValue() > 0 && !active(mode).contains(e.getKey()))) {
            throw invalid("请按当前余额模式分配，分配合计须等于单据金额");
        }
    }

    LedgerStore.Row receipt(long memberId, long id) {
        return db.require("SELECT * FROM studio_receipt WHERE member_id=? AND id=?", memberId, id);
    }

    List<Map<String, Object>> receipts(long memberId, long before) {
        return db.view("SELECT * FROM studio_receipt WHERE member_id=? AND id<? ORDER BY id DESC LIMIT 30", memberId, before);
    }
}
