package com.crdp.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.job.TenantDirectory;
import com.crdp.framework.job.TenantRef;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

/** E02-T03：按批次升级租户库；任一租户失败即停批（后续租户保持待执行），批次可重试。 */
class TenantUpgradeBatchServiceTest {

    private static final long BATCH_ID = 42L;

    private final JdbcTemplate platform = mock(JdbcTemplate.class);
    private final TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
    private final TenantRoutingDataSource routing = mock(TenantRoutingDataSource.class);
    private final TenantDirectory directory = mock(TenantDirectory.class);

    private final TenantUpgradeBatchService service =
            new TenantUpgradeBatchService(platform, migrator, routing, directory);

    private void stubBatchId() {
        when(platform.update(any(PreparedStatementCreator.class), any(KeyHolder.class)))
                .thenAnswer(
                        invocation -> {
                            KeyHolder holder = invocation.getArgument(1);
                            holder.getKeyList().add(Map.of("GENERATED_KEY", BATCH_ID));
                            return 1;
                        });
    }

    private void tenantsRegistered(long... ids) {
        for (long id : ids) {
            when(routing.hasTenant(id)).thenReturn(true);
        }
        when(directory.enabledTenants())
                .thenReturn(
                        Arrays.stream(ids).mapToObj(id -> new TenantRef(id, "t" + id)).toList());
    }

    @Test
    void migratesEveryTenantOfTheBatchAndMarksSuccess() {
        stubBatchId();
        tenantsRegistered(1L, 2L);

        long batchId = service.startBatch("发布 v0.2 迁移", List.of(1L, 2L));

        assertEquals(BATCH_ID, batchId);
        verify(migrator, times(2)).migrateTenant(routing);
        verify(platform).update(contains("UPDATE plt_upgrade_batch"), eq("SUCCESS"), eq(BATCH_ID));
    }

    @Test
    void stopsTheBatchWhenATenantFails() {
        stubBatchId();
        tenantsRegistered(1L, 2L, 3L);
        doThrow(new RuntimeException("迁移失败：DDL 冲突"))
                .doNothing()
                .when(migrator)
                .migrateTenant(any());

        service.startBatch("发布 v0.2 迁移", List.of(1L, 2L, 3L));

        verify(platform)
                .update(
                        contains("UPDATE plt_upgrade_batch_item"),
                        eq("FAILED"),
                        contains("DDL 冲突"),
                        eq(BATCH_ID),
                        eq(1L));
        // 停批：失败之后的租户不得进入执行
        verify(platform, never())
                .update(
                        contains("UPDATE plt_upgrade_batch_item"),
                        eq("RUNNING"),
                        eq(BATCH_ID),
                        eq(2L));
        verify(platform, never())
                .update(
                        contains("UPDATE plt_upgrade_batch_item"),
                        eq("RUNNING"),
                        eq(BATCH_ID),
                        eq(3L));
        verify(platform).update(contains("UPDATE plt_upgrade_batch"), eq("FAILED"), eq(BATCH_ID));
    }

    @Test
    void retryReRunsOnlyTheFailedAndPendingItems() {
        tenantsRegistered(1L, 2L, 3L);
        when(platform.query(anyString(), any(RowMapper.class), eq(BATCH_ID)))
                .thenReturn(
                        List.of(
                                new TenantUpgradeBatchService.UpgradeItemView(1L, "FAILED", "x"),
                                new TenantUpgradeBatchService.UpgradeItemView(2L, "PENDING", null),
                                new TenantUpgradeBatchService.UpgradeItemView(
                                        3L, "SUCCESS", null)));

        service.retryBatch(BATCH_ID);

        verify(migrator, times(2)).migrateTenant(routing);
        verify(platform).update(contains("UPDATE plt_upgrade_batch"), eq("SUCCESS"), eq(BATCH_ID));
    }

    @Test
    void unregisteredTenantsFailFastWithAClearReason() {
        stubBatchId();
        when(directory.enabledTenants()).thenReturn(List.of());

        service.startBatch("发布 v0.2 迁移", List.of(9L));

        verify(migrator, never()).migrateTenant(any());
        verify(platform)
                .update(
                        contains("UPDATE plt_upgrade_batch_item"),
                        eq("FAILED"),
                        contains("数据源"),
                        eq(BATCH_ID),
                        eq(9L));
        verify(platform).update(contains("UPDATE plt_upgrade_batch"), eq("FAILED"), eq(BATCH_ID));
    }

    @Test
    void getBatchShowsProgressPerTenant() {
        when(platform.queryForObject(contains("description"), eq(String.class), eq(BATCH_ID)))
                .thenReturn("发布 v0.2 迁移");
        when(platform.queryForObject(contains("status"), eq(String.class), eq(BATCH_ID)))
                .thenReturn("FAILED");
        when(platform.query(anyString(), any(RowMapper.class), eq(BATCH_ID)))
                .thenReturn(
                        List.of(
                                new TenantUpgradeBatchService.UpgradeItemView(1L, "FAILED", "迁移失败"),
                                new TenantUpgradeBatchService.UpgradeItemView(
                                        2L, "PENDING", null)));

        TenantUpgradeBatchService.UpgradeBatchView view = service.getBatch(BATCH_ID);

        assertEquals(BATCH_ID, view.batchId());
        assertEquals("发布 v0.2 迁移", view.description());
        assertEquals("FAILED", view.status());
        assertEquals(2, view.items().size());
        assertTrue(view.items().stream().anyMatch(item -> "FAILED".equals(item.status())));
    }
}
