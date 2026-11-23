package com.yoyi.pilates.common.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** 门店业务日期不依赖服务器默认时区。 */
public final class StudioTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private StudioTime() { }
    public static LocalDateTime now() { return LocalDateTime.now(ZONE); }
    public static LocalDate today() { return now().toLocalDate(); }
}
