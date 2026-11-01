package com.crdp.server.system.audit;

import com.crdp.framework.web.ApiException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * E08-T04 查询条件的解析与校验（医院端与平台端共用）。 口径来自 platform-spec §9 业务规则：单次查询时间跨度不超过 3 个月；分页 pageNo 从 1
 * 开始、pageSize 默认 20（AGENTS 第 6 节）。 时间入参为 ISO-8601 带时区，展示一律北京时间。
 */
public final class AuditQuerySupport {

    /** 北京时间（业务口径，AGENTS 第 6 节）。 */
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 展示格式：ISO-8601 带时区（+08:00）。 */
    public static final DateTimeFormatter TIME =
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZONE);

    /** 单次查询（含导出）时间跨度上限：3 个月按 92 天计（platform-spec §9）。 */
    private static final long MAX_SPAN_DAYS = 92;

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    private AuditQuerySupport() {}

    /** 解析时间条件：必填、ISO-8601 带时区；非法或缺失抛 40001。 */
    public static LocalDateTime parseTime(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(
                    400, 40001, field + " 必填（ISO-8601 带时区，如 2027-01-01T00:00:00+08:00）");
        }
        try {
            return OffsetDateTime.parse(value.trim()).atZoneSameInstant(ZONE).toLocalDateTime();
        } catch (RuntimeException e) {
            throw new ApiException(400, 40001, field + " 格式不正确，应为 ISO-8601 带时区");
        }
    }

    /** 校验时间窗：end ≥ start 且跨度 ≤ 3 个月（92 天）。 */
    public static void requireSpan(LocalDateTime start, LocalDateTime end) {
        if (end.isBefore(start)) {
            throw new ApiException(400, 40001, "结束时间不能早于开始时间");
        }
        if (start.plusDays(MAX_SPAN_DAYS).isBefore(end)) {
            throw new ApiException(400, 40001, "单次查询时间跨度不能超过 3 个月，请缩小范围分批查询");
        }
    }

    /** pageNo 从 1 开始；pageSize 默认 20、上限 100。 */
    public static int pageNo(Integer pageNo) {
        if (pageNo == null) {
            return 1;
        }
        if (pageNo < 1) {
            throw new ApiException(400, 40001, "pageNo 必须从 1 开始");
        }
        return pageNo;
    }

    public static int pageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new ApiException(400, 40001, "pageSize 取值范围 1–100");
        }
        return pageSize;
    }

    /** 数据库时间（DATETIME，北京时间）→ ISO-8601 带时区展示。 */
    public static String format(LocalDateTime time) {
        return time == null ? null : TIME.format(time.atZone(ZONE));
    }

    /** 字符串条件：去掉首尾空白，空白视为未填。 */
    public static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
