package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.tenant.UnknownTenantDataSourceException;
import com.crdp.framework.web.ApiException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 租户目录（E05-T01 / PLT-TEN-001、PLT-TEN-009，验收：按名称、状态检索；修改留记录）。
 *
 * <p>只读平台库：列表按名称/编码关键词、状态、到期日、模块检索，行内带已开通模块与用户数（用户数实时取自 租户库
 * sys_user，租户库不可达时置空不阻塞列表）。信息维护编辑基本信息、联系人与备注；电话经 {@link CryptoService} 信封加密存储
 * （purpose=plt-tenant-phone，平台库 crypto_data_key，见 OQ-34），列表不返回电话（明文可见范围见 OQ-35）；每次有字段变更写一条
 * TENANT_UPDATE 审计（只记字段名，不落敏感明文）。到期类型/到期日本任务只落列与展示，维护是 E05-T02；租户操作记录查询（PLT-TEN-022）是 E08-T01。
 */
@Service
public class TenantDirectoryService {

    /** 电话字段的加密用途（平台库 crypto_data_key 的 purpose）。 */
    static final String PHONE_PURPOSE = "plt-tenant-phone";

    /** PLT-TEN-010 的七个状态；开通中在向导阶段即产生。 */
    private static final Set<String> STATUSES =
            Set.of(
                    "PROVISIONING",
                    "TRIAL",
                    "ENABLED",
                    "GRACE",
                    "DISABLED",
                    "CANCELLING",
                    "CANCELLED");

    /** 排序白名单（值 → 排序列）；expireAt 特殊处理把长期有效排到最后。 */
    private static final Set<String> SORTS = Set.of("createdAt", "name", "code", "expireAt");

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate platform;
    private final TenantRoutingDataSource routing;
    private final CryptoService crypto;
    private final AuditLogger audit;

    public TenantDirectoryService(
            JdbcTemplate platform,
            TenantRoutingDataSource routing,
            CryptoService crypto,
            AuditLogger audit) {
        this.platform = platform;
        this.routing = routing;
        this.crypto = crypto;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 列表检索条件。expireBefore 为 ISO 日期（yyyy-MM-dd）：到期日不晚于该日的租户。 */
    public record ListQuery(
            String keyword,
            String status,
            String expireBefore,
            String moduleCode,
            int pageNo,
            int pageSize,
            String sort) {

        /** 仅筛选条件（分页与排序取默认值），供构造 WHERE 的纯函数使用。 */
        public ListQuery(String keyword, String status, String expireBefore, String moduleCode) {
            this(keyword, status, expireBefore, moduleCode, 1, 20, null);
        }
    }

    /** 检索条件 → SQL 片段与参数（值全部参数化，片段只来自白名单）。 */
    public record PreparedFilter(String whereSql, List<Object> args) {}

    public record ModuleRef(String code, String name) {}

    /** 列表行（PLT-TEN-001）：到期日长期有效时 expireAt 为空；用户数取不到时为空。 */
    public record TenantListItem(
            long id,
            String code,
            String name,
            String shortName,
            String status,
            String expireType,
            LocalDate expireAt,
            List<ModuleRef> modules,
            Long userCount,
            OffsetDateTime createdAt) {}

    /**
     * 详情（PLT-TEN-009）：含联系人与备注；电话仅在详情返回明文供维护，列表不返回。 remindDays / graceDays / graceReadOnly
     * 是到期设置（E05-T02 / PLT-TEN-011），长期有效租户 remindDays 与 graceDays 为空。
     */
    public record TenantDetail(
            long id,
            String code,
            String name,
            String shortName,
            String creditCode,
            String orgType,
            String region,
            String contactName,
            String contactPhone,
            String contactEmail,
            String contractNo,
            String remark,
            String status,
            String expireType,
            LocalDate expireAt,
            List<Integer> remindDays,
            Integer graceDays,
            boolean graceReadOnly,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {}

    /** 信息维护请求：整体替换可编辑字段（编码、状态、到期不在此列）。空串清空、null 视为空。 */
    public record UpdateRequest(
            String name,
            String shortName,
            String creditCode,
            String orgType,
            String region,
            String contactName,
            String contactPhone,
            String contactEmail,
            String contractNo,
            String remark) {}

    /** 分页响应（约定 {items,total,pageNo,pageSize}）。 */
    public record PageView<T>(List<T> items, long total, int pageNo, int pageSize) {}

    // ---------------------------------------------------------------- 查询

    public PageView<TenantListItem> list(ListQuery query) {
        int pageNo = Math.max(1, query.pageNo());
        int pageSize = Math.min(100, Math.max(1, query.pageSize()));
        PreparedFilter filter = prepareFilter(query);
        String order = orderClause(query.sort());

        return TenantContext.callAsPlatform(
                () -> {
                    Long total =
                            platform.queryForObject(
                                    "SELECT COUNT(*) FROM plt_tenant t" + filter.whereSql(),
                                    Long.class,
                                    filter.args().toArray());
                    int offset = (pageNo - 1) * pageSize;
                    List<Object> pageArgs = new ArrayList<>(filter.args());
                    pageArgs.add(pageSize);
                    pageArgs.add(offset);
                    List<Map<String, Object>> rows =
                            platform.queryForList(
                                    "SELECT t.id, t.code, t.name, t.short_name, t.status,"
                                            + " t.expire_type, t.expire_at, t.created_at"
                                            + " FROM plt_tenant t"
                                            + filter.whereSql()
                                            + order
                                            + " LIMIT ? OFFSET ?",
                                    pageArgs.toArray());
                    Map<Long, List<ModuleRef>> modules = modulesOf(rows);
                    List<TenantListItem> items =
                            rows.stream()
                                    .map(
                                            row ->
                                                    toListItem(
                                                            row,
                                                            modules.getOrDefault(
                                                                    ((Number) row.get("id"))
                                                                            .longValue(),
                                                                    List.of())))
                                    .toList();
                    return new PageView<>(items, total == null ? 0 : total, pageNo, pageSize);
                });
    }

    public TenantDetail detail(long tenantId) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> row = requireRow(tenantId);
                    return toDetail(row, decryptPhone(row.get("contact_phone")));
                });
    }

    // ---------------------------------------------------------------- 维护

    public TenantDetail update(long tenantId, UpdateRequest request) {
        validate(request);
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> current = requireRow(tenantId);
                    String name = request.name().trim();
                    if (!name.equals(current.get("name"))
                            && !platform.queryForList(
                                            "SELECT t.id FROM plt_tenant t WHERE t.name = ?", name)
                                    .isEmpty()) {
                        throw new ApiException(409, 40901, "医院名称已存在");
                    }

                    String currentPhone = decryptPhone(current.get("contact_phone"));
                    String phone = blankToNull(request.contactPhone());
                    boolean phoneChanged = !equalsNullable(currentPhone, phone);
                    String phoneCipher = str(current.get("contact_phone"));
                    if (phoneChanged) {
                        phoneCipher = phone == null ? null : crypto.encrypt(PHONE_PURPOSE, phone);
                    }

                    Map<String, Object> updated = new LinkedHashMap<>(current);
                    List<String> changed = new ArrayList<>();
                    put(updated, changed, "name", name, str(current.get("name")));
                    put(
                            updated,
                            changed,
                            "short_name",
                            blankToNull(request.shortName()),
                            str(current.get("short_name")));
                    put(
                            updated,
                            changed,
                            "credit_code",
                            blankToNull(request.creditCode()),
                            str(current.get("credit_code")));
                    put(
                            updated,
                            changed,
                            "org_type",
                            blankToNull(request.orgType()),
                            str(current.get("org_type")));
                    put(
                            updated,
                            changed,
                            "region",
                            blankToNull(request.region()),
                            str(current.get("region")));
                    put(
                            updated,
                            changed,
                            "contact_name",
                            blankToNull(request.contactName()),
                            str(current.get("contact_name")));
                    if (phoneChanged) {
                        updated.put("contact_phone", phoneCipher);
                        changed.add("contact_phone");
                    } else {
                        updated.put("contact_phone", current.get("contact_phone"));
                    }
                    put(
                            updated,
                            changed,
                            "contact_email",
                            blankToNull(request.contactEmail()),
                            str(current.get("contact_email")));
                    put(
                            updated,
                            changed,
                            "contract_no",
                            blankToNull(request.contractNo()),
                            str(current.get("contract_no")));
                    put(
                            updated,
                            changed,
                            "remark",
                            blankToNull(request.remark()),
                            str(current.get("remark")));

                    if (!changed.isEmpty()) {
                        platform.update(
                                "UPDATE plt_tenant SET name = ?, short_name = ?, credit_code = ?,"
                                        + " org_type = ?, region = ?, contact_name = ?, contact_phone = ?,"
                                        + " contact_email = ?, contract_no = ?, remark = ?,"
                                        + " updated_at = CURRENT_TIMESTAMP(3)"
                                        + " WHERE id = ?",
                                name,
                                updated.get("short_name"),
                                updated.get("credit_code"),
                                updated.get("org_type"),
                                updated.get("region"),
                                updated.get("contact_name"),
                                updated.get("contact_phone"),
                                updated.get("contact_email"),
                                updated.get("contract_no"),
                                updated.get("remark"),
                                tenantId);
                        audit.record(
                                new AuditEvent(
                                        "TENANT_UPDATE",
                                        CurrentSession.current()
                                                .map(principal -> principal.userId())
                                                .orElse(null),
                                        "UPDATE",
                                        "plt_tenant:" + current.get("code"),
                                        "修改字段：" + String.join(",", changed)));
                    }
                    return detail(tenantId);
                });
    }

    // ---------------------------------------------------------------- 检索构造（纯函数，供单测）

    /** 校验并构造 WHERE。keyword 匹配名称或编码（LIKE 转义），expireBefore 只含有到期日的租户。 */
    static PreparedFilter prepareFilter(ListQuery query) {
        List<String> clauses = new ArrayList<>();
        List<Object> args = new ArrayList<>();

        String keyword = query.keyword() == null ? "" : query.keyword().trim();
        if (!keyword.isEmpty()) {
            if (keyword.length() > 64) {
                throw new ApiException(400, 40001, "关键词过长（最多 64 个字符）");
            }
            String escaped = escapeLike(keyword);
            clauses.add("(t.name LIKE ? OR t.code LIKE ?)");
            args.add("%" + escaped + "%");
            args.add("%" + escaped + "%");
        }
        if (query.status() != null && !query.status().isBlank()) {
            if (!STATUSES.contains(query.status())) {
                throw new ApiException(400, 40001, "未知的租户状态：" + query.status());
            }
            clauses.add("t.status = ?");
            args.add(query.status());
        }
        if (query.expireBefore() != null && !query.expireBefore().isBlank()) {
            LocalDate date;
            try {
                date = LocalDate.parse(query.expireBefore().trim());
            } catch (RuntimeException ex) {
                throw new ApiException(400, 40001, "到期日格式应为 yyyy-MM-dd");
            }
            clauses.add("t.expire_type = 'FIXED' AND t.expire_at <= ?");
            args.add(date);
        }
        if (query.moduleCode() != null && !query.moduleCode().isBlank()) {
            clauses.add(
                    "EXISTS (SELECT 1 FROM plt_tenant_module pm WHERE pm.tenant_id = t.id"
                            + " AND pm.module_code = ? AND pm.status = 'OPEN')");
            args.add(query.moduleCode().trim());
        }
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        return new PreparedFilter(where, args);
    }

    /** 排序白名单；支持 "-" 前缀降序，默认创建时间倒序；expireAt 让长期有效（空）排在最后。 */
    static String orderClause(String sort) {
        String field = sort == null || sort.isBlank() ? "-createdAt" : sort.trim();
        boolean desc = field.startsWith("-");
        String name = desc ? field.substring(1) : field;
        if (!SORTS.contains(name)) {
            throw new ApiException(400, 40001, "不支持的排序字段：" + sort);
        }
        String column =
                switch (name) {
                    case "name" -> "t.name";
                    case "code" -> "t.code";
                    case "expireAt" -> "t.expire_at";
                    default -> "t.created_at";
                };
        String direction = desc ? "DESC" : "ASC";
        String idDirection = desc ? "DESC" : "ASC";
        if ("expireAt".equals(name)) {
            // MySQL 里 ASC 空值在前，这里显式把长期有效排到最后，保证“最近到期在前”有意义
            return " ORDER BY t.expire_at IS NULL, t.expire_at "
                    + direction
                    + ", t.id "
                    + idDirection;
        }
        return " ORDER BY " + column + " " + direction + ", t.id " + idDirection;
    }

    // ---------------------------------------------------------------- 内部

    private static final String SELECT_COLUMNS =
            "t.id, t.code, t.name, t.short_name, t.credit_code, t.org_type, t.region,"
                    + " t.contact_name, t.contact_phone, t.contact_email, t.contract_no, t.remark,"
                    + " t.status, t.expire_type, t.expire_at, t.remind_days, t.grace_days,"
                    + " t.grace_read_only, t.created_at, t.updated_at";

    private Map<String, Object> requireRow(long tenantId) {
        List<Map<String, Object>> rows =
                platform.queryForList(
                        "SELECT " + SELECT_COLUMNS + " FROM plt_tenant t WHERE t.id = ?", tenantId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "租户不存在");
        }
        return rows.get(0);
    }

    private Map<Long, List<ModuleRef>> modulesOf(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", rows.stream().map(r -> "?").toList());
        Object[] ids = rows.stream().map(r -> ((Number) r.get("id")).longValue()).toArray();
        List<Map<String, Object>> moduleRows =
                platform.queryForList(
                        "SELECT pm.tenant_id, pm.module_code, p.name AS module_name"
                                + " FROM plt_tenant_module pm JOIN plt_module p ON p.code = pm.module_code"
                                + " WHERE pm.status = 'OPEN' AND pm.tenant_id IN ("
                                + placeholders
                                + ")"
                                + " ORDER BY p.base_module DESC, pm.module_code",
                        ids);
        Map<Long, List<ModuleRef>> grouped = new HashMap<>();
        for (Map<String, Object> row : moduleRows) {
            grouped.computeIfAbsent(
                            ((Number) row.get("tenant_id")).longValue(), k -> new ArrayList<>())
                    .add(new ModuleRef(str(row.get("module_code")), str(row.get("module_name"))));
        }
        return grouped;
    }

    private Long countUsers(long tenantId, String code) {
        if (!routing.hasTenant(tenantId)) {
            return null;
        }
        try {
            return TenantContext.callAsTenant(
                    tenantId,
                    code,
                    () -> platform.queryForObject("SELECT COUNT(*) FROM sys_user", Long.class));
        } catch (UnknownTenantDataSourceException | DataAccessException ex) {
            return null;
        }
    }

    private TenantListItem toListItem(Map<String, Object> row, List<ModuleRef> modules) {
        long id = ((Number) row.get("id")).longValue();
        return new TenantListItem(
                id,
                str(row.get("code")),
                str(row.get("name")),
                str(row.get("short_name")),
                str(row.get("status")),
                str(row.get("expire_type")),
                localDate(row.get("expire_at")),
                modules,
                countUsers(id, str(row.get("code"))),
                offsetDateTime(row.get("created_at")));
    }

    private TenantDetail toDetail(Map<String, Object> row, String phone) {
        return new TenantDetail(
                ((Number) row.get("id")).longValue(),
                str(row.get("code")),
                str(row.get("name")),
                str(row.get("short_name")),
                str(row.get("credit_code")),
                str(row.get("org_type")),
                str(row.get("region")),
                str(row.get("contact_name")),
                phone,
                str(row.get("contact_email")),
                str(row.get("contract_no")),
                str(row.get("remark")),
                str(row.get("status")),
                str(row.get("expire_type")),
                localDate(row.get("expire_at")),
                TenantLifecycleService.parseRemindDays(str(row.get("remind_days"))),
                row.get("grace_days") == null ? null : ((Number) row.get("grace_days")).intValue(),
                ((Number) row.get("grace_read_only")).intValue() == 1,
                offsetDateTime(row.get("created_at")),
                offsetDateTime(row.get("updated_at")));
    }

    private String decryptPhone(Object cipher) {
        if (cipher == null) {
            return null;
        }
        return crypto.decrypt(str(cipher));
    }

    private static void validate(UpdateRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ApiException(400, 40001, "医院名称必填");
        }
        if (request.name().trim().length() > 128) {
            throw new ApiException(400, 40001, "医院名称过长（最多 128 个字符）");
        }
        lengthLimit("简称", request.shortName(), 64);
        lengthLimit("统一社会信用代码", request.creditCode(), 32);
        lengthLimit("机构类型与等级", request.orgType(), 64);
        lengthLimit("所在地区", request.region(), 128);
        lengthLimit("联系人", request.contactName(), 64);
        lengthLimit("联系电话", request.contactPhone(), 32);
        lengthLimit("联系邮箱", request.contactEmail(), 128);
        lengthLimit("合同编号", request.contractNo(), 64);
        lengthLimit("备注", request.remark(), 255);
    }

    private static void lengthLimit(String label, String value, int max) {
        if (value != null && value.trim().length() > max) {
            throw new ApiException(400, 40001, label + "过长（最多 " + max + " 个字符）");
        }
    }

    private static void put(
            Map<String, Object> target,
            List<String> changed,
            String column,
            String value,
            String current) {
        target.put(column, value);
        if (!equalsNullable(current, value)) {
            changed.add(column);
        }
    }

    private static boolean equalsNullable(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String escapeLike(String value) {
        return value.replaceAll("([%_\\\\])", "\\\\$1");
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDate localDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static OffsetDateTime offsetDateTime(Object value) {
        if (value == null) {
            return null;
        }
        LocalDateTime local =
                value instanceof LocalDateTime time ? time : ((Timestamp) value).toLocalDateTime();
        return local.atZone(ZONE).toOffsetDateTime();
    }
}
