package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.system.audit.AuditExportCsv;
import com.crdp.server.system.audit.AuditQuerySupport;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 资源变更记录查询与导出（E05-T10 / PLT-PERM-012，P2）：plt_resource_change_log 的分页查询与 CSV 导出。 写入点见 {@link
 * ResourceChangeRecorder}（菜单维护 / 清单同步 / 接口标记 / 接口扫描登记）。
 *
 * <p>导出为同步下载（带 UTF-8 BOM 与水印列的 CSV，口径与审计导出一致，OQ-22），行数上限 {@link AuditExportCsv#MAX_ROWS}；每次导出写
 * DATA_ACCESS 审计（导出留痕，PLT-AUD-003）。字段资源（FIELD）类型值已 预留，管理接口随 PLT-PERM-009 的平台端接口（E08+）落地时接入写入。
 */
@Service
public class ResourceChangeLogService {

    private static final List<String> TYPES = List.of("MENU", "API", "FIELD");
    private static final List<String> ACTIONS =
            List.of("CREATE", "UPDATE", "DELETE", "OFFLINE", "REGISTER");
    private static final List<String> CHANNELS = List.of("PAGE", "SYNC");
    private static final int KEYWORD_MAX = 64;

    private final JdbcTemplate platform;
    private final AuditLogger audit;

    public ResourceChangeLogService(JdbcTemplate platform, AuditLogger audit) {
        this.platform = platform;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 变更记录视图（createdAt 为 ISO-8601 带时区，北京时间）。 */
    public record LogItem(
            long id,
            String createdAt,
            String resourceType,
            Long resourceId,
            String resourceName,
            String resourceIdent,
            String action,
            String channel,
            String detail,
            Long operatorId,
            String operatorName) {

        static LogItem of(Map<String, Object> row) {
            return new LogItem(
                    num(row.get("id")),
                    AuditQuerySupport.format(time(row.get("created_at"))),
                    str(row.get("resource_type")),
                    row.get("resource_id") == null ? null : num(row.get("resource_id")),
                    str(row.get("resource_name")),
                    str(row.get("resource_ident")),
                    str(row.get("action")),
                    str(row.get("channel")),
                    str(row.get("detail")),
                    row.get("operator_id") == null ? null : num(row.get("operator_id")),
                    str(row.get("operator_name")));
        }
    }

    public record LogPage(List<LogItem> items, long total, int pageNo, int pageSize) {}

    /** 导出文件（CSV，UTF-8 BOM，首列水印）。 */
    public record ExportFile(String fileName, byte[] body, long rowCount) {}

    // ---------------------------------------------------------------- 查询

    /** 分页查询（新→旧）；resourceType / action / channel / keyword 均可省略。 */
    public LogPage search(
            String resourceType,
            String action,
            String channel,
            String keyword,
            Integer pageNo,
            Integer pageSize) {
        requirePlatformContext();
        Filters filters = validate(resourceType, action, channel, keyword);
        int no = AuditQuerySupport.pageNo(pageNo);
        int size = AuditQuerySupport.pageSize(pageSize);
        StringBuilder where = new StringBuilder("1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(filters, where, args);
        Long total =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_resource_change_log WHERE " + where,
                        Long.class,
                        args.toArray());
        List<LogItem> items =
                platform.query(
                        "SELECT id, resource_type, resource_id, resource_name, resource_ident,"
                                + " action, channel, detail, operator_id, operator_name, created_at"
                                + " FROM plt_resource_change_log WHERE "
                                + where
                                + " ORDER BY id DESC LIMIT "
                                + size
                                + " OFFSET "
                                + (no - 1) * size,
                        (rs, i) -> {
                            Map<String, Object> row = new java.util.LinkedHashMap<>();
                            row.put("id", rs.getLong("id"));
                            row.put("resource_type", rs.getString("resource_type"));
                            row.put(
                                    "resource_id",
                                    rs.getObject("resource_id") == null
                                            ? null
                                            : rs.getLong("resource_id"));
                            row.put("resource_name", rs.getString("resource_name"));
                            row.put("resource_ident", rs.getString("resource_ident"));
                            row.put("action", rs.getString("action"));
                            row.put("channel", rs.getString("channel"));
                            row.put("detail", rs.getString("detail"));
                            row.put(
                                    "operator_id",
                                    rs.getObject("operator_id") == null
                                            ? null
                                            : rs.getLong("operator_id"));
                            row.put("operator_name", rs.getString("operator_name"));
                            row.put("created_at", rs.getTimestamp("created_at"));
                            return LogItem.of(row);
                        },
                        args.toArray());
        return new LogPage(items, total == null ? 0 : total, no, size);
    }

    // ---------------------------------------------------------------- 导出

    /** 同步导出 CSV（条件与查询同口径；行数上限 {@link AuditExportCsv#MAX_ROWS}，超出截断并在尾行提示）。 */
    public ExportFile export(String resourceType, String action, String channel, String keyword) {
        SessionPrincipal principal = CurrentSession.requirePlatform();
        requirePlatformContext();
        Filters filters = validate(resourceType, action, channel, keyword);
        StringBuilder where = new StringBuilder("1 = 1");
        List<Object> args = new ArrayList<>();
        appendFilters(filters, where, args);
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT id, resource_type, resource_id, resource_name, resource_ident,"
                                + " action, channel, detail, operator_id, operator_name, created_at"
                                + " FROM plt_resource_change_log WHERE "
                                + where
                                + " ORDER BY id ASC LIMIT "
                                + (AuditExportCsv.MAX_ROWS + 1),
                        args.toArray());
        boolean truncated = rows.size() > AuditExportCsv.MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, AuditExportCsv.MAX_ROWS);
        }
        List<String> columns =
                List.of(
                        "id",
                        "created_at",
                        "resource_type",
                        "resource_name",
                        "resource_ident",
                        "action",
                        "channel",
                        "detail",
                        "operator_name");
        LocalDateTime now = LocalDateTime.now(AuditQuerySupport.ZONE);
        String watermark =
                AuditExportCsv.watermark(
                        principal.displayName(), principal.username(), "平台资源变更记录", now);
        String note =
                "资源变更记录导出 · "
                        + conditionSummary(filters)
                        + " · "
                        + rows.size()
                        + " 条"
                        + (truncated ? "（已达上限，已截断）" : "");
        byte[] body = AuditExportCsv.render(columns, rows, watermark, note);
        String fileName =
                "资源变更记录-"
                        + AuditQuerySupport.TIME
                                .format(now.atZone(AuditQuerySupport.ZONE))
                                .replace(":", "")
                                .replace("T", "-")
                        + ".csv";
        audit.append(
                AuditEntry.dataAccess("platform", "EXPORT")
                        .description("导出资源变更记录：" + note)
                        .objectType("plt_resource_change_log")
                        .recordCount((long) rows.size())
                        .queryDigest(conditionSummary(filters))
                        .result(AuditResult.SUCCESS)
                        .build());
        return new ExportFile(fileName, body, rows.size());
    }

    // ---------------------------------------------------------------- 内部

    private record Filters(String resourceType, String action, String channel, String keyword) {}

    private Filters validate(String resourceType, String action, String channel, String keyword) {
        String type = AuditQuerySupport.trim(resourceType);
        if (type != null && !TYPES.contains(type)) {
            throw new ApiException(400, 40001, "resourceType 只允许 MENU / API / FIELD");
        }
        String act = AuditQuerySupport.trim(action);
        if (act != null && !ACTIONS.contains(act)) {
            throw new ApiException(
                    400, 40001, "action 只允许 CREATE / UPDATE / DELETE / OFFLINE / REGISTER");
        }
        String ch = AuditQuerySupport.trim(channel);
        if (ch != null && !CHANNELS.contains(ch)) {
            throw new ApiException(400, 40001, "channel 只允许 PAGE / SYNC");
        }
        String kw = AuditQuerySupport.trim(keyword);
        if (kw != null && kw.length() > KEYWORD_MAX) {
            throw new ApiException(400, 40001, "keyword 关键词过长（最多 64 个字符）");
        }
        return new Filters(type, act, ch, kw);
    }

    private void appendFilters(Filters filters, StringBuilder where, List<Object> args) {
        if (filters.resourceType() != null) {
            where.append(" AND resource_type = ?");
            args.add(filters.resourceType());
        }
        if (filters.action() != null) {
            where.append(" AND action = ?");
            args.add(filters.action());
        }
        if (filters.channel() != null) {
            where.append(" AND channel = ?");
            args.add(filters.channel());
        }
        if (filters.keyword() != null) {
            String escaped = escapeLike(filters.keyword());
            where.append(" AND (resource_name LIKE ? OR resource_ident LIKE ? OR detail LIKE ?)");
            args.add("%" + escaped + "%");
            args.add("%" + escaped + "%");
            args.add("%" + escaped + "%");
        }
    }

    /** 条件摘要（导出审计与文件头说明用）。 */
    private static String conditionSummary(Filters filters) {
        StringBuilder summary = new StringBuilder();
        if (filters.resourceType() != null) {
            summary.append("类型 ").append(filters.resourceType());
        }
        if (filters.action() != null) {
            summary.append(summary.isEmpty() ? "" : "，").append("动作 ").append(filters.action());
        }
        if (filters.channel() != null) {
            summary.append(summary.isEmpty() ? "" : "，").append("渠道 ").append(filters.channel());
        }
        if (filters.keyword() != null) {
            summary.append(summary.isEmpty() ? "" : "，").append("关键词 ").append(filters.keyword());
        }
        return summary.isEmpty() ? "全部" : summary.toString();
    }

    private static String escapeLike(String value) {
        return value.replaceAll("([%_\\\\])", "\\\\$1");
    }

    private static void requirePlatformContext() {
        TenantContext.State state = TenantContext.require();
        if (!state.platform()) {
            throw new ApiException(403, 40300, "医院账号不能访问平台端");
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long num(Object value) {
        return ((Number) value).longValue();
    }

    private static LocalDateTime time(Object value) {
        return value == null ? null : ((java.sql.Timestamp) value).toLocalDateTime();
    }
}
