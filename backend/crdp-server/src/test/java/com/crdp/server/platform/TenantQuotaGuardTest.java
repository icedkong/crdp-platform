package com.crdp.server.platform;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * E05-T09（PLT-ORG-010）单元测试：达到上限禁止新建 / 启用用户（40901）、不限或读不到配额放行、 平台上下文不拦截。端到端见 {@code
 * PlatformTenantQuotaMySqlIT}。
 */
class TenantQuotaGuardTest {

    private final JdbcTemplate platformPool = mock(JdbcTemplate.class);
    private final JdbcTemplate tenantDb = mock(JdbcTemplate.class);

    private TenantQuotaGuard guard;

    @BeforeEach
    void setUp() {
        // guard 对外构造自建平台连接（routing.platform()）；测试用包内构造直接注入 mock
        guard = new TenantQuotaGuard(platformPool);
        TenantContext.useTenant(9L, "tquota9");
    }

    @AfterEach
    void cleanUp() {
        TenantContext.clear();
    }

    @Test
    void blocksWhenEnabledUsersReachLimit() {
        when(platformPool.queryForObject(anyString(), eq(Integer.class), eq(9L))).thenReturn(5);
        when(tenantDb.queryForObject(anyString(), eq(Long.class))).thenReturn(5L);
        ApiException ex =
                assertThrows(
                        ApiException.class, () -> guard.assertUserQuotaAvailable(tenantDb, "新建用户"));
        assertEquals(409, ex.httpStatus());
        assertEquals(40901, ex.code());
        assertEquals("已达用户数上限（5），无法新建用户，请联系平台调整配额", ex.getMessage());
    }

    @Test
    void allowsWhenBelowLimitOrUnlimited() {
        when(platformPool.queryForObject(anyString(), eq(Integer.class), eq(9L))).thenReturn(5);
        when(tenantDb.queryForObject(anyString(), eq(Long.class))).thenReturn(4L);
        assertDoesNotThrow(() -> guard.assertUserQuotaAvailable(tenantDb, "新建用户"));

        when(platformPool.queryForObject(anyString(), eq(Integer.class), eq(9L))).thenReturn(null);
        assertDoesNotThrow(() -> guard.assertUserQuotaAvailable(tenantDb, "启用用户"));
    }

    @Test
    void platformContextOrReadFailureMeansUnlimited() {
        TenantContext.usePlatform();
        assertNull(guard.currentUserLimit());
        assertNull(guard.currentStorageLimitBytes());
        assertDoesNotThrow(() -> guard.assertUserQuotaAvailable(tenantDb, "导入用户"));

        TenantContext.useTenant(9L, "tquota9");
        when(platformPool.queryForObject(anyString(), eq(Integer.class), eq(9L)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("down"));
        assertNull(guard.currentUserLimit(), "平台库不可达时按不限处理");
        assertDoesNotThrow(() -> guard.assertUserQuotaAvailable(tenantDb, "新建用户"));
    }
}
