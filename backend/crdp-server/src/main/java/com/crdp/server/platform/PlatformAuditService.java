package com.crdp.server.platform;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.system.audit.AuditExportCsv;
import com.crdp.server.system.audit.AuditQuerySupport;
import com.crdp.server.system.audit.AuditTables;
import com.crdp.server.system.audit.AuditTables.Kind;
import com.crdp.server.system.audit.AuditViews.AuditDetailView;
import com.crdp.server.system.audit.AuditViews.AuditPage;
import com.crdp.server.system.audit.AuditViews.AuditRowView;
import com.crdp.server.system.audit.AuditViews.AuditTraceLink;
import com.crdp.server.system.audit.AuditViews.ChainCheckView;
import com.crdp.server.system.audit.AuditViews.SearchRequest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * E08-T04 / PLT-AUD-005、006、009：平台端审计查询（docs/api/audit.md §3）。 五个页签映射： operation → plt_audit_log
 * 全量；control-plane → plt_audit_log 且 tenant_id 非空（租户控制面，PLT-TEN-022）； login → 平台库
 * aud_login_log（平台账号 + 租户未识别的医院登录失败）；tenant-view → aud_platform_access_log 全量（PLT-AUD-004
 * 租户视角记录）；view-rule → aud_platform_access_log 且 action 前缀 VIEW_RULE（视角规则变更， 写入侧随
 * E20-T01）。可按租户筛选（platform-spec §9 权限点 platform:audit:view）。
 */
@Service
public class PlatformAuditService {

    private final JdbcTemplate jdbc;

    public PlatformAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 分页查询。 */
    public AuditPage search(String type, SearchRequest request) {
        requirePlatformContext();
        Kind kind = AuditTables.platformKind(type);
        LocalDateTime start = AuditQuerySupport.parseTime(request.startTime(), "开始时间");
        LocalDateTime end = AuditQuerySupport.parseTime(request.endTime(), "结束时间");
        AuditQuerySupport.requireSpan(start, end);
        int pageNo = AuditQuerySupport.pageNo(request.pageNo());
        int pageSize = AuditQuerySupport.pageSize(request.pageSize());

        StringBuilder where = new StringBuilder("created_at >= ? AND created_at <= ?");
        List<Object> args = new ArrayList<>(List.of(start, end.plusNanos(999_999_999)));
        appendFilters(kind, type, request, where, args);

        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM "
                                + AuditTables.PLATFORM_TABLES.get(kind)
                                + " WHERE "
                                + where,
                        Long.class,
                        args.toArray());
        int offset = (pageNo - 1) * pageSize;
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM "
                                + AuditTables.PLATFORM_TABLES.get(kind)
                                + " WHERE "
                                + where
                                + " ORDER BY id DESC LIMIT "
                                + pageSize
                                + " OFFSET "
                                + offset,
                        args.toArray());
        return new AuditPage(
                rows.stream().map(r -> row(kind, r)).toList(),
                total == null ? 0 : total,
                pageNo,
                pageSize);
    }

    /** 详情：前后值对比 + 同 traceId 关联（跨平台库三张表）。 */
    public AuditDetailView detail(String type, long id) {
        requirePlatformContext();
        Kind kind = AuditTables.platformKind(type);
        String extra = extraPredicate(type);
        String predicate = extra.isEmpty() ? "" : " AND " + extra;
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM "
                                + AuditTables.PLATFORM_TABLES.get(kind)
                                + " WHERE id = ?"
                                + predicate,
                        id);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "日志不存在");
        }
        Map<String, Object> row = rows.get(0);
        List<AuditTraceLink> links = new ArrayList<>();
        String traceId = str(row.get("trace_id"));
        if (traceId != null && !traceId.isBlank()) {
            for (Kind k : List.of(Kind.OPERATION, Kind.LOGIN, Kind.PLATFORM_ACCESS)) {
                // 各表描述列名不同：plt_audit_log.detail / aud_login_log.message /
                // aud_platform_access_log.description
                String description =
                        switch (k) {
                            case LOGIN -> "message";
                            case PLATFORM_ACCESS -> "description";
                            default -> "detail";
                        };
                jdbc.queryForList(
                                "SELECT id, action, "
                                        + description
                                        + " AS description FROM "
                                        + AuditTables.PLATFORM_TABLES.get(k)
                                        + " WHERE trace_id = ? AND id != ? ORDER BY id LIMIT 50",
                                traceId,
                                id)
                        .forEach(
                                r ->
                                        links.add(
                                                new AuditTraceLink(
                                                        k.name(),
                                                        num(r.get("id")),
                                                        str(r.get("action")),
                                                        str(r.get("description")),
                                                        null)));
            }
        }
        return new AuditDetailView(
                row(kind, row),
                str(row.get("detail")),
                str(row.get("before_json")),
                str(row.get("after_json")),
                str(row.get("user_agent")),
                str(row.get("view_session_id")),
                str(row.get("role_codes")),
                str(row.get("prev_hash")),
                str(row.get("hash")),
                links);
    }

    /** 最近一次哈希链校验结果（PLT-AUD-009，audit.md §3；tenantId 为空表示平台库自身）。 */
    public List<ChainCheckView> integrity(Long tenantId) {
        requirePlatformContext();
        return jdbc.query(
                "SELECT c.* FROM aud_chain_check c JOIN (SELECT table_name, MAX(id) AS max_id FROM"
                        + " aud_chain_check WHERE tenant_id <=> ? GROUP BY table_name) m"
                        + " ON c.table_name = m.table_name AND c.id = m.max_id ORDER BY c.table_name",
                (rs, i) ->
                        new ChainCheckView(
                                rs.getString("table_name"),
                                rs.getString("status"),
                                rs.getLong("hashed_rows"),
                                rs.getInt("break_count"),
                                rs.getObject("first_break_id") == null
                                        ? null
                                        : rs.getLong("first_break_id"),
                                rs.getString("first_break_kind"),
                                rs.getString("detail"),
                                AuditQuerySupport.format(
                                        rs.getTimestamp("checked_at").toLocalDateTime())),
                tenantId);
    }

    /** 导出取数（上限 {@link AuditExportCsv#MAX_ROWS}）；导出列含 tenant_id（平台端）。 */
    public List<Map<String, Object>> exportRows(String type, SearchRequest request) {
        requirePlatformContext();
        Kind kind = AuditTables.platformKind(type);
        validate(type, request);
        StringBuilder where = new StringBuilder("created_at >= ? AND created_at <= ?");
        List<Object> args =
                new ArrayList<>(
                        List.of(
                                AuditQuerySupport.parseTime(request.startTime(), "开始时间"),
                                AuditQuerySupport.parseTime(request.endTime(), "结束时间")
                                        .plusNanos(999_999_999)));
        appendFilters(kind, type, request, where, args);
        String columns = String.join(", ", platformColumns(kind));
        return jdbc.queryForList(
                "SELECT "
                        + columns
                        + " FROM "
                        + AuditTables.PLATFORM_TABLES.get(kind)
                        + " WHERE "
                        + where
                        + " ORDER BY id ASC LIMIT "
                        + (AuditExportCsv.MAX_ROWS + 1),
                args.toArray());
    }

    /** 条件校验（导出提交用，与查询同口径）。 */
    public void validate(String type, SearchRequest request) {
        requirePlatformContext();
        Kind kind = AuditTables.platformKind(type);
        LocalDateTime start = AuditQuerySupport.parseTime(request.startTime(), "开始时间");
        LocalDateTime end = AuditQuerySupport.parseTime(request.endTime(), "结束时间");
        AuditQuerySupport.requireSpan(start, end);
        if (kind == Kind.LOGIN
                && (AuditQuerySupport.trim(request.objectType()) != null
                        || AuditQuerySupport.trim(request.objectId()) != null)) {
            throw new ApiException(400, 40001, "登录日志不支持按对象筛选");
        }
    }

    /** 平台端导出列：OPERATION / LOGIN 在通用列前补 tenant_id（按租户检索是平台端核心口径）。 */
    public static List<String> platformColumns(Kind kind) {
        List<String> columns = new ArrayList<>(AuditTables.COLUMNS.get(kind));
        if (kind == Kind.OPERATION || kind == Kind.LOGIN) {
            columns.add(2, "tenant_id");
        }
        return List.copyOf(columns);
    }

    // ---------------------------------------------------------------- 内部

    /** 页签 → 库表过滤（control-plane：tenant_id 非空；view-rule：action 前缀 VIEW_RULE）。 */
    private String extraPredicate(String type) {
        return switch (type == null ? "" : type) {
            case "control-plane" -> "tenant_id IS NOT NULL";
            case "view-rule" -> "action LIKE 'VIEW_RULE%'";
            default -> "";
        };
    }

    private void appendFilters(
            Kind kind, String type, SearchRequest request, StringBuilder where, List<Object> args) {
        String extra = extraPredicate(type);
        if (!extra.isEmpty()) {
            where.append(" AND ").append(extra);
        }
        String userColumn = kind == Kind.LOGIN ? "username" : "user_name";
        String idColumn = kind == Kind.OPERATION ? "actor_id" : "user_id";
        if (request.tenantId() != null) {
            if (kind == Kind.OPERATION || kind == Kind.PLATFORM_ACCESS || kind == Kind.LOGIN) {
                where.append(" AND tenant_id = ?");
                args.add(request.tenantId());
            }
        }
        if (request.userId() != null) {
            where.append(" AND ").append(idColumn).append(" = ?");
            args.add(request.userId());
        }
        String userName = AuditQuerySupport.trim(request.userName());
        if (userName != null) {
            where.append(" AND ").append(userColumn).append(" = ?");
            args.add(userName);
        }
        String module = AuditQuerySupport.trim(request.module());
        if (module != null) {
            where.append(" AND module = ?");
            args.add(module);
        }
        String action = AuditQuerySupport.trim(request.action());
        if (action != null && !"view-rule".equals(type)) {
            where.append(" AND action = ?");
            args.add(action);
        }
        if (kind != Kind.LOGIN) {
            String objectType = AuditQuerySupport.trim(request.objectType());
            if (objectType != null) {
                where.append(" AND object_type = ?");
                args.add(objectType);
            }
            String objectId = AuditQuerySupport.trim(request.objectId());
            if (objectId != null) {
                where.append(" AND object_id = ?");
                args.add(objectId);
            }
        }
        String result = AuditQuerySupport.trim(request.result());
        if (result != null) {
            where.append(" AND result = ?");
            args.add(result);
        }
        String ip = AuditQuerySupport.trim(request.ip());
        if (ip != null) {
            where.append(" AND ip = ?");
            args.add(ip);
        }
        String traceId = AuditQuerySupport.trim(request.traceId());
        if (traceId != null) {
            where.append(" AND trace_id = ?");
            args.add(traceId);
        }
        // 视角会话号（仅平台访问日志有该列，E20-T05 / PLT-VIEW-016「视角会话」钻取）
        String viewSessionId = AuditQuerySupport.trim(request.viewSessionId());
        if (viewSessionId != null && kind == Kind.PLATFORM_ACCESS) {
            where.append(" AND view_session_id = ?");
            args.add(viewSessionId);
        }
    }

    private AuditRowView row(Kind kind, Map<String, Object> r) {
        return new AuditRowView(
                num(r.get("id")),
                kind.name(),
                AuditQuerySupport.format(time(r.get("created_at"))),
                str(kind == Kind.LOGIN ? r.get("username") : r.get("user_name")),
                str(r.get("display_name")),
                str(r.get("user_type")),
                str(r.get("module")),
                str(r.get("action")),
                str(r.get("detail") != null ? r.get("detail") : r.get("description")),
                str(r.get("object_type")),
                str(r.get("object_id")),
                str(r.get("result")),
                r.get("error_code") == null ? null : (int) num(r.get("error_code")),
                str(r.get("reason") != null ? r.get("reason") : r.get("fail_reason")),
                str(r.get("ip")),
                str(r.get("trace_id")),
                r.get("tenant_id") == null ? null : num(r.get("tenant_id")),
                str(r.get("login_method")),
                str(r.get("session_id")),
                str(r.get("access_method")),
                r.get("record_count") == null ? null : num(r.get("record_count")));
    }

    private static void requirePlatformContext() {
        TenantContext.State state = TenantContext.require();
        if (!state.platform()) {
            throw new ApiException(403, 40300, "医院账号不能访问平台端审计中心");
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long num(Object value) {
        return ((Number) value).longValue();
    }

    private static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime time) {
            return time;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return null;
    }
}
