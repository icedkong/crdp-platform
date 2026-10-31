package com.crdp.server.config;

import com.crdp.framework.tenant.TenantRoutingDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** 探针只打平台库。路由数据源在没有租户上下文时会拒绝连接，不能拿来做健康检查。 */
@Component("platformDb")
public class PlatformDatabaseHealthIndicator implements HealthIndicator {

    private final TenantRoutingDataSource routing;

    public PlatformDatabaseHealthIndicator(TenantRoutingDataSource routing) {
        this.routing = routing;
    }

    @Override
    public Health health() {
        try (Connection connection = routing.platform().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            return Health.up().build();
        } catch (SQLException ex) {
            return Health.down().withDetail("error", "platform database unavailable").build();
        }
    }
}
