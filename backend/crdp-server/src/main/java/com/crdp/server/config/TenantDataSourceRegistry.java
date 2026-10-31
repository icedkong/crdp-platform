package com.crdp.server.config;

import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.platform.tenant.TenantResourceRow;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.stereotype.Component;

/**
 * 运行期的租户数据源注册表（PLT-TEN-005：应用热加载，无需重启）。新租户入库后调用 {@link #register}
 * 建池、跑租户库迁移、注册进路由数据源，随后请求立即可路由到该租户库。已注册的租户重复 register 直接跳过（幂等，防止并发建出多个池）；租户停用或连接信息变更走 unregister
 * 后重新 register。 开通编排（建库、写资源表）是 E02-T04。注册是低频操作，这里用全局锁换取简单正确。
 */
@Component
public class TenantDataSourceRegistry {

    private final TenantRoutingDataSource routing;
    private final TenantSchemaMigrator migrator;
    private final DataSourceFactory factory;

    public TenantDataSourceRegistry(
            TenantRoutingDataSource routing,
            TenantSchemaMigrator migrator,
            DataSourceFactory factory) {
        this.routing = routing;
        this.migrator = migrator;
        this.factory = factory;
    }

    public synchronized void register(TenantResourceRow tenant) {
        if (routing.hasTenant(tenant.tenantId())) {
            return;
        }
        HikariDataSource pool =
                factory.hikari(
                        "crdp-" + tenant.code(),
                        tenant.jdbcUrl(),
                        tenant.username(),
                        tenant.passwordCipher());
        try {
            // 先迁移后注册：不存在"可路由但未迁移"的窗口
            migrator.migrateTenant(pool);
            routing.registerTenant(tenant.tenantId(), pool);
        } catch (RuntimeException ex) {
            pool.close();
            throw ex;
        }
    }

    public synchronized void unregister(long tenantId) {
        routing.unregisterTenant(tenantId);
    }
}
