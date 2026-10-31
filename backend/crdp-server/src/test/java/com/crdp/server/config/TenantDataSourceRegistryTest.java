package com.crdp.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.framework.tenant.UnknownTenantDataSourceException;
import com.crdp.platform.tenant.TenantResourceRow;
import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** E02-T02：新租户入库后运行期建池迁移注册，无需重启即可路由到该租户库。 */
class TenantDataSourceRegistryTest {

    private final TenantRoutingDataSource routing = new TenantRoutingDataSource();
    private final TenantSchemaMigrator migrator = mock(TenantSchemaMigrator.class);
    private final DataSourceFactory factory = mock(DataSourceFactory.class);

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static TenantResourceRow tenant(long id, String code, String jdbcUrl) {
        return new TenantResourceRow(
                id, code, "ENABLED", "t_" + code, jdbcUrl, "crdp_app", "secret");
    }

    private TenantDataSourceRegistry registry() {
        return new TenantDataSourceRegistry(routing, migrator, factory);
    }

    @Test
    void registersANewTenantAtRuntimeSoItIsRoutableWithoutRestart() {
        HikariDataSource pool = mock(HikariDataSource.class);
        when(factory.hikari(anyString(), anyString(), anyString(), anyString())).thenReturn(pool);

        registry().register(tenant(7L, "demoz", "jdbc:mysql://127.0.0.1:13306/t_demoz"));

        assertTrue(routing.hasTenant(7L), "注册后立即可路由，无需重启");
        TenantContext.useTenant(7L, "demoz");
        assertSame(pool, routing.current());
        verify(migrator).migrateTenant(pool);
    }

    @Test
    void registerIsIdempotentWhileTheTenantIsRegistered() {
        AtomicInteger builds = new AtomicInteger();
        HikariDataSource first = mock(HikariDataSource.class);
        when(factory.hikari(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            builds.incrementAndGet();
                            return first;
                        });

        TenantDataSourceRegistry registry = registry();
        registry.register(tenant(7L, "demoz", "jdbc:mysql://127.0.0.1:13306/t_demoz"));
        registry.register(tenant(7L, "demoz", "jdbc:mysql://10.0.0.9:3306/t_demoz"));

        assertEquals(1, builds.get(), "已注册租户重复 register 不应再建池");
        verify(first, never()).close();
        TenantContext.useTenant(7L, "demoz");
        assertSame(first, routing.current());
    }

    @Test
    void unregisterThenRegisterSwapsThePool() {
        HikariDataSource first = mock(HikariDataSource.class);
        HikariDataSource second = mock(HikariDataSource.class);
        when(factory.hikari(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(first, second);

        TenantDataSourceRegistry registry = registry();
        registry.register(tenant(7L, "demoz", "jdbc:mysql://127.0.0.1:13306/t_demoz"));
        registry.unregister(7L);
        registry.register(tenant(7L, "demoz", "jdbc:mysql://10.0.0.9:3306/t_demoz"));

        verify(first).close();
        TenantContext.useTenant(7L, "demoz");
        assertSame(second, routing.current());
    }

    @Test
    void unregisterRemovesTheTenantAndClosesThePool() {
        HikariDataSource pool = mock(HikariDataSource.class);
        when(factory.hikari(anyString(), anyString(), anyString(), anyString())).thenReturn(pool);

        TenantDataSourceRegistry registry = registry();
        registry.register(tenant(7L, "demoz", "jdbc:mysql://127.0.0.1:13306/t_demoz"));
        registry.unregister(7L);

        assertFalse(routing.hasTenant(7L));
        verify(pool).close();
        TenantContext.useTenant(7L, "demoz");
        assertThrows(UnknownTenantDataSourceException.class, routing::current);
    }

    @Test
    void failedMigrationClosesThePoolAndRegistersNothing() {
        HikariDataSource pool = mock(HikariDataSource.class);
        when(factory.hikari(anyString(), anyString(), anyString(), anyString())).thenReturn(pool);
        doThrow(new IllegalStateException("flyway 失败")).when(migrator).migrateTenant(any());

        assertThrows(
                IllegalStateException.class,
                () ->
                        registry()
                                .register(
                                        tenant(
                                                7L,
                                                "demoz",
                                                "jdbc:mysql://127.0.0.1:13306/t_demoz")));

        assertFalse(routing.hasTenant(7L), "迁移失败不得注册半成品池");
        verify(pool).close();
    }

    @Test
    void concurrentRegistrationOfTheSameTenantBuildsExactlyOnePool() throws Exception {
        AtomicInteger builds = new AtomicInteger();
        HikariDataSource pool = mock(HikariDataSource.class);
        when(factory.hikari(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(
                        invocation -> {
                            builds.incrementAndGet();
                            return pool;
                        });

        TenantDataSourceRegistry registry = registry();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);
        for (int i = 0; i < 8; i++) {
            executor.submit(
                    () -> {
                        try {
                            start.await();
                            registry.register(
                                    tenant(7L, "demoz", "jdbc:mysql://127.0.0.1:13306/t_demoz"));
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
        }
        start.countDown();
        try {
            assertTrue(done.await(5, TimeUnit.SECONDS), "并发注册应全部完成");
        } finally {
            executor.shutdownNow();
        }

        assertEquals(1, builds.get(), "并发注册同一租户只应建一个池");
        verify(pool, never()).close();
    }
}
