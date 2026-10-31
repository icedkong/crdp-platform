package com.fu.server.config;

import com.fu.framework.tenant.TenantRoutingDataSource;
import com.fu.platform.tenant.TenantResourceRow;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    public TenantRoutingDataSource tenantRoutingDataSource(
            PlatformDataSourceProperties properties, TenantSchemaMigrator migrator) {
        HikariDataSource platform =
                DataSourceFactory.hikari(
                        "fu-platform",
                        properties.getUrl(),
                        properties.getUsername(),
                        properties.getPassword());
        migrator.migratePlatform(platform);
        TenantRoutingDataSource routing = new TenantRoutingDataSource();
        routing.registerPlatform(platform);
        for (TenantResourceRow tenant : migrator.loadTenants(platform)) {
            HikariDataSource pool =
                    DataSourceFactory.hikari(
                            "fu-" + tenant.code(),
                            tenant.jdbcUrl(),
                            tenant.username(),
                            tenant.passwordCipher());
            migrator.migrateTenant(pool);
            routing.registerTenant(tenant.tenantId(), pool);
        }
        return routing;
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
