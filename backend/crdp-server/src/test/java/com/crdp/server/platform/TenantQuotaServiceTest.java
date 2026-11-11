package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.audit.AuditEvent;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.security.SessionPrincipal;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.web.ApiException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * E05-T09（PLT-TEN-014）单元测试：配额维护校验（默认不限、上限须为正数）、预警 / 满额状态纯函数、 修改写 TENANT_QUOTA 审计（含前后值）。 真实 MySQL
 * 端到端（含租户侧用量读取与拦截）由 {@code PlatformTenantQuotaMySqlIT} 覆盖。
 */
class TenantQuotaServiceTest {

    private static final long TENANT_ID = 7L;

    private final JdbcTemplate platform = mock(JdbcTemplate.class);
    private final TenantRoutingDataSource routing = mock(TenantRoutingDataSource.class);
    private final AuditLogger audit = mock(AuditLogger.class);

    private TenantQuotaService service;

    @BeforeAll
    static void loginAsPlatformAdmin() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                new SessionPrincipal(
                                        "token",
                                        "PLATFORM",
                                        null,
                                        null,
                                        null,
                                        5L,
                                        "platform.admin",
                                        "平台演示管理员",
                                        List.of("PLATFORM_ADMIN"),
                                        List.of(),
                                        List.of("platform:tenant:edit"),
                                        List.of("base"),
                                        1L,
                                        ScopeSnapshot.allowAll(),
                                        false,
                                        null),
                                null,
                                List.of()));
    }

    @AfterAll
    static void clearSession() {
        SecurityContextHolder.clearContext();
    }

    @BeforeEach
    void setUp() {
        service = new TenantQuotaService(platform, routing, audit);
        lenient().when(routing.hasTenant(anyLong())).thenReturn(false);
        stubTenantRow(null, null);
    }

    /** plt_tenant 一行（queryForMap 形态），配额两列可覆盖。 */
    private void stubTenantRow(Integer userLimit, Long storageLimitBytes) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", TENANT_ID);
        row.put("code", "tquota01");
        row.put("name", "虚构示例医院辛");
        row.put("user_limit", userLimit);
        row.put("storage_limit_bytes", storageLimitBytes);
        lenient().when(platform.queryForMap(anyString(), eq(TENANT_ID))).thenReturn(row);
    }

    // ---------------------------------------------------------------- 校验（纯函数）

    @Test
    void limitsMustBePositiveWhenPresent() {
        assertEquals("用户数上限须为不小于 1 的整数", TenantQuotaService.limitError(-1, 0L));
        assertEquals("用户数上限须为不小于 1 的整数", TenantQuotaService.limitError(0, 0L));
        assertEquals("存储上限须为不小于 1 字节", TenantQuotaService.limitError(10, 0L));
        assertEquals("存储上限须为不小于 1 字节", TenantQuotaService.limitError(10, -5L));
        assertNull(TenantQuotaService.limitError(null, null), "默认不限");
        assertNull(TenantQuotaService.limitError(1, 1L));
    }

    @Test
    void updateRejectsNonPositiveLimitWithoutWriting() {
        ApiException ex =
                assertThrows(
                        ApiException.class,
                        () ->
                                service.update(
                                        TENANT_ID,
                                        new TenantQuotaService.QuotaUpdateRequest(0, null)));
        assertEquals(40001, ex.code());
        verify(platform, never()).update(anyString(), any(Object[].class));
        verify(audit, never()).record(any(AuditEvent.class));
    }

    // ---------------------------------------------------------------- 预警与满额（纯函数）

    @Test
    void warningFlagsFollowEightyPercentThreshold() {
        // 用户数：8/10 恰到 80% 预警（未满额）、10/10 预警且满额；未到 80% 两者皆否
        assertTrue(TenantQuotaService.QuotaUsage.of(10L, 8L).warning());
        assertFalse(TenantQuotaService.QuotaUsage.of(10L, 8L).limitReached());
        assertTrue(TenantQuotaService.QuotaUsage.of(10L, 10L).warning());
        assertTrue(TenantQuotaService.QuotaUsage.of(10L, 10L).limitReached());
        assertFalse(TenantQuotaService.QuotaUsage.of(10L, 7L).warning());
        assertFalse(TenantQuotaService.QuotaUsage.of(10L, 7L).limitReached());
        // 存储字节（大数也安全）：1 GB 上限用到 900 MB 预警（>= 80% 即预警，达到上限即满额）
        assertTrue(TenantQuotaService.QuotaUsage.of(1L << 30, 900L << 20).warning());
        assertFalse(TenantQuotaService.QuotaUsage.of(1L << 30, 900L << 20).limitReached());
        // 不限或用量未知：无预警无满额
        assertFalse(TenantQuotaService.QuotaUsage.of(null, 5L).warning());
        assertFalse(TenantQuotaService.QuotaUsage.of(10L, null).warning());
        assertFalse(TenantQuotaService.QuotaUsage.of(10L, null).limitReached());
    }

    // ---------------------------------------------------------------- 视图

    @Test
    void viewReportsUsageAndFlagsWhenTenantDbReachable() {
        stubTenantRow(10, 1L << 30);
        when(routing.hasTenant(TENANT_ID)).thenReturn(true);
        // 租户上下文内同一 JdbcTemplate 切到租户库（与 TenantDirectoryService.countUsers 同口径）
        lenient()
                .when(platform.queryForObject(contains("FROM sys_user"), eq(Long.class)))
                .thenReturn(9L);
        lenient()
                .when(platform.queryForObject(contains("FROM sys_file"), eq(Long.class)))
                .thenReturn(850L << 20);

        TenantQuotaService.QuotaView view = service.view(TENANT_ID);
        assertEquals(10, view.userLimit());
        assertEquals(1L << 30, view.storageLimitBytes());
        assertEquals(9L, view.enabledUserCount());
        assertEquals(850L << 20, view.storageUsedBytes());
        assertTrue(view.userWarning(), "9/10 >= 80% 预警");
        assertFalse(view.userLimitReached());
        assertTrue(view.storageWarning(), "850MB/1GB >= 80% 预警");
        assertFalse(view.storageLimitReached());
    }

    @Test
    void viewLeavesUsageNullWhenTenantDbUnreachable() {
        stubTenantRow(10, null);
        TenantQuotaService.QuotaView view = service.view(TENANT_ID);
        assertEquals(10, view.userLimit());
        assertNull(view.storageLimitBytes(), "存储默认不限");
        assertNull(view.enabledUserCount(), "租户库不可达时用量置空不阻塞");
        assertNull(view.storageUsedBytes());
        assertFalse(view.userWarning());
        assertFalse(view.userLimitReached());
    }

    @Test
    void viewRejectsUnknownTenant() {
        when(platform.queryForMap(anyString(), eq(TENANT_ID)))
                .thenThrow(new org.springframework.dao.EmptyResultDataAccessException(1));
        ApiException ex = assertThrows(ApiException.class, () -> service.view(TENANT_ID));
        assertEquals(40400, ex.code());
    }

    // ---------------------------------------------------------------- 维护

    @Test
    void updateWritesColumnsAndAuditWithBeforeAfterValues() {
        stubTenantRow(10, null);
        when(routing.hasTenant(TENANT_ID)).thenReturn(true);
        lenient()
                .when(platform.queryForObject(contains("FROM sys_user"), eq(Long.class)))
                .thenReturn(18L);

        TenantQuotaService.QuotaView view =
                service.update(TENANT_ID, new TenantQuotaService.QuotaUpdateRequest(20, 5L << 30));

        verify(platform)
                .update(contains("SET user_limit = ?"), eq(20), eq(5L << 30), eq(TENANT_ID));
        assertEquals(20, view.userLimit());
        assertEquals(5L << 30, view.storageLimitBytes());
        assertTrue(view.userWarning(), "18/20 = 90% 预警");
        assertFalse(view.userLimitReached(), "18 < 20：调上限不回收存量，仍有余量");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(captor.capture());
        AuditEvent event = captor.getValue();
        assertEquals("TENANT_QUOTA", event.eventType());
        assertEquals("plt_tenant:tquota01", event.target());
        assertTrue(event.detail().contains("用户数上限 10 → 20"), "审计记前后值：" + event.detail());
        assertTrue(
                event.detail().contains("存储上限 不限 → 5368709120 字节"), "存储不限 → 具体值：" + event.detail());
    }

    @Test
    void updateToUnlimitedClearsColumns() {
        stubTenantRow(10, 1L << 30);
        service.update(TENANT_ID, new TenantQuotaService.QuotaUpdateRequest(null, null));
        verify(platform)
                .update(
                        contains("SET user_limit = ?"),
                        eq((Object) null),
                        eq((Object) null),
                        eq(TENANT_ID));
    }
}
