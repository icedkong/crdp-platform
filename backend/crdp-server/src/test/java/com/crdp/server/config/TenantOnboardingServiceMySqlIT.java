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
                CODE, "虚构示例医院丙", "admin", "丙院管理员", INITIAL_PASSWORD, null, null, null);
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
                                                .DataSourceTransactionManager(routing)),
                                event -> {}),
                        null,
                        null,
                        new com.crdp.server.platform.RoleTemplateService(
                                new JdbcTemplate(routing))),
                // E06-T12：local/dev 演示占位替换器，本 IT 手工装配传空提供者
                EmptyObjectProvider.get(),
                EmptyObjectProvider.get(),
                // E12-T05：本 IT 不选配置模板（步骤记 SKIPPED），模板头校验与应用服务不会触碰，传 null
                null,
                null);
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
        assertEquals(6, result.steps().size());
        assertTrue(
                result.steps().stream()
                        .allMatch(
                                s ->
                                        "SUCCESS".equals(s.status())
                                                || ("config-template".equals(s.step())
                                                        && "SKIPPED".equals(s.status()))),
                "未选模板时 config-template 记 SKIPPED，其余 SUCCESS：" + result.steps());
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
                // E08-T03 增加 V18（审计表按月分区 + 归档登记 aud_archive）。
                // E08-T04 增加 V19（审计导出作业表 job_export / job_export_approval + 审计中心菜单授权）。
                // E06-T08 增加 V20（用户导入按钮授权 tenant:user:import）。
                // E09-T02 增加 V21（站内信收件箱 ntf_message，followup 目录）。
                // E09-T04 增加 V22（通知规则 ntf_rule / 发送台账 ntf_send_log / 病区默认负责人，followup 目录）。
                // E09-T07 增加 V23（个人通知偏好 ntf_user_pref / 租户发送设置 ntf_setting，followup 目录）。
                // E09-T05 增加 V24（ntf_send_log 汇总作业索引，followup 目录）。
                // E10-T01 增加 V25（文件上传台账 sys_file + 上传按钮授权，followup 目录）。
                // E10-T02 增加 V26（sys_file 迁桶后清理 body 列，followup 目录）。
                // E10-T03 增加 V27（sys_file 缩略图键 + pages 口径收敛，followup 目录）。
                // E11-T01 增加 V28（字段元数据 fe_form / fe_field / fe_option_set / fe_option，followup 目录）。
                // E11-T02 增加 V29（模板版本快照 fe_form_version / fe_form_version_field 与 JSON 记录
                // fe_record，followup 目录）。
                // E11-T04 增加 V30（跨字段校验规则 fe_rule，followup 目录）。
                // E11-T03 增加 V31（条件显示 / 条件必填演示字段，followup 目录）。
                // E11-T06 增加 V32（字段级变更历史 fe_field_history，followup 目录）。
                // E11-T05 增加 V33（派生字段演示：体重 / 出院日期 / BMI，followup 目录）。
                // E11-T08 增加 V34（cfg_dict_item.aliases + cfg_dict_log，base 目录）与 V35
                // （字段字典变更记录 fe_field_dict_log，followup 目录）。
                // E11-T09 增加 V36（手术年龄派生演示字段，followup 目录）。
                // E13-T01 增加 V37（患者表 pt_patient + 新建患者菜单授权，followup 目录）。
                // E23-T01 增加 V38（医院 A 字段字典，followup 目录）。
                // E13-T03 增加 V39（pt_contact / pt_status_history / pt_patient_summary +
                // 编辑患者资料按钮授权，followup 目录）。
                // E13-T04 增加 V40（pt_patient_summary 存量补齐，followup 目录）。
                // E13-T05 增加 V41（患者详情菜单授权，followup 目录）。
                // E13-T06 增加 V42（个人保存的筛选 fu_saved_filter，followup 目录）。
                // E13-T08 增加 V43（病区字典补列、转病区历史 pt_ward_history +
                // 病区管理 / 转病区菜单授权，followup 目录）。
                // E13-T09 增加 V44（审批患者导出按钮授权，followup 目录）与
                // V45（job_export.scope_json 冻结导出范围，base 目录）；
                // E14-T01 增加 V46（fu_plan / fu_plan_node / fu_enrollment 与计划菜单授权，followup 目录）；
                // E25-T01 增加 V47（录入随访记录按钮授权 followup V47 与种子 V118）；
                // E14-T04 增加 V48（任务实例表 fu_task）、E14-T08 增加 V49（计划变更记录
                // fu_plan_log）、E14-T05 增加 V50（fu_plan.auto_enroll）、E14-T06 增加 V51（fu_task 终止三列）、
                // E13-T07 增加 V52（批量指定责任医生按钮授权，种子 V119）、E15-T01 增加 V53（fu_task 完成标记三列）、
                // E15-T02 增加 V54/V55（fu_task.record_id、随访录入菜单授权）、E15-T04 增加 V56（fu_contact_log /
                // fu_contact_audio 沟通记录与录音）、E15-T05 增加 V57（失访候选跟进）、E15 增加 V58（fu_record）、
                // E09-T06 增加 V59（sys_webhook）、E13-T02 增加 V60（基线分节锁）、
                // E23-T02 增加 V61（医院 A 预置计划 fu_plan / fu_plan_node / fu_plan_log）、
                // E16-T01 增加 V62（报告单配置 rpt_type / rpt_name / rpt_indicator_tpl /
                // rpt_indicator_item）、E16-T02 增加 V63（报告单运行时 rpt_report /
                // rpt_report_link / rpt_report_file / rpt_indicator_value）、E23-T03 增加 V64
                // （字段映射 rpt_mapping / rpt_mapping_item 与心脏超声预置）、E16-T09 增加 V65
                // （rpt_report 软删除列 + 删除按钮授权）、E16-T15 增加 V66
                // （rpt_report.location_adjusted 退回标记）、E17-T01 增加 V67
                // （对比标红规则 fu_compare_rule 与对比规则菜单授予）、E18-T02 增加 V68
                // （导入作业与暂存行 imp_job / imp_row）、E17-T04 增加 V69
                // （fu_record.highlight 对比标红命中列）、E17-T05 增加 V70（医院 A Q41
                // 默认对比规则预置 fu_compare_rule 15 条）、E18-T03 增加 V71（导入校验问题 imp_issue）、
                // E16-T08 增加 V72（rpt_report.contact_log_id 通话记录关联）；E18-T05 增加 V73（imp_batch /
                // imp_batch_write、fu_record 排期锚点列）。
                // 此处随迁移清单维护
                List.of(
                        "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14",
                        "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26",
                        "27", "28", "29", "30", "31", "32", "33", "34", "35", "36", "37", "38",
                        "39", "40", "41", "42", "43", "44", "45", "46", "47", "48", "49", "50",
                        "51", "52", "53", "54", "55", "56", "57", "58", "59", "60", "61", "62",
                        "63", "64", "65", "66", "67", "68", "69", "70", "71", "72", "73"),
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
                6,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_provision_step WHERE tenant_id ="
                                + " (SELECT id FROM plt_tenant WHERE code = ?)",
                        Integer.class,
                        CODE));
    }
}
