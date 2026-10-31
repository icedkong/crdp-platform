package com.crdp.server.config;

import com.crdp.framework.job.TenantDirectory;
import com.crdp.framework.job.TenantRef;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

/**
 * 租户库升级批次（PLT-MOD-007 / E02-T03）：发版后按租户分批执行迁移，任一租户失败即停批——失败租户之后 的项保持 PENDING，批次标 FAILED；重试只重跑
 * FAILED 与 PENDING 的租户。批次与明细记录在平台库 plt_upgrade_batch / plt_upgrade_batch_item，供平台端展示进度与失败原因（页面在
 * E05）。
 */
@Component
public class TenantUpgradeBatchService {

    private static final int ERROR_MAX_LENGTH = 1000;

    /** 单个租户在某批次中的执行情况。 */
    public record UpgradeItemView(long tenantId, String status, String error) {}

    /** 批次整体视图：状态与逐租户进度。 */
    public record UpgradeBatchView(
            long batchId, String description, String status, List<UpgradeItemView> items) {}

    private final JdbcTemplate platform;
    private final TenantSchemaMigrator migrator;
    private final TenantRoutingDataSource routing;
    private final TenantDirectory directory;

    public TenantUpgradeBatchService(
            JdbcTemplate platform,
            TenantSchemaMigrator migrator,
            TenantRoutingDataSource routing,
            TenantDirectory directory) {
        this.platform = platform;
        this.migrator = migrator;
        this.routing = routing;
        this.directory = directory;
    }

    /** 创建批次并立即按入参顺序执行；返回批次 id。 */
    public long startBatch(String description, List<Long> tenantIds) {
        long batchId =
                TenantContext.callAsPlatform(
                        () ->
                                insertReturningId(
                                        "INSERT INTO plt_upgrade_batch (description, status)"
                                                + " VALUES (?, 'RUNNING')",
                                        description));
        for (long tenantId : tenantIds) {
            TenantContext.runAsPlatform(
                    () ->
                            platform.update(
                                    "INSERT INTO plt_upgrade_batch_item (batch_id, tenant_id, status)"
                                            + " VALUES (?, ?, 'PENDING')",
                                    batchId,
                                    tenantId));
        }
        boolean stopped = runItems(batchId, tenantIds);
        finishBatch(batchId, stopped ? "FAILED" : "SUCCESS");
        return batchId;
    }

    /** 重试批次：只重跑 FAILED 与 PENDING 的租户。 */
    public void retryBatch(long batchId) {
        List<Long> todo =
                loadItems(batchId).stream()
                        .filter(
                                item ->
                                        "FAILED".equals(item.status())
                                                || "PENDING".equals(item.status()))
                        .map(UpgradeItemView::tenantId)
                        .toList();
        TenantContext.runAsPlatform(
                () ->
                        platform.update(
                                "UPDATE plt_upgrade_batch SET status = 'RUNNING' WHERE id = ?",
                                batchId));
        boolean stopped = runItems(batchId, todo);
        finishBatch(batchId, stopped ? "FAILED" : "SUCCESS");
    }

    /** 批次进度视图。 */
    public UpgradeBatchView getBatch(long batchId) {
        return TenantContext.callAsPlatform(
                () -> {
                    String description =
                            platform.queryForObject(
                                    "SELECT description FROM plt_upgrade_batch WHERE id = ?",
                                    String.class,
                                    batchId);
                    String status =
                            platform.queryForObject(
                                    "SELECT status FROM plt_upgrade_batch WHERE id = ?",
                                    String.class,
                                    batchId);
                    return new UpgradeBatchView(batchId, description, status, loadItems(batchId));
                });
    }

    /** 逐租户执行；任一失败返回 true（已停批，剩余租户保持 PENDING）。 */
    private boolean runItems(long batchId, List<Long> tenantIds) {
        Map<Long, TenantRef> knownTenants =
                directory.enabledTenants().stream()
                        .collect(Collectors.toMap(TenantRef::tenantId, Function.identity()));
        for (long tenantId : tenantIds) {
            String error = runOne(batchId, tenantId, knownTenants);
            if (error != null) {
                return true;
            }
        }
        return false;
    }

    private String runOne(long batchId, long tenantId, Map<Long, TenantRef> knownTenants) {
        TenantContext.runAsPlatform(
                () ->
                        platform.update(
                                "UPDATE plt_upgrade_batch_item"
                                        + " SET status = ?, started_at = CURRENT_TIMESTAMP(3)"
                                        + " WHERE batch_id = ? AND tenant_id = ?",
                                "RUNNING",
                                batchId,
                                tenantId));
        TenantRef tenant = knownTenants.get(tenantId);
        String error = null;
        if (tenant == null || !routing.hasTenant(tenantId)) {
            error = "租户不在启用清单或数据源未注册: " + tenantId;
        } else {
            try {
                TenantContext.runAsTenant(
                        tenant.tenantId(),
                        tenant.tenantCode(),
                        () -> migrator.migrateTenant(routing));
            } catch (RuntimeException ex) {
                error = abbreviate(ex);
            }
        }
        final String reportedError = error;
        String finalStatus = reportedError == null ? "SUCCESS" : "FAILED";
        TenantContext.runAsPlatform(
                () ->
                        platform.update(
                                "UPDATE plt_upgrade_batch_item"
                                        + " SET status = ?, error_text = ?, finished_at = CURRENT_TIMESTAMP(3)"
                                        + " WHERE batch_id = ? AND tenant_id = ?",
                                finalStatus,
                                reportedError,
                                batchId,
                                tenantId));
        return reportedError;
    }

    private void finishBatch(long batchId, String status) {
        TenantContext.runAsPlatform(
                () ->
                        platform.update(
                                "UPDATE plt_upgrade_batch"
                                        + " SET status = ?, finished_at = CURRENT_TIMESTAMP(3) WHERE id = ?",
                                status,
                                batchId));
    }

    private List<UpgradeItemView> loadItems(long batchId) {
        return TenantContext.callAsPlatform(
                () ->
                        platform.query(
                                "SELECT tenant_id, status, error_text FROM plt_upgrade_batch_item"
                                        + " WHERE batch_id = ? ORDER BY id",
                                (rs, rowNum) ->
                                        new UpgradeItemView(
                                                rs.getLong("tenant_id"),
                                                rs.getString("status"),
                                                rs.getString("error_text")),
                                batchId));
    }

    private long insertReturningId(String sql, Object... args) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        platform.update(
                (PreparedStatementCreator)
                        connection -> {
                            PreparedStatement statement =
                                    connection.prepareStatement(
                                            sql, Statement.RETURN_GENERATED_KEYS);
                            for (int i = 0; i < args.length; i++) {
                                statement.setObject(i + 1, args[i]);
                            }
                            return statement;
                        },
                keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("未取到 plt_upgrade_batch 的自增主键");
        }
        return key.longValue();
    }

    private static String abbreviate(RuntimeException ex) {
        String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return message.length() > ERROR_MAX_LENGTH
                ? message.substring(0, ERROR_MAX_LENGTH)
                : message;
    }
}
