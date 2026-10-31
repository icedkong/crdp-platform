package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.tenant.UnknownTenantDataSourceException;
import com.crdp.framework.web.ApiException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 租户生命周期（E05-T02 / PLT-TEN-010、011、013，验收：状态流转按规格，续期即恢复）。
 *
 * <p>状态流转严格按平台规格第 2 章状态图：本任务提供人工可达的三条边——转正（试用→启用）、手动停用（启用→停用）、恢复（停用→启用）； 到期进入宽限期与宽限期满自动停用是
 * E05-T03（PLT-TEN-012），注销流转是 E05-T08（PLT-TEN-018..021）。到期设置按租户配置到期类型、提醒节点、
 * 宽限期天数与宽限期是否只读（已确认项）；长期有效租户不提醒、不进入宽限期，三项清空。续期（修改到期日或改为长期有效）时，宽限期或停用中的
 * 租户立即恢复启用。人工变更一律填原因并写平台审计（TENANT_STATUS / TENANT_EXPIRE，不落敏感明文——本域只有状态与日期）。
 */
@Service
public class TenantLifecycleService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 提醒节点与宽限期的默认值（规格「到期流程」：默认到期前 30、7、1 天提醒、宽限期 15 天，均可按租户调整）。 */
    static final List<Integer> DEFAULT_REMIND_DAYS = List.of(30, 7, 1);

    static final int DEFAULT_GRACE_DAYS = 15;

    private static final int REASON_MAX = 200;

    private static final String SELECT_ROW =
            "SELECT id, code, name, status, expire_type, expire_at, remind_days, grace_days,"
                    + " grace_read_only FROM plt_tenant WHERE id = ?";

    private final JdbcTemplate platform;
    private final TenantRoutingDataSource routing;
    private final SessionStore sessions;
    private final AuditLogger audit;

    public TenantLifecycleService(
            JdbcTemplate platform,
            TenantRoutingDataSource routing,
            SessionStore sessions,
            AuditLogger audit) {
        this.platform = platform;
        this.routing = routing;
        this.sessions = sessions;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 状态变更请求（PLT-TEN-010）：action 为 ENABLE（转正 / 恢复）或 DISABLE（手动停用）。 */
    public record StatusChangeRequest(String action, String reason) {}

    /**
     * 到期设置 / 续期请求（PLT-TEN-011、013）。remindDays / graceDays / graceReadOnly 可省略： 省略时沿用租户已配置的值，首次配置
     * FIXED 时用默认值；expireType=LONG_TERM 时三项与到期日一并清空。
     */
    public record ExpireRequest(
            String expireType,
            String expireAt,
            List<Integer> remindDays,
            Integer graceDays,
            Boolean graceReadOnly,
            String reason) {}

    /** 租户当前的到期设置快照。 */
    public record ExpiryView(
            String expireType,
            LocalDate expireAt,
            List<Integer> remindDays,
            Integer graceDays,
            boolean graceReadOnly) {}

    /** 状态变更影响面（状态变更对话框：在线用户数、进行中任务）。 */
    public record ImpactView(
            long tenantId,
            String code,
            String name,
            String status,
            long onlineUserCount,
            Long pendingTaskCount) {}

    public record StatusResult(
            long tenantId,
            String code,
            String previousStatus,
            String status,
            long onlineUserCount) {}

    public record ExpireResult(
            long tenantId,
            String code,
            String previousStatus,
            String status,
            ExpiryView expiry,
            List<String> changedFields) {}

    // ---------------------------------------------------------------- 查询

    /** 影响面：在线用户数取「租户 → 令牌」会话索引；随访任务表（fu_task）未建时进行中任务数为空。 */
    public ImpactView impact(long tenantId) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> row = requireRow(tenantId);
                    long id = ((Number) row.get("id")).longValue();
                    String code = str(row.get("code"));
                    return new ImpactView(
                            id,
                            code,
                            str(row.get("name")),
                            str(row.get("status")),
                            sessions.listForTenant(id).size(),
                            pendingTaskCount(id, code));
                });
    }

    // ---------------------------------------------------------------- 状态变更（PLT-TEN-010）

    public StatusResult changeStatus(long tenantId, StatusChangeRequest request) {
        if (request == null) {
            throw new ApiException(400, 40001, "请求体不能为空");
        }
        String action = request.action() == null ? "" : request.action().trim();
        if (!"ENABLE".equals(action) && !"DISABLE".equals(action)) {
            throw new ApiException(400, 40001, "action 只能是 ENABLE 或 DISABLE");
        }
        String reason = reasonOf(request.reason());

        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> row = requireRow(tenantId);
                    String current = str(row.get("status"));
                    String target = transitionTarget(current, action);
                    int updated =
                            platform.update(
                                    "UPDATE plt_tenant SET status = ? WHERE id = ? AND status = ?",
                                    target,
                                    tenantId,
                                    current);
                    if (updated == 0) {
                        throw new ApiException(409, 40901, "租户状态已变化，请刷新后重试");
                    }
                    audit.record(
                            new AuditEvent(
                                    "TENANT_STATUS",
                                    CurrentSession.current()
                                            .map(principal -> principal.userId())
                                            .orElse(null),
                                    "UPDATE",
                                    "plt_tenant:" + row.get("code"),
                                    "状态 " + current + "→" + target + "；原因：" + reason));
                    return new StatusResult(
                            tenantId,
                            str(row.get("code")),
                            current,
                            target,
                            sessions.listForTenant(tenantId).size());
                });
    }

    // ---------------------------------------------------------------- 到期设置与续期（PLT-TEN-011、013）

    public ExpireResult applyExpiry(long tenantId, ExpireRequest request) {
        if (request == null) {
            throw new ApiException(400, 40001, "请求体不能为空");
        }
        String reason = reasonOf(request.reason());

        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> row = requireRow(tenantId);
                    String currentStatus = str(row.get("status"));
                    String newStatus = statusAfterRenewal(currentStatus);
                    ExpiryView current = expiryOf(row);
                    ExpiryView merged = mergedExpiry(current, request, LocalDate.now(ZONE));

                    List<String> changed = changesOf(current, merged);
                    platform.update(
                            "UPDATE plt_tenant SET status = ?, expire_type = ?, expire_at = ?,"
                                    + " remind_days = ?, grace_days = ?, grace_read_only = ?"
                                    + " WHERE id = ?",
                            newStatus,
                            merged.expireType(),
                            merged.expireAt(),
                            joinRemindDays(merged.remindDays()),
                            merged.graceDays(),
                            merged.graceReadOnly() ? 1 : 0,
                            tenantId);

                    List<String> detail = new ArrayList<>(changed);
                    if (!newStatus.equals(currentStatus)) {
                        detail.add(0, "状态 " + currentStatus + "→" + newStatus + "（续期即恢复）");
                    }
                    detail.add("原因：" + reason);
                    audit.record(
                            new AuditEvent(
                                    "TENANT_EXPIRE",
                                    CurrentSession.current()
                                            .map(principal -> principal.userId())
                                            .orElse(null),
                                    "UPDATE",
                                    "plt_tenant:" + row.get("code"),
                                    String.join("；", detail)));
                    return new ExpireResult(
                            tenantId,
                            str(row.get("code")),
                            currentStatus,
                            newStatus,
                            merged,
                            List.copyOf(changed));
                });
    }

    // ---------------------------------------------------------------- 纯函数（供单测与向导接入）

    /**
     * 人工状态流转（严格按规格状态图）：ENABLE 从试用是转正、从停用是恢复；DISABLE 只从启用（手动停用）。 图上没有的边返回 40901
     * 并说明原因：宽限期→启用只走续期（PLT-TEN-013），试用与宽限期不能直接停用，开通 / 注销各态不在此处流转。
     */
    static String transitionTarget(String current, String action) {
        if (action == null || action.isBlank()) {
            throw new ApiException(400, 40001, "action 只能是 ENABLE 或 DISABLE");
        }
        switch (action) {
            case "ENABLE" -> {
                return switch (current) {
                    case "TRIAL" -> "ENABLED";
                    case "DISABLED" -> "ENABLED";
                    case "ENABLED" -> throw new ApiException(409, 40901, "租户已是启用状态");
                    case "GRACE" ->
                            throw new ApiException(409, 40901, "宽限期租户请通过「续期」恢复启用（PLT-TEN-013）");
                    case "PROVISIONING" ->
                            throw new ApiException(409, 40901, "开通中的租户不能变更状态，请在开通进度页处理");
                    case "CANCELLING" ->
                            throw new ApiException(
                                    409, 40901, "注销中的租户不能恢复，注销撤销见 PLT-TEN-018（E05-T08）");
                    case "CANCELLED" -> throw new ApiException(409, 40901, "已注销的租户不能恢复");
                    default -> throw new ApiException(409, 40901, "未知的租户状态：" + current);
                };
            }
            case "DISABLE" -> {
                return switch (current) {
                    case "ENABLED" -> "DISABLED";
                    case "TRIAL" -> throw new ApiException(409, 40901, "试用租户须先转正（ENABLE）才能停用");
                    case "GRACE" ->
                            throw new ApiException(
                                    409, 40901, "宽限期租户到期未续费将自动停用（PLT-TEN-012），不能手动停用");
                    case "DISABLED" -> throw new ApiException(409, 40901, "租户已是停用状态");
                    case "PROVISIONING" ->
                            throw new ApiException(409, 40901, "开通未完成的租户请从开通进度页放弃回收，不能停用");
                    case "CANCELLING", "CANCELLED" ->
                            throw new ApiException(409, 40901, "注销流程中的租户不能停用");
                    default -> throw new ApiException(409, 40901, "未知的租户状态：" + current);
                };
            }
            default -> throw new ApiException(400, 40001, "action 只能是 ENABLE 或 DISABLE");
        }
    }

    /** 续期后的状态（PLT-TEN-013）：宽限期或停用中立即恢复启用；试用与启用保持不变。 */
    static String statusAfterRenewal(String current) {
        return switch (current) {
            case "GRACE", "DISABLED" -> "ENABLED";
            case "TRIAL", "ENABLED" -> current;
            case "PROVISIONING" -> throw new ApiException(409, 40901, "开通中的租户不能设置到期，请等待开通完成");
            case "CANCELLING", "CANCELLED" -> throw new ApiException(409, 40901, "注销流程中的租户不能续期");
            default -> throw new ApiException(409, 40901, "未知的租户状态：" + current);
        };
    }

    /**
     * 合并到期设置：LONG_TERM 清空提醒与宽限（长期有效不提醒、不进入宽限期）；FIXED 必须给未来到期日， 省略的提醒节点 / 宽限期沿用当前配置，首次配置用默认值。
     * 新建向导（跨包）也用它做创建时的到期校验。
     */
    public static ExpiryView mergedExpiry(
            ExpiryView current, ExpireRequest request, LocalDate today) {
        if (request == null || request.expireType() == null || request.expireType().isBlank()) {
            throw new ApiException(400, 40001, "到期类型必填（FIXED 或 LONG_TERM）");
        }
        String type = request.expireType().trim();
        if ("LONG_TERM".equals(type)) {
            return new ExpiryView("LONG_TERM", null, null, null, false);
        }
        if (!"FIXED".equals(type)) {
            throw new ApiException(400, 40001, "未知的到期类型：" + type);
        }
        if (request.expireAt() == null || request.expireAt().isBlank()) {
            throw new ApiException(400, 40001, "有到期日的租户必须填写到期日（yyyy-MM-dd）");
        }
        LocalDate expireAt;
        try {
            expireAt = LocalDate.parse(request.expireAt().trim());
        } catch (RuntimeException ex) {
            throw new ApiException(400, 40001, "到期日格式应为 yyyy-MM-dd");
        }
        if (!expireAt.isAfter(today)) {
            throw new ApiException(400, 40001, "到期日必须晚于当天（北京时间）");
        }
        List<Integer> remindDays =
                request.remindDays() == null
                        ? (current.remindDays() == null
                                ? DEFAULT_REMIND_DAYS
                                : current.remindDays())
                        : normalizeRemindDays(request.remindDays());
        Integer graceDays =
                request.graceDays() == null
                        ? (current.graceDays() == null ? DEFAULT_GRACE_DAYS : current.graceDays())
                        : normalizeGraceDays(request.graceDays());
        boolean graceReadOnly =
                request.graceReadOnly() == null ? current.graceReadOnly() : request.graceReadOnly();
        return new ExpiryView("FIXED", expireAt, remindDays, graceDays, graceReadOnly);
    }

    /** 人工变更的原因（PLT-TEN-010：人工变更需填写原因）：必填、去首尾空格、最长 200 字。 */
    static String reasonOf(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ApiException(400, 40001, "必须填写变更原因");
        }
        String trimmed = reason.trim();
        if (trimmed.length() > REASON_MAX) {
            throw new ApiException(400, 40001, "变更原因过长（最多 " + REASON_MAX + " 个字符）");
        }
        return trimmed;
    }

    /** 存储格式（逗号分隔降序）→ 列表；空串与 null 都表示未配置（长期有效）。 */
    static List<Integer> parseRemindDays(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            List<Integer> days = new ArrayList<>();
            for (String part : stored.split(",")) {
                days.add(Integer.valueOf(part.trim()));
            }
            return days;
        } catch (NumberFormatException ex) {
            throw new ApiException(500, 50000, "提醒节点存储格式损坏");
        }
    }

    /** 列表 → 存储格式（降序，与列注释一致）；null 表示未配置。开通编排（跨包）写入新租户的提醒节点时也用它。 */
    public static String joinRemindDays(List<Integer> remindDays) {
        if (remindDays == null) {
            return null;
        }
        List<String> parts =
                new TreeSet<>(remindDays).descendingSet().stream().map(String::valueOf).toList();
        return String.join(",", parts);
    }

    private static List<Integer> normalizeRemindDays(List<Integer> remindDays) {
        if (remindDays.isEmpty()) {
            throw new ApiException(400, 40001, "提醒节点至少一个（到期前 N 天）");
        }
        TreeSet<Integer> unique = new TreeSet<>(remindDays);
        for (Integer day : unique) {
            if (day == null || day < 1 || day > 365) {
                throw new ApiException(400, 40001, "提醒节点须在 1 至 365 天之间");
            }
        }
        // TreeSet 升序，展示与存储都用降序（先 30 后 7 后 1）
        return unique.descendingSet().stream().toList();
    }

    private static int normalizeGraceDays(Integer graceDays) {
        if (graceDays == null || graceDays < 1 || graceDays > 365) {
            throw new ApiException(400, 40001, "宽限期天数须在 1 至 365 天之间");
        }
        return graceDays;
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> requireRow(long tenantId) {
        List<Map<String, Object>> rows = platform.queryForList(SELECT_ROW, tenantId);
        if (rows.isEmpty()) {
            throw new ApiException(404, 40400, "租户不存在");
        }
        return rows.get(0);
    }

    private static ExpiryView expiryOf(Map<String, Object> row) {
        return new ExpiryView(
                str(row.get("expire_type")),
                localDate(row.get("expire_at")),
                parseRemindDays(str(row.get("remind_days"))),
                row.get("grace_days") == null ? null : ((Number) row.get("grace_days")).intValue(),
                ((Number) row.get("grace_read_only")).intValue() == 1);
    }

    /** 旧值 → 新值的字段级差异（审计只记变化项，不落敏感明文；本域为状态与日期）。 */
    private static List<String> changesOf(ExpiryView current, ExpiryView next) {
        List<String> changed = new ArrayList<>();
        if (!equalsNullable(current.expireType(), next.expireType())) {
            changed.add("expire_type " + current.expireType() + "→" + next.expireType());
        }
        if (!equalsNullable(current.expireAt(), next.expireAt())) {
            changed.add(
                    "expire_at "
                            + (current.expireAt() == null ? "空" : current.expireAt())
                            + "→"
                            + (next.expireAt() == null ? "空" : next.expireAt()));
        }
        if (!equalsNullable(current.remindDays(), next.remindDays())) {
            changed.add(
                    "remind_days "
                            + (current.remindDays() == null
                                    ? "空"
                                    : joinRemindDays(current.remindDays()))
                            + "→"
                            + (next.remindDays() == null
                                    ? "空"
                                    : joinRemindDays(next.remindDays())));
        }
        if (!equalsNullable(current.graceDays(), next.graceDays())) {
            changed.add(
                    "grace_days "
                            + (current.graceDays() == null ? "空" : current.graceDays())
                            + "→"
                            + (next.graceDays() == null ? "空" : next.graceDays()));
        }
        if (current.graceReadOnly() != next.graceReadOnly()) {
            changed.add(
                    "grace_read_only "
                            + (current.graceReadOnly() ? 1 : 0)
                            + "→"
                            + (next.graceReadOnly() ? 1 : 0));
        }
        return changed;
    }

    /** 进行中随访任务数（停用影响面）：fu_task 表随 E15 提供，未建或租户库不可达时返回空，不阻塞对话框。 */
    private Long pendingTaskCount(long tenantId, String code) {
        if (!routing.hasTenant(tenantId)) {
            return null;
        }
        try {
            return TenantContext.callAsTenant(
                    tenantId,
                    code,
                    () -> {
                        Integer present =
                                platform.queryForObject(
                                        "SELECT COUNT(*) FROM information_schema.TABLES"
                                                + " WHERE TABLE_SCHEMA = DATABASE()"
                                                + " AND TABLE_NAME = 'fu_task'",
                                        Integer.class);
                        if (present == null || present == 0) {
                            return null;
                        }
                        return platform.queryForObject(
                                "SELECT COUNT(*) FROM fu_task"
                                        + " WHERE status NOT IN ('DONE', 'CLOSED', 'CANCELLED')",
                                Long.class);
                    });
        } catch (UnknownTenantDataSourceException | DataAccessException ex) {
            return null;
        }
    }

    private static boolean equalsNullable(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
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
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }
}
