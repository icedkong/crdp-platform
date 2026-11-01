package com.crdp.server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.crypto.CryptoProperties;
import com.crdp.framework.tenant.TenantRoutingDataSource;
import com.crdp.platform.tenant.TenantResourceRow;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * E02-T04 对真 MySQL 的端到端验证（验收原文：中途失败重跑可续，不重复建库）。场景按序： 迁移步骤失败 → 从失败步骤重跑成功 → 再跑一次不重复建库 / 不重复建管理员。
 * CRDP_IT_MYSQL_PORT=13306 时运行；自 E02-T09 起 CI 注入该端口（docker compose 起 MySQL）；自建自清。
 */
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class TenantOnboardingServiceMySqlIT {

    private static final String CODE = "democ";
    private static final String DB = "t_democ";
    private static final String INITIAL_PASSWORD = "Init@2026#";

    private static JdbcTemplate root;
    private static JdbcTemplate platform;
    private static TenantRoutingDataSource routing;
    private static TenantDataSourceRegistry registry;
    private static ProvisioningProperties properties;
    private static PasswordEncoder encoder;

    @BeforeAll
    static void setUp() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        String base = "jdbc:mysql://127.0.0.1:" + port;
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        root =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                base + "/" + params, "root", "DevOnly#root2026"));
        platform =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));

        DataSource platformPool =
                new DataSourceFactory()
                        .hikari(
                                "it-platform",
                                base + "/crdp_platform" + params,
                                "crdp_app",
                                "DevOnly#app2026");
        routing = new TenantRoutingDataSource();
        routing.registerPlatform(platformPool);

        TenantSchemaMigrator migrator =
                new TenantSchemaMigrator(new MockEnvironment(), new CryptoProperties());
        registry = new TenantDataSourceRegistry(routing, migrator, new DataSourceFactory());

        properties = new ProvisioningProperties();
        properties.setJdbcUrl(base + "/" + params);
        properties.setUsername("root");
        properties.setPassword("DevOnly#root2026");
        properties.setTenantJdbcUrlTemplate(base + "/{db}" + params);

        encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @AfterAll
    static void tearDown() {
        if (root != null) {
            root.execute("DROP DATABASE IF EXISTS " + DB);
            root.execute("DROP USER IF EXISTS `" + DB + "`@'%'");
        }
        if (platform != null) {
            List<Long> ids =
                    platform.queryForList(
                            "SELECT id FROM plt_tenant WHERE code = ?", Long.class, CODE);
            for (Long id : ids) {
                platform.update("DELETE FROM plt_provision_step WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_resource WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant WHERE id = ?", id);
            }
        }
        if (routing != null) {
            routing.destroy();
        }
    }

    private static TenantOnboardingService.OnboardingRequest request() {
        return new TenantOnboardingService.OnboardingRequest(
                CODE, "虚构示例医院丙", "admin", "丙院管理员", INITIAL_PASSWORD, null, null);
    }

    private static TenantOnboardingService service(TenantDataSourceRegistry registryToUse) {
        // 平台操作走路由数据源（平台上下文），租户操作在租户上下文内路由到该院池
        // 模块开通（E05-T05）只用到平台库 / 租户库与审计；会话与权限版本重算在新建租户路径不触发，
        // 这里传 null（该 IT 无 Redis，bump 不会被调用）。
        return new TenantOnboardingService(
                new JdbcTemplate(routing),
                registryToUse,
                new ProvisioningDatabase(properties),
                properties,
                encoder,
                new com.crdp.server.platform.TenantModuleService(
                        new JdbcTemplate(routing),
                        new com.crdp.platform.module.ModuleRegistry(),
                        routing,
                        new TenantSchemaMigrator(new MockEnvironment(), new CryptoProperties()),
                        new com.crdp.framework.audit.JdbcAuditLogger(
                                new JdbcTemplate(routing),
                                new com.crdp.framework.audit.AuditChainWriter(
                                        new JdbcTemplate(routing),
                                        new com.crdp.framework.crypto.Sha256DigestService(),
                                        new org.springframework.jdbc.datasource
                                                .DataSourceTransactionManager(routing))),
                        null,
                        null,
                        new com.crdp.server.platform.RoleTemplateService(
                                new JdbcTemplate(routing))));
    }

    @Test
    void midFailureResumesAndRerunDoesNotRecreate() {
        TenantDataSourceRegistry failingRegistry =
                new TenantDataSourceRegistry(
                        routing,
                        new TenantSchemaMigrator(new MockEnvironment(), new CryptoProperties()),
                        new DataSourceFactory()) {
                    @Override
                    public synchronized void register(TenantResourceRow tenant) {
                        throw new RuntimeException("模拟迁移失败");
                    }
                };

        // 1. 中途失败：migrate-register 步骤抛错，编排中止
        assertThrows(RuntimeException.class, () -> service(failingRegistry).onboard(request()));
        assertEquals(
                "PROVISIONING",
                platform.queryForObject(
                        "SELECT status FROM plt_tenant WHERE code = ?", String.class, CODE),
                "失败的开通不得置为 TRIAL");
        assertEquals(
                "FAILED",
                platform.queryForObject(
                        "SELECT status FROM plt_provision_step WHERE tenant_id ="
                                + " (SELECT id FROM plt_tenant WHERE code = ?) AND step = 'migrate-register'",
                        String.class,
                        CODE));
        assertEquals(
                "SUCCESS",
                platform.queryForObject(
                        "SELECT status FROM plt_provision_step WHERE tenant_id ="
                                + " (SELECT id FROM plt_tenant WHERE code = ?) AND step = 'database'",
                        String.class,
                        CODE),
                "失败之前的步骤保持成功，重跑才可续");

        // 2. 重跑成功：从失败步骤继续，最终 TRIAL
        TenantOnboardingService.OnboardingResult result = service(registry).onboard(request());
        assertEquals(5, result.steps().size());
        assertTrue(
                result.steps().stream().allMatch(s -> "SUCCESS".equals(s.status())),
                () -> result.steps().toString());
        assertEquals(
                "TRIAL",
                platform.queryForObject(
                        "SELECT status FROM plt_tenant WHERE code = ?", String.class, CODE));
        assertEquals(
                1,
                root.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                        Integer.class,
                        DB),
                "租户库已建");
        assertEquals(
                // E02-T04 时是 1..4；E03-T05 增加租户库 V5（重加密进度表）；
                // E04-T04 增加 V6（账号手机号 + 双因素角色配置）；
                // E04-T05 增加 V7（密码有效期列 + 历史密码表 + 解锁按钮授权）；
                // E04-T06 增加 V8（单端登录参数 + 在线会话按钮授权）；
                // E06-T02 增加 V9（科室类型 / 负责人 / HIS 编码 / 排序列 + 组织维护按钮授权）；
                // E06-T11 增加 V10（租户个性化 cfg_brand + 机构设置菜单授权）；
                // E06-T05 增加 V11（角色类型 / 来源模块 / 说明 + 自定义科室范围表 + 角色维护按钮授权）；
                // E06-T07 增加 V12（工号 / 主科室列 + 用户维护按钮授权）；
                // E06-T10 增加 V13（租户字典 / 参数覆盖表 + 参数、字典按钮授权）；
                // E06-T03 增加 V14（sys_outbox 事务外发箱）；
                // E08-T01 增加 V15（审计规格字段：aud_log / aud_login_log 扩展列 + aud_data_access_log）；
                // E07-T06 增加 V16（角色字段策略覆盖表 sys_role_field_policy）；
                // E08-T02 增加 V17（审计哈希链链头表 + 只读触发器）。
                // 此处随迁移清单维护
                List.of(
                        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14",
                        "15", "16", "17"),
                root.queryForList(
                        "SELECT version FROM "
                                + DB
                                + ".flyway_schema_history ORDER BY installed_rank",
                        String.class),
                "租户库迁移完整（无种子，集成测试无 profile）");
        assertTrue(
                encoder.matches(
                        INITIAL_PASSWORD,
                        root.queryForObject(
                                "SELECT password_hash FROM "
                                        + DB
                                        + ".sys_user WHERE username = 'admin'",
                                String.class)),
                "管理员口令为 Argon2id 哈希");
        assertEquals(
                1,
                root.queryForObject(
                        "SELECT COUNT(*) FROM " + DB + ".sys_role WHERE code = 'TENANT_ADMIN'",
                        Integer.class));

        // 3. 再跑一次：幂等，不重复建库、不重复建管理员
        service(registry).onboard(request());
        assertEquals(
                1,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_tenant_resource WHERE tenant_id ="
                                + " (SELECT id FROM plt_tenant WHERE code = ?)",
                        Integer.class,
                        CODE),
                "资源行只有一条，口令未重新生成");
        assertEquals(
                1,
                root.queryForObject(
                        "SELECT COUNT(*) FROM " + DB + ".sys_user WHERE username = 'admin'",
                        Integer.class),
                "管理员不重复创建");
        assertEquals(
                5,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_provision_step WHERE tenant_id ="
                                + " (SELECT id FROM plt_tenant WHERE code = ?)",
                        Integer.class,
                        CODE));
    }
}
