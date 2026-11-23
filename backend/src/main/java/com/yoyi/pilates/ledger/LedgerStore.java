package com.yoyi.pilates.ledger;

import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.JsonUtils;
import com.yoyi.pilates.common.util.StudioTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
@RequiredArgsConstructor
class LedgerStore {
    private final JdbcTemplate jdbc;

    record Row(Map<String, Object> values) {
        String text(String key) { return (String) values.get(key); }
        Long id(String key) {
            Number value = (Number) values.get(key);
            return value == null ? null : value.longValue();
        }
        long number(String key) { return Objects.requireNonNullElse(id(key), 0L); }
        LocalDateTime time(String key) { return (LocalDateTime) values.get(key); }
    }

    List<Row> list(String sql, Object... args) {
        return jdbc.query(sql, (rs, index) -> {
            var values = new LinkedHashMap<String, Object>();
            var metadata = rs.getMetaData();
            for (int i = 1; i <= metadata.getColumnCount(); i++) {
                Object value = rs.getObject(i);
                if (value instanceof Timestamp timestamp) value = timestamp.toLocalDateTime();
                if (value instanceof java.sql.Date date) value = date.toLocalDate();
                if (value instanceof java.sql.Time time) value = time.toLocalTime();
                values.put(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT), value);
            }
            return new Row(values);
        }, args);
    }

    Row find(String sql, Object... args) {
        return list(sql, args).stream().findFirst().orElse(null);
    }

    Row require(String sql, Object... args) {
        Row row = find(sql, args);
        if (row == null) throw BusinessException.of(ResultCode.NOT_FOUND);
        return row;
    }

    List<Map<String, Object>> view(String sql, Object... args) {
        return list(sql, args).stream().map(Row::values).toList();
    }

    int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            return statement;
        }, key);
        var generated = Objects.requireNonNull(key.getKeys());
        if (generated.size() == 1) return ((Number) generated.values().iterator().next()).longValue();
        return generated.entrySet().stream().filter(entry -> "id".equalsIgnoreCase(entry.getKey()))
                .mapToLong(entry -> ((Number) entry.getValue()).longValue()).findFirst().orElseThrow();
    }

    void lockMember(long memberId) {
        require("SELECT id FROM member WHERE id=? AND deleted=0 FOR UPDATE", memberId);
    }

    long memberForUser(long userId) {
        return require("SELECT id FROM member WHERE user_id=? AND deleted=0", userId).number("id");
    }

    long command(long actor, String key, String action, Object payload, LongSupplier work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Ledger command requires an active transaction");
        }
        if (key == null || !key.matches("[A-Za-z0-9_-]{12,80}")) {
            throw LedgerRules.invalid("请求编号无效");
        }
        String hash = digest(JsonUtils.toJson(payload));
        update("""
                INSERT INTO studio_admin_command(operator_id,request_key,payload_hash,action,recorded_at)
                VALUES(?,?,?,?,?) ON DUPLICATE KEY UPDATE request_key=studio_admin_command.request_key
                """, actor, key, hash, action, StudioTime.now());
        Row saved = require("""
                SELECT id,payload_hash,action,result_id FROM studio_admin_command
                WHERE operator_id=? AND request_key=? FOR UPDATE
                """, actor, key);
        if (!hash.equals(saved.text("payload_hash")) || !action.equals(saved.text("action"))) {
            throw LedgerRules.conflict("请求编号已用于不同操作");
        }
        Long result = saved.id("result_id");
        if (result != null) return result;
        long id = work.getAsLong();
        update("UPDATE studio_admin_command SET result_id=? WHERE id=?", id, saved.number("id"));
        return id;
    }

    void audit(long actor, String action, long target, Object detail) {
        update("""
                INSERT INTO studio_admin_audit(operator_id,action,target_id,recorded_at,details)
                VALUES(?,?,?,?,?)
                """, actor, action, target, StudioTime.now(), JsonUtils.toJson(detail));
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
