package com.fu.server.config;

import com.fu.framework.crypto.CryptoProperties;
import com.fu.platform.tenant.TenantResourceRow;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TenantSchemaMigrator {

    private final Environment environment;
    private final CryptoProperties cryptoProperties;

    public TenantSchemaMigrator(Environment environment, CryptoProperties cryptoProperties) {
        this.environment = environment;
        this.cryptoProperties = cryptoProperties;
    }

    public void migratePlatform(DataSource dataSource) {
        List<String> locations = new ArrayList<>();
        locations.add("classpath:db/platform");
        if (seedEnabled()) {
            locations.add("classpath:db/platform/seed");
        }
        flyway(dataSource, locations).migrate();
    }

    public void migrateTenant(DataSource dataSource) {
        List<String> locations = new ArrayList<>();
        locations.add("classpath:db/tenant/base");
        locations.add("classpath:db/tenant/followup");
        if (seedEnabled()) {
            locations.add("classpath:db/tenant/seed");
        }
        flyway(dataSource, locations).migrate();
    }

    public List<TenantResourceRow> loadTenants(DataSource platform) {
        return new JdbcTemplate(platform)
                .query(
                        """
                        SELECT t.id, t.code, t.status, r.db_name, r.jdbc_url, r.db_username, r.password_cipher
                        FROM plt_tenant t
                        JOIN plt_tenant_resource r ON r.tenant_id = t.id
                        WHERE t.status IN ('ENABLED', 'TRIAL')
                        ORDER BY t.id
                        """,
                        (rs, rowNum) ->
                                new TenantResourceRow(
                                        rs.getLong("id"),
                                        rs.getString("code"),
                                        rs.getString("status"),
                                        rs.getString("db_name"),
                                        rs.getString("jdbc_url"),
                                        rs.getString("db_username"),
                                        DatasourceSecrets.resolve(
                                                rs.getString("password_cipher"),
                                                cryptoProperties.getMode())));
    }

    private boolean seedEnabled() {
        return Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> "local".equals(profile) || "dev".equals(profile));
    }

    private static Flyway flyway(DataSource dataSource, List<String> locations) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations(locations.toArray(String[]::new))
                .baselineOnMigrate(false)
                .load();
    }
}
