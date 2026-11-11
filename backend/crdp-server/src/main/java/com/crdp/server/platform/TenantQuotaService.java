package com.crdp.server.platform;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.CurrentSession;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.tenant.UnknownTenantDataSourceException;
import com.crdp.framework.web.ApiException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 配额管理（E05-T09 / PLT-TEN-014）：用户数上限与存储上限，默认不限。
 *
 * <p>上限落在 plt_tenant（V67：user_limit / storage_limit_bytes，NULL=不限）。视图实时带用量： 启用中用户数（sys_user
 * status='ENABLED'，与拦截口径一致——新建即启用、启用停用账号都会 +1）与存储用量（sys_file 未删除行 size_bytes 合计）；
 * 租户库不可达时用量置空不阻塞（与租户列表 userCount 同口径）。「预警」按 80%（platform-spec §2：存储 80% 为建议值，用户数同取 80%）；
 * 达到上限即「满额」。上限不强制高于当前用量：调低只挡新增，不回收既有账号或文件。
 *
 * <p>租户侧拦截（PLT-ORG-010：达到上限禁止新建或启用用户；存储达上限禁止上传）在 {@link TenantQuotaGuard} 与 文件服务（{@code
 * TenantStorageQuota} 端口），本服务只管平台侧的查看与维护；每次修改写 TENANT_QUOTA 审计（前后值）。
 */
@Service
public class TenantQuotaService {

    /** 预警阈值（80%，platform-spec §2 建议值；用户数与存储同口径）。 */
    static final double WARNING_RATIO = 0.8;

    private final JdbcTemplate platform;
    private final TenantRoutingDataSource routing;
    private final AuditLogger audit;

    public TenantQuotaService(
            JdbcTemplate platform, TenantRoutingDataSource routing, AuditLogger audit) {
        this.platform = platform;
        this.routing = routing;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- DTO

    /** 配额视图：用量与预警状态。enabledUserCount / storageUsedBytes 为 null 表示租户库不可达（不算预警）。 */
    public record QuotaView(
            Integer userLimit,
            Long storageLimitBytes,
            Long enabledUserCount,
            Long storageUsedBytes,
            boolean userWarning,
            boolean userLimitReached,
            boolean storageWarning,
            boolean storageLimitReached) {

        static QuotaView of(
                Integer userLimit,
                Long storageLimitBytes,
                Long enabledUserCount,
                Long storageUsedBytes) {
            QuotaUsage users =
                    QuotaUsage.of(
                            userLimit == null ? null : userLimit.longValue(), enabledUserCount);
            QuotaUsage storage = QuotaUsage.of(storageLimitBytes, storageUsedBytes);
            return new QuotaView(
                    userLimit,
                    storageLimitBytes,
                    enabledUserCount,
                    storageUsedBytes,
                    users.warning(),
                    users.limitReached(),
                    storage.warning(),
                    storage.limitReached());
        }
    }

    /** 维护请求：null = 不限（清除上限）。 */
    public record QuotaUpdateRequest(Integer userLimit, Long storageLimitBytes) {}

    /** 单项配额的预警 / 满额状态（纯函数，供单测）。 */
    record QuotaUsage(Long limit, Long used) {

        static QuotaUsage of(Long limit, Long used) {
            return new QuotaUsage(limit, used);
        }

        boolean warning() {
            return limit != null && used != null && (double) used / limit >= WARNING_RATIO;
        }

        boolean limitReached() {
            return limit != null && used != null && used >= limit;
        }
    }

    // ---------------------------------------------------------------- 校验（纯函数）

    /** 上限合法性：null（不限）或 >= 1；返回中文错误文案，合法返回 null。 */
    public static String limitError(Integer userLimit, Long storageLimitBytes) {
        if (userLimit != null && userLimit < 1) {
            return "用户数上限须为不小于 1 的整数";
        }
        if (storageLimitBytes != null && storageLimitBytes < 1) {
            return "存储上限须为不小于 1 字节";
        }
        return null;
    }

    // ---------------------------------------------------------------- 查询

    public QuotaView view(long tenantId) {
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> row = requireRow(tenantId);
                    return viewOf(
                            tenantId,
                            intOrNull(row.get("user_limit")),
                            longOrNull(row.get("storage_limit_bytes")));
                });
    }

    /** 用已知上限组装视图（维护后不重查上限，只补实时用量）。 */
    private QuotaView viewOf(long tenantId, Integer userLimit, Long storageLimitBytes) {
        return QuotaView.of(
                userLimit,
                storageLimitBytes,
                tenantValue(tenantId, "SELECT COUNT(*) FROM sys_user WHERE status = 'ENABLED'"),
                tenantValue(
                        tenantId,
                        "SELECT COALESCE(SUM(size_bytes), 0) FROM sys_file"
                                + " WHERE status <> 'DELETED'"));
    }

    // ---------------------------------------------------------------- 维护

    public QuotaView update(long tenantId, QuotaUpdateRequest request) {
        String error =
                limitError(
                        request == null ? null : request.userLimit(),
                        request == null ? null : request.storageLimitBytes());
        if (error != null) {
            throw new ApiException(400, 40001, error);
        }
        return TenantContext.callAsPlatform(
                () -> {
                    Map<String, Object> current = requireRow(tenantId);
                    Integer userLimit = request == null ? null : request.userLimit();
                    Long storageLimitBytes = request == null ? null : request.storageLimitBytes();
                    platform.update(
                            "UPDATE plt_tenant SET user_limit = ?, storage_limit_bytes = ?,"
                                    + " updated_at = CURRENT_TIMESTAMP(3) WHERE id = ?",
                            userLimit,
                            storageLimitBytes,
                            tenantId);
                    Integer previousUserLimit = intOrNull(current.get("user_limit"));
                    Long previousStorage = longOrNull(current.get("storage_limit_bytes"));
                    boolean unchanged =
                            java.util.Objects.equals(previousUserLimit, userLimit)
                                    && java.util.Objects.equals(previousStorage, storageLimitBytes);
                    if (!unchanged) {
                        audit.record(
                                new AuditEvent(
                                        "TENANT_QUOTA",
                                        CurrentSession.current()
                                                .map(principal -> principal.userId())
                                                .orElse(null),
                                        "UPDATE",
                                        "plt_tenant:" + current.get("code"),
                                        "配额变更：用户数上限 "
                                                + quotaText(
                                                        previousUserLimit == null
                                                                ? null
                                                                : previousUserLimit.longValue())
                                                + " → "
                                                + quotaText(
                                                        userLimit == null
                                                                ? null
                                                                : userLimit.longValue())
                                                + "；存储上限 "
                                                + storageText(previousStorage)
                                                + " → "
                                                + storageText(storageLimitBytes)));
                    }
                    return viewOf(tenantId, userLimit, storageLimitBytes);
                });
    }

    // ---------------------------------------------------------------- 内部

    private Map<String, Object> requireRow(long tenantId) {
        try {
            return platform.queryForMap(
                    "SELECT code, user_limit, storage_limit_bytes FROM plt_tenant WHERE id = ?",
                    tenantId);
        } catch (EmptyResultDataAccessException ex) {
            throw new ApiException(404, 40400, "租户不存在");
        }
    }

    /** 进租户库读一个数值；租户库不可达时返回 null（配额展示不阻塞，与列表 userCount 同口径）。 */
    private Long tenantValue(long tenantId, String sql) {
        if (!routing.hasTenant(tenantId)) {
            return null;
        }
        String code = tenantCode(tenantId);
        try {
            return TenantContext.callAsTenant(
                    tenantId,
                    code,
                    () -> {
                        Long value = platform.queryForObject(sql, Long.class);
                        return value == null ? 0L : value;
                    });
        } catch (UnknownTenantDataSourceException | DataAccessException ex) {
            return null;
        }
    }

    private String tenantCode(long tenantId) {
        return platform.queryForObject(
                "SELECT code FROM plt_tenant WHERE id = ?", String.class, tenantId);
    }

    private static Integer intOrNull(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static Long longOrNull(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    /** 用户数上限文案：null = 不限。 */
    static String quotaText(Long limit) {
        return limit == null ? "不限" : String.valueOf(limit);
    }

    /** 存储上限文案：字节原值（前端按 GB/MB 展示，审计保留精确值）。 */
    static String storageText(Long bytes) {
        return bytes == null ? "不限" : bytes + " 字节";
    }
}
