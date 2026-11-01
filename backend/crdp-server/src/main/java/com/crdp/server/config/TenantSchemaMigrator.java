package com.crdp.server.config;

import com.crdp.framework.crypto.CryptoProperties;
import com.crdp.platform.tenant.TenantResourceRow;
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

    /**
     * 只跑某一个模块的迁移脚本（PLT-MOD-004 开通流程第 2 步）：对该租户库执行该模块迁移目录里的脚本。 与全量迁移共用 flyway_schema_history 与
     * outOfOrder（租户库可能已应用更高版本，如种子 V100），脚本可重入（平台规格 §3）。 校验关闭： history 里还有其他模块（base
     * 等）的已应用版本，它们不在本模块目录里，默认校验会报 missing； 全量迁移（注册与启动时）仍做完整校验。
     */
    public void migrateTenantModule(DataSource dataSource, String migrationLocation) {
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:" + migrationLocation)
                .baselineOnMigrate(false)
                .outOfOrder(true)
                .validateOnMigrate(false)
                .load()
                .migrate();
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
                // 种子脚本固定用 V100（高于普通迁移）；local/dev 的库先跑过种子后，新增的普通迁移
                // 版本号低于已应用的高水位，必须允许乱序补入。生产不含种子、永远顺序执行。
                .outOfOrder(true)
                .load();
    }
}
