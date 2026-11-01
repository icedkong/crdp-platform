package com.crdp.server.platform;

import com.crdp.framework.security.SessionStore;
import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 停用处理与停用时段底座（E05-T03 / PLT-TEN-016、017）。
 *
 * <p>停用（人工 POST /tenants/{id}/status action=DISABLE 或宽限期满自动，PLT-TEN-012）：吊销该院全部在线会话 + 开一段停用时段。
 * 恢复（续期 PLT-TEN-013 或人工启用）：关闭时段。数据不动（数据保留），平台侧会话不受影响（平台仍可进入租户视角排障）。
 *
 * <p>停用补偿（PLT-TEN-017）：底座只提供停用时段（{@link #suspensionsOf}，GET /tenants/{id}/suspensions）；
 * 停用期间本应执行的任务标记为 「停用期间未执行」并由人工选择补做或关闭，是业务模块（fu_task 创建方式，E14-T06）按时段推导的事，恢复后不自动补发任何通知或任务。
 */
@Service
public class TenantSuspensionService {

    /** 停用原因类别（plt_tenant_suspension.cause）。 */
    public static final String CAUSE_MANUAL = "MANUAL";

    /** 宽限期满自动停用（PLT-TEN-012）。 */
    public static final String CAUSE_AUTO_GRACE_END = "AUTO_GRACE_END";

    /** 恢复途径：续期（PLT-TEN-013，宽限期或停用中续期即恢复）。 */
    public static final String RESTORE_RENEWAL = "RENEWAL";

    /** 恢复途径：人工启用（停用 → 启用）。 */
    public static final String RESTORE_MANUAL_ENABLE = "MANUAL_ENABLE";

    private final JdbcTemplate platform;

    private final SessionStore sessions;

    public TenantSuspensionService(JdbcTemplate platform, SessionStore sessions) {
        this.platform = platform;
        this.sessions = sessions;
    }

    /** 停用时段行（倒序）。restoredAt 为空表示仍在停用中。 */
    public record SuspensionView(
            long id,
            long tenantId,
            String cause,
            String reason,
            java.time.LocalDateTime disabledAt,
            java.time.LocalDateTime restoredAt,
            String restoreCause) {}

    /**
     * 停用处理（PLT-TEN-016）：吊销该院全部在线会话并开一段停用时段。幂等：已有未关闭时段时不重复开 （人工与自动并发停用只有一段）。
     *
     * @return 本次吊销的会话数（供审计与返回展示）
     */
    public long onDisabled(long tenantId, String cause, String reason) {
        long revoked = sessions.revokeAllForTenant(tenantId);
        TenantContext.callAsPlatform(
                () ->
                        platform.update(
                                """
                                INSERT INTO plt_tenant_suspension (tenant_id, cause, reason, disabled_at)
                                SELECT ?, ?, ?, CURRENT_TIMESTAMP(3) FROM DUAL
                                WHERE NOT EXISTS (
                                    SELECT 1 FROM plt_tenant_suspension
                                    WHERE tenant_id = ? AND restored_at IS NULL)
                                """,
                                tenantId,
                                cause,
                                abbreviate(reason),
                                tenantId));
        return revoked;
    }

    /** 恢复：关闭未关闭的停用时段（没有时段行则不操作——E05-T03 之前已停用的存量租户）。 */
    public void onRestored(long tenantId, String restoreCause) {
        TenantContext.callAsPlatform(
                () ->
                        platform.update(
                                """
                                UPDATE plt_tenant_suspension
                                SET restored_at = CURRENT_TIMESTAMP(3), restore_cause = ?
                                WHERE tenant_id = ? AND restored_at IS NULL
                                """,
                                restoreCause,
                                tenantId));
    }

    /** 某租户的停用时段（新→旧）。 */
    public List<SuspensionView> suspensionsOf(long tenantId) {
        return TenantContext.callAsPlatform(
                () ->
                        platform.query(
                                """
                                SELECT id, tenant_id, cause, reason, disabled_at, restored_at, restore_cause
                                FROM plt_tenant_suspension
                                WHERE tenant_id = ?
                                ORDER BY disabled_at DESC, id DESC
                                """,
                                (rs, rowNum) ->
                                        new SuspensionView(
                                                rs.getLong("id"),
                                                rs.getLong("tenant_id"),
                                                rs.getString("cause"),
                                                rs.getString("reason"),
                                                rs.getTimestamp("disabled_at").toLocalDateTime(),
                                                rs.getTimestamp("restored_at") == null
                                                        ? null
                                                        : rs.getTimestamp("restored_at")
                                                                .toLocalDateTime(),
                                                rs.getString("restore_cause")),
                                tenantId));
    }

    private static String abbreviate(String reason) {
        String text = reason == null ? "" : reason.trim();
        return text.length() > 255 ? text.substring(0, 255) : text;
    }
}
