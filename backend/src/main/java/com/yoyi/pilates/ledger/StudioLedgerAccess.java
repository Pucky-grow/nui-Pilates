package com.yoyi.pilates.ledger;

import com.yoyi.pilates.common.exception.BusinessException;
import com.yoyi.pilates.common.result.ResultCode;
import com.yoyi.pilates.system.security.LoginUser;
import com.yoyi.pilates.system.security.SecurityUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StudioLedgerAccess {
    public static final String MIGRATION = "V004__studio_ledger.sql";
    public static final String MIGRATION_SHA256 = "1a5d017c1711c21027c99abefa5e7c0b9020e45f3fe08fe0dcbc3e841f8f9c6d";
    public static final int MIGRATION_STEPS = 13;
    public static final int TABLES_ADDED = 12;
    public static final String ENABLED_PROPERTY = "yoyi.studio-ledger.enabled";

    private final boolean enabled;

    public StudioLedgerAccess(@Value("${yoyi.studio-ledger.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    void requireEnabled() {
        if (!enabled) throw BusinessException.of(ResultCode.NOT_FOUND, "会员账本暂未启用");
    }

    LoginUser operator() {
        requireEnabled();
        LoginUser actor = SecurityUtils.requireUser();
        if (!actor.isAdmin()) throw BusinessException.of(ResultCode.FORBIDDEN);
        return actor;
    }

    long admin() {
        LoginUser actor = operator();
        if (!actor.isAdminRole()) throw BusinessException.of(ResultCode.FORBIDDEN);
        return actor.getId();
    }
}
