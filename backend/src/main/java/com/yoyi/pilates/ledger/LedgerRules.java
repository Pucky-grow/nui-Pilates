package com.yoyi.pilates.ledger;

import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.common.util.StudioTime;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

final class LedgerRules {
    static final long MAX_FEN = 100_000_000L;
    static final List<String> BUCKETS = List.of("GENERAL", "GROUP", "PRIVATE");
    private LedgerRules() {}

    static BusinessException invalid(String message) { return BusinessException.of(ResultCode.BAD_REQUEST, message); }
    static BusinessException conflict(String message) { return BusinessException.of(ResultCode.CONFLICT, message); }

    static String required(String input, int max, String label) {
        String value = optional(input, max, label);
        if (value == null) throw invalid("请填写" + label);
        return value;
    }

    static String optional(String input, int max, String label) {
        if (input == null || input.isBlank()) return null;
        String value = input.trim();
        if (value.length() > max) throw invalid(label + "过长");
        return value;
    }

    static String oneOf(String value, String label, String... allowed) {
        if (value == null || !Set.of(allowed).contains(value)) throw invalid(label + "无效");
        return value;
    }

    static long fen(String yuan) {
        if (yuan == null || !yuan.matches("[0-9]{1,7}(\\.[0-9]{1,2})?")) {
            throw invalid("金额须为非负元金额，最多两位小数");
        }
        long result = new BigDecimal(yuan).movePointRight(2).longValueExact();
        if (result > MAX_FEN) throw invalid("金额超过单笔上限");
        return result;
    }

    static long optionalFen(String yuan) {
        return yuan == null || yuan.isBlank() ? 0 : fen(yuan);
    }

    static LocalDateTime date(LocalDateTime value, String label) {
        if (value == null) throw invalid("请填写" + label);
        return value.truncatedTo(ChronoUnit.SECONDS);
    }

    static LocalDateTime past(LocalDateTime value, String label) {
        var time = date(value, label);
        if (time.isAfter(StudioTime.now())) throw invalid(label + "不能晚于现在");
        return time;
    }

    static long fee(String kind, int value, long price) {
        return switch (kind) {
            case "NONE" -> 0;
            case "FIXED" -> Math.min(price, value);
            case "PERCENT" -> (price * value + 5_000) / 10_000;
            default -> throw invalid("收费方式无效");
        };
    }

    static int feeValue(String kind, Integer value) {
        if (value == null || value < 0 || value > switch (kind) {
            case "NONE" -> 0;
            case "FIXED" -> MAX_FEN;
            case "PERCENT" -> 10_000;
            default -> throw invalid("收费方式无效");
        }) throw invalid("费率或固定费用无效");
        return value;
    }

    static boolean late(LocalDateTime cancelled, LocalDateTime start, int minutes) {
        return cancelled != null && cancelled.isBefore(start) && cancelled.isAfter(start.minusMinutes(minutes));
    }
}
