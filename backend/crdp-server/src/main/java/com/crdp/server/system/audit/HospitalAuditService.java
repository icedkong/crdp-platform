package com.crdp.server.system.audit;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.system.audit.AuditTables.Kind;
import com.crdp.server.system.audit.AuditViews.AuditDetailView;
import com.crdp.server.system.audit.AuditViews.AuditPage;
import com.crdp.server.system.audit.AuditViews.AuditRowView;
import com.crdp.server.system.audit.AuditViews.AuditTraceLink;
import com.crdp.server.system.audit.AuditViews.SearchRequest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * E08-T04 / PLT-AUD-005、006：医院端审计查询（docs/api/audit.md §2）。 只读本租户库三张审计表（操作 / 登录 / 数据访问）；
 * 租户侧永远看不到平台访问记录（PLT-AUD-004 仅存平台侧）。 调用前提：医院会话（Controller 侧 {@code
 * CurrentSession.requireHospital()}），租户上下文由登录过滤器设置。
 *
 * <p>单次查询时间跨度 ≤ 3 个月（platform-spec §9 业务规则，{@link AuditQuerySupport#requireSpan}）。
 */
@Service
public class HospitalAuditService {

    private final JdbcTemplate jdbc;

    public HospitalAuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 分页查询（PLT-AUD-005：时间、用户、模块、对象、结果、IP、traceId 组合）。 */
    public AuditPage search(Kind kind, SearchRequest request) {
        LocalDateTime start = AuditQuerySupport.parseTime(request.startTime(), "开始时间");
        LocalDateTime end = AuditQuerySupport.parseTime(request.endTime(), "结束时间");
        AuditQuerySupport.requireSpan(start, end);
        int pageNo = AuditQuerySupport.pageNo(request.pageNo());
        int pageSize = AuditQuerySupport.pageSize(request.pageSize());

        StringBuilder where = new StringBuilder("created_at >= ? AND created_at <= ?");
        List<Object> args = new ArrayList<>(List.of(start, end.plusNanos(999_999_999)));
        appendFilters(kind, request, where, args);
        String table = AuditTables.TENANT_TABLES.get(kind);

        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE " + where,
                        Long.class,
                        args.toArray());
        int offset = (pageNo - 1) * pageSize;
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT * FROM "
                                + table
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

    /** 详情（PLT-AUD-006）：前后值对比（敏感值写入侧已是摘要）+ 同 traceId 关联链路。 */
    public AuditDetailView detail(Kind kind, long id) {
        String table = AuditTables.TENANT_TABLES.get(kind);
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT * FROM " + table + " WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "日志不存在");
        }
        Map<String, Object> row = rows.get(0);
        List<AuditTraceLink> links = traceLinksOf(str(row.get("trace_id")), kind, id);
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

    /** 患者详情「操作日志」页签（audit.md §2）：该患者相关的操作与数据访问日志，只读。 */
    public AuditPage patientLogs(long patientId, Integer pageNo, Integer pageSize) {
        int no = AuditQuerySupport.pageNo(pageNo);
        int size = AuditQuerySupport.pageSize(pageSize);
        String filter =
                "FROM (SELECT id, 'OPERATION' AS log_type, created_at, user_name, display_name,"
                        + " user_type, module, action, detail AS description, object_type,"
                        + " object_id, result, reason, ip, trace_id, NULL AS access_method,"
                        + " NULL AS record_count FROM aud_log"
                        + " WHERE object_type = 'PATIENT' AND object_id = ?"
                        + " UNION ALL"
                        + " SELECT id, 'DATA_ACCESS' AS log_type, created_at, user_name, display_name,"
                        + " user_type, module, action, description, object_type, object_id, result,"
                        + " reason, ip, trace_id, access_method, record_count FROM aud_data_access_log"
                        + " WHERE object_type = 'PATIENT' AND object_id = ?) t";
        Long total =
                jdbc.queryForObject(
                        "SELECT COUNT(*) " + filter,
                        Long.class,
                        String.valueOf(patientId),
                        String.valueOf(patientId));
        int offset = (no - 1) * size;
        List<Map<String, Object>> rows =
                jdbc.queryForList(
                        "SELECT t.* "
                                + filter
                                + " ORDER BY t.created_at DESC, t.id DESC LIMIT "
                                + size
                                + " OFFSET "
                                + offset,
                        String.valueOf(patientId),
                        String.valueOf(patientId));
        return new AuditPage(
                rows.stream()
                        .map(
                                r ->
                                        new AuditRowView(
                                                num(r.get("id")),
                                                str(r.get("log_type")),
                                                AuditQuerySupport.format(time(r.get("created_at"))),
                                                str(r.get("user_name")),
                                                str(r.get("display_name")),
                                                str(r.get("user_type")),
                                                str(r.get("module")),
                                                str(r.get("action")),
                                                str(r.get("description")),
                                                str(r.get("object_type")),
                                                str(r.get("object_id")),
                                                str(r.get("result")),
                                                null,
                                                str(r.get("reason")),
                                                str(r.get("ip")),
                                                str(r.get("trace_id")),
                                                null,
                                                null,
                                                null,
                                                str(r.get("access_method")),
                                                r.get("record_count") == null
                                                        ? null
                                                        : num(r.get("record_count"))))
                        .toList(),
                total == null ? 0 : total,
                no,
                size);
    }

    /** 条件校验（导出提交时先跑一遍，查询与导出同一口径）。 */
    public void validate(Kind kind, SearchRequest request) {
        LocalDateTime start = AuditQuerySupport.parseTime(request.startTime(), "开始时间");
        LocalDateTime end = AuditQuerySupport.parseTime(request.endTime(), "结束时间");
        AuditQuerySupport.requireSpan(start, end);
        if (kind == Kind.LOGIN
                && (AuditQuerySupport.trim(request.objectType()) != null
                        || AuditQuerySupport.trim(request.objectId()) != null)) {
            throw new ApiException(400, 40001, "登录日志不支持按对象筛选");
        }
    }

    /** 导出取数（上限 {@link AuditExportCsv#MAX_ROWS}），列与 CSV 一致；在当前租户上下文内执行。 */
    public List<Map<String, Object>> exportRows(Kind kind, SearchRequest request) {
        LocalDateTime start = AuditQuerySupport.parseTime(request.startTime(), "开始时间");
        LocalDateTime end = AuditQuerySupport.parseTime(request.endTime(), "结束时间");
        AuditQuerySupport.requireSpan(start, end);
        StringBuilder where = new StringBuilder("created_at >= ? AND created_at <= ?");
        List<Object> args = new ArrayList<>(List.of(start, end.plusNanos(999_999_999)));
        appendFilters(kind, request, where, args);
        String columns = String.join(", ", AuditTables.COLUMNS.get(kind));
        String table = AuditTables.TENANT_TABLES.get(kind);
        return jdbc.queryForList(
                "SELECT "
                        + columns
                        + " FROM "
                        + table
                        + " WHERE "
                        + where
                        + " ORDER BY id ASC LIMIT "
                        + (AuditExportCsv.MAX_ROWS + 1),
                args.toArray());
    }

    /** 同 traceId 的其他日志（按表顺序、主键升序，最多 50 条）。 */
    private List<AuditTraceLink> traceLinksOf(String traceId, Kind selfKind, long selfId) {
        List<AuditTraceLink> links = new ArrayList<>();
        if (traceId == null || traceId.isBlank()) {
            return links;
        }
        for (Kind kind : List.of(Kind.OPERATION, Kind.LOGIN, Kind.DATA_ACCESS)) {
            String table = AuditTables.TENANT_TABLES.get(kind);
            // 各表描述列名不同：aud_log.detail / aud_login_log.message / aud_data_access_log.description
            String description =
                    switch (kind) {
                        case LOGIN -> "message";
                        case DATA_ACCESS -> "description";
                        default -> "detail";
                    };
            jdbc.queryForList(
                            "SELECT id, action, "
                                    + description
                                    + " AS description FROM "
                                    + table
                                    + " WHERE trace_id = ? AND id != ? ORDER BY id LIMIT 50",
                            traceId,
                            selfId)
                    .forEach(
                            r ->
                                    links.add(
                                            new AuditTraceLink(
                                                    kind.name(),
                                                    num(r.get("id")),
                                                    str(r.get("action")),
                                                    str(r.get("description")),
                                                    null)));
        }
        return links;
    }

    private void appendFilters(
            Kind kind, SearchRequest request, StringBuilder where, List<Object> args) {
        String userColumn = kind == Kind.LOGIN ? "username" : "user_name";
        String idColumn =
                kind == Kind.LOGIN ? "user_id" : kind == Kind.OPERATION ? "actor_id" : "user_id";
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
        if (action != null) {
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
                null,
                str(r.get("login_method")),
                str(r.get("session_id")),
                str(r.get("access_method")),
                r.get("record_count") == null ? null : num(r.get("record_count")));
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

    /** 供 Controller 组装导出条件摘要（不含敏感明文）：只保留命中项。 */
    public static String conditionSummary(SearchRequest request) {
        Map<String, String> parts = new LinkedHashMap<>();
        if (request.startTime() != null) {
            parts.put("开始", request.startTime());
        }
        if (request.endTime() != null) {
            parts.put("结束", request.endTime());
        }
        if (request.userId() != null) {
            parts.put("用户ID", String.valueOf(request.userId()));
        }
        putIfPresent(parts, "账号", request.userName());
        putIfPresent(parts, "模块", request.module());
        putIfPresent(parts, "操作", request.action());
        putIfPresent(parts, "对象类型", request.objectType());
        putIfPresent(parts, "对象ID", request.objectId());
        putIfPresent(parts, "结果", request.result());
        putIfPresent(parts, "IP", request.ip());
        putIfPresent(parts, "traceId", request.traceId());
        if (request.tenantId() != null) {
            parts.put("租户ID", String.valueOf(request.tenantId()));
        }
        return parts.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .reduce((a, b) -> a + "，" + b)
                .orElse("全部（默认时间窗）");
    }

    private static void putIfPresent(Map<String, String> parts, String label, String value) {
        String trimmed = AuditQuerySupport.trim(value);
        if (trimmed != null) {
            parts.put(label, trimmed);
        }
    }

    /** 服务内自检：必须处于租户上下文（防误在平台上下文查租户表）。 */
    static void requireTenantContext() {
        TenantContext.State state = TenantContext.require();
        if (state.platform()) {
            throw new ApiException(403, 40300, "平台账号不能访问医院端审计中心");
        }
    }
}
