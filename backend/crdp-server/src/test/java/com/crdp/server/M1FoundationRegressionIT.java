package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.audit.AuditChainVerifier;
import com.crdp.framework.tenant.TenantContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * E06-T12 / M1 底座集成回归。M1 完成标准（tech-plan §16）共五项，逐项各一个用例，按真实使用动线串起来：
 *
 * <ol>
 *   <li><b>能开通租户</b>：平台端登录 → 开通向导提交 → 五步 SUCCESS、租户 TRIAL、库已建、首个管理员待改密；
 *   <li><b>医院端与平台端登录</b>：新租户管理员医院端首登（初始密码强制改密 40305 → 改密 → 恢复）、平台端登录、 跨端拒绝（医院令牌进不了平台接口、反之亦然）、无令牌
 *       40100；
 *   <li><b>角色菜单与权限码</b>：管理员建随访用户（FOLLOWUP_DOCTOR + 负责病区），同一租户内两个角色下发的
 *       菜单树与权限码差异（PLT-PERM-006），无权限接口后端 40300（前端隐藏不能代替后端校验）；
 *   <li><b>数据范围与脱敏</b>：权限预览与登录会话同一计算入口——随访用户 WARD + RESPONSIBLE_PATIENT （含负责病区、来源角色），管理员
 *       ALL；用户目录手机号只回脱敏值（PLT-PERM-009 默认脱敏）；
 *   <li><b>审计链生效</b>：本动线在新租户库产生的登录 / 操作审计自 GENESIS 起整链封存且验链通过 （新建租户链上没有其他 IT
 *       的维护清理空洞，可做强断言；平台库为共享库只验本用例新增段）。
 * </ol>
 *
 * <p>与既有用例的分工：E02–E08 各 IT 在种子演示租户（demoa / demob）上分域验证；本套件五项全部跑在 <b>本用例通过真实开通编排新建的租户</b>
 * 上，覆盖此前没有用例的跨 Epic 集成路径——开通产出的租户从零走到 审计链完整。local / CI 剖面新租户库执行同一份种子（TenantSchemaMigrator），院区 / 科室
 * / 病区 / 内置角色由种子提供； 种子演示账号的口令哈希标记由启动时的 DemoPasswordInitializer 替换、开通发生在运行中，故动线不依赖种子演示账号，
 * 全部使用开通管理员与本用例新建的账号。门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清
 * （结束后整租户回收，含库与平台登记）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class M1FoundationRegressionIT {

    private static final String PLATFORM_PASSWORD = "Demo@2026";
    private static final String TENANT_NAME = "M1回归示例医院";
    private static final String ADMIN_USERNAME = "regress";
    private static final String ADMIN_INITIAL_PASSWORD = "Init@2026#";
    private static final String ADMIN_NEW_PASSWORD = "Fresh@2027pw";
    private static final String NURSE_USERNAME = "itr.m1.nurse";
    private static final String NURSE_PHONE = "13900005678";
    private static final String NURSE_NEW_PASSWORD = "Nurse@2027pw";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuditChainVerifier chainVerifier;

    /** 动线状态：租户开通后各用例共用。 */
    private String tenantCode;

    private long tenantId;
    private String adminToken;
    private String nurseToken;
    private long nurseUserId;

    /** 本用例开始前平台登录链的最大 id：平台库共享，只验本用例新增段。 */
    private long platformLoginMaxId;

    private JdbcTemplate root;

    // ---------------------------------------------------------------- 标准①：能开通租户

    @Test
    @Order(1)
    @SuppressWarnings("unchecked")
    void item1_tenantProvisioningWorksEndToEnd() {
        connectRoot();
        recycleLeftoverFromEarlierRun();
        platformLoginMaxId = platformLoginMaxId();

        // 未登录不得开通
        Map<?, ?> anonymous = post("/api/platform/tenants", null, Map.of("name", TENANT_NAME));
        assertEquals(40100, code(anonymous), "无令牌不得创建租户");

        // 平台端登录 → 开通向导（不填编码，后端生成；带首个管理员；勾选 base + followup 模块，
        // PLT-MOD-004——不开 followup 则随访菜单与权限码对整个租户不可见）
        String platform = platformToken();
        Map<?, ?> created =
                post(
                        "/api/platform/tenants",
                        platform,
                        Map.of(
                                "name", TENANT_NAME,
                                "adminUsername", ADMIN_USERNAME,
                                "adminDisplayName", "M1回归管理员（示例）",
                                "adminInitialPassword", ADMIN_INITIAL_PASSWORD,
                                "modules", List.of("base", "followup")));
        assertEquals(0, code(created), () -> "开通失败：" + created);
        Map<?, ?> data = data(created);
        tenantCode = String.valueOf(data.get("code"));
        tenantId = ((Number) data.get("tenantId")).longValue();
        assertTrue(tenantCode.matches("^t[a-z0-9]{7}$"), "编码自动生成：" + tenantCode);

        List<Map<String, Object>> steps = (List<Map<String, Object>>) data.get("steps");
        assertEquals(6, steps.size(), "开通编排六步（含可选 config-template）：" + steps);
        assertTrue(
                steps.stream()
                        .allMatch(
                                step ->
                                        "SUCCESS".equals(step.get("status"))
                                                || ("config-template".equals(step.get("step"))
                                                        && "SKIPPED".equals(step.get("status")))),
                () -> "未选模板时 config-template 记 SKIPPED，其余 SUCCESS：" + steps);

        // 平台登记与租户库
        assertEquals(
                "TRIAL",
                platformQuery(
                        "SELECT status FROM plt_tenant WHERE id = ?", String.class, tenantId));
        assertEquals(
                1,
                root.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                        Integer.class,
                        "t_" + tenantCode),
                "租户库已建");

        // 首个管理员（待改密）与种子内置角色 / 数据范围就绪
        assertEquals(
                1,
                inTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT must_change_password FROM sys_user WHERE username = ?",
                                        Integer.class,
                                        ADMIN_USERNAME)),
                "首个管理员强制首登改密");
        assertTrue(
                inTenant(
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM sys_role WHERE code IN"
                                                        + " ('TENANT_ADMIN','FOLLOWUP_DOCTOR')"
                                                        + " AND status = 'ENABLED'",
                                                Integer.class))
                        >= 2,
                "种子内置角色已初始化");
        assertEquals(
                1,
                inTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role_scope rs JOIN sys_role r"
                                                + " ON r.id = rs.role_id WHERE r.code ="
                                                + " 'TENANT_ADMIN' AND rs.scope_type = 'ALL'",
                                        Integer.class)),
                "租户管理员角色默认范围 ALL");

        // E06-T12 回归发现并修复：运行中开通的租户要在迁移后就地替换种子演示占位（口令哈希 / 手机号），
        // 否则演示账号要等下次重启才能登录、用户目录解密 40002（演示初始化器原本只在启动时扫已注册租户）
        assertEquals(
                0,
                inTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_user WHERE password_hash ="
                                                + " 'PENDING_DEMO_HASH' OR phone LIKE"
                                                + " 'PENDING_DEMO_PHONE:%'",
                                        Integer.class)),
                "开通完成即无演示占位残留");
    }

    // ---------------------------------------------------------------- 标准②：医院端与平台端登录

    @Test
    @Order(2)
    void item2_hospitalAndPlatformLoginWork() {
        assertNotNull(tenantCode, "标准①先行");

        // 医院端首登：初始密码可用，但会话带强制改密标记
        Map<?, ?> login =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of(
                                "tenantCode", tenantCode,
                                "username", ADMIN_USERNAME,
                                "password", ADMIN_INITIAL_PASSWORD));
        assertEquals(0, code(login), () -> "初始密码登录失败：" + login);
        adminToken = token(login);
        assertEquals(
                Boolean.TRUE,
                data(get("/api/hospital/auth/me", adminToken)).get("mustChangePassword"),
                "初始密码必须先改");

        // 强制改密期间：除改密 / 登出 / me 外一律 40305（PLT-AUTH-010）
        assertEquals(40305, code(get("/api/hospital/menus", adminToken)));

        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/password",
                                adminToken,
                                Map.of(
                                        "oldPassword", ADMIN_INITIAL_PASSWORD,
                                        "newPassword", ADMIN_NEW_PASSWORD))),
                "改密失败");
        assertEquals(
                Boolean.FALSE,
                data(get("/api/hospital/auth/me", adminToken)).get("mustChangePassword"),
                "改密后解除强制改密标记");
        assertEquals(0, code(get("/api/hospital/menus", adminToken)), "改密后其余接口恢复可用");

        // 旧口令不能登录，新口令可以（旧口令 40101，PLT-AUTH-006）
        assertEquals(
                40101,
                code(
                        post(
                                "/api/hospital/auth/login",
                                null,
                                Map.of(
                                        "tenantCode", tenantCode,
                                        "username", ADMIN_USERNAME,
                                        "password", ADMIN_INITIAL_PASSWORD))));
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/login",
                                null,
                                Map.of(
                                        "tenantCode", tenantCode,
                                        "username", ADMIN_USERNAME,
                                        "password", ADMIN_NEW_PASSWORD))));

        // 平台端登录（第二端）与跨端拒绝、无令牌拒绝
        String platform = platformToken();
        assertEquals(0, code(get("/api/platform/menus", platform)), "平台端登录可用");
        assertEquals(40300, code(get("/api/platform/menus", adminToken)), "医院令牌进不了平台接口");
        assertEquals(40300, code(get("/api/hospital/menus", platform)), "平台令牌进不了医院接口");
        assertEquals(40100, code(get("/api/hospital/menus", null)), "无令牌拒绝");
    }

    // ---------------------------------------------------------------- 标准③：角色菜单与权限码

    @Test
    @Order(3)
    void item3_roleMenusAndPermissionCodesDifferByRole() {
        assertNotNull(adminToken, "标准②先行");

        // 管理员建随访用户：FOLLOWUP_DOCTOR + 种子病区（临时密码只随本次响应下发）
        long deptId =
                inTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'",
                                        Long.class));
        long wardId =
                inTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM ward WHERE code = 'W-DEMO-1'", Long.class));
        long roleId =
                inTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_role WHERE code = 'FOLLOWUP_DOCTOR'",
                                        Long.class));
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        adminToken,
                        Map.of(
                                "username",
                                NURSE_USERNAME,
                                "displayName",
                                "M1回归随访护士（示例）",
                                "phone",
                                NURSE_PHONE,
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId),
                                "wardIds",
                                List.of(wardId)));
        assertEquals(0, code(created), () -> "新建随访用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        assertFalse(temporary.isBlank(), "临时密码随本次响应下发一次");
        nurseUserId = ((Number) userOf(created).get("id")).longValue();

        // 随访用户首登 → 强制改密 → 会话可用
        Map<?, ?> login =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of(
                                "tenantCode", tenantCode,
                                "username", NURSE_USERNAME,
                                "password", temporary));
        assertEquals(0, code(login), () -> "随访用户临时密码登录失败：" + login);
        nurseToken = token(login);
        assertEquals(40305, code(get("/api/hospital/menus", nurseToken)));
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/password",
                                nurseToken,
                                Map.of(
                                        "oldPassword", temporary,
                                        "newPassword", NURSE_NEW_PASSWORD))));
        assertEquals(0, code(get("/api/hospital/menus", nurseToken)));

        // 菜单树与权限码按角色差异下发（PLT-PERM-006）
        List<String> adminMenus = menuTitles(adminToken);
        List<String> nurseMenus = menuTitles(nurseToken);
        assertTrue(adminMenus.contains("患者档案"), "管理员菜单含患者档案：" + adminMenus);
        assertTrue(adminMenus.contains("用户管理"), "管理员菜单含用户管理：" + adminMenus);
        assertTrue(nurseMenus.contains("患者档案"), "随访用户菜单含患者档案：" + nurseMenus);
        assertFalse(nurseMenus.contains("用户管理"), "随访用户菜单不含用户管理：" + nurseMenus);

        List<String> adminCodes = codes(adminToken);
        List<String> nurseCodes = codes(nurseToken);
        assertTrue(adminCodes.contains("followup:patient:view"), "管理员有患者档案权限码");
        assertTrue(adminCodes.contains("tenant:user:view"), "管理员有用户管理权限码");
        assertTrue(nurseCodes.contains("followup:patient:view"), "随访用户有患者档案权限码");
        assertFalse(nurseCodes.contains("tenant:user:view"), "随访用户没有用户管理权限码");

        // 前端隐藏不能代替后端校验：无权限码直接 40300
        assertEquals(40300, code(get("/api/hospital/system/users", nurseToken)), "随访用户调用户列表 40300");
        assertEquals(0, code(get("/api/hospital/system/users", adminToken)), "管理员调用户列表正常");
    }

    // ---------------------------------------------------------------- 标准④：数据范围与脱敏

    @Test
    @Order(4)
    void item4_dataScopeAndMaskingEffective() {
        assertNotNull(adminToken, "标准②先行");
        assertNotNull(nurseToken, "标准③先行");

        // 数据范围：预览与登录会话同一计算入口（PLT-ORG-009）
        Map<?, ?> nurseView =
                data(
                        get(
                                "/api/hospital/system/users/"
                                        + nurseUserId
                                        + "/effective-permissions",
                                adminToken));
        assertEquals(NURSE_USERNAME, nurseView.get("username"));
        Map<String, List<String>> nurseScopes = scopeTypeSources(nurseView);
        assertTrue(
                nurseScopes.containsKey("WARD") && nurseScopes.containsKey("RESPONSIBLE_PATIENT"),
                "随访医生范围 = 病区 + 本人负责的患者：" + nurseScopes);
        assertEquals(List.of("随访医生"), nurseScopes.get("WARD"), "范围来源角色为随访医生");
        assertTrue(
                String.valueOf(nurseView.get("wards")).contains("虚构病区一"), "预览可见负责病区（种子病区随租户初始化）");

        long adminUserId =
                inTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_user WHERE username = ?",
                                        Long.class,
                                        ADMIN_USERNAME));
        Map<String, List<String>> adminScopes =
                scopeTypeSources(
                        data(
                                get(
                                        "/api/hospital/system/users/"
                                                + adminUserId
                                                + "/effective-permissions",
                                        adminToken)));
        assertTrue(adminScopes.containsKey("ALL"), "租户管理员范围 ALL：" + adminScopes);

        // 脱敏：用户目录手机号只回脱敏值，响应里不出现明文（PLT-PERM-009 默认脱敏）
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        adminToken,
                        Map.of(
                                "username", "itr.m1.plain",
                                "displayName", "M1回归脱敏用户（示例）",
                                "phone", "13900005679",
                                "mainDeptId",
                                        inTenant(
                                                () ->
                                                        jdbc.queryForObject(
                                                                "SELECT id FROM sys_dept WHERE"
                                                                        + " code ="
                                                                        + " 'DEPT-CARDIO'",
                                                                Long.class)),
                                "roleIds",
                                        List.of(
                                                inTenant(
                                                        () ->
                                                                jdbc.queryForObject(
                                                                        "SELECT id FROM sys_role"
                                                                                + " WHERE code ="
                                                                                + " 'FOLLOWUP_DOCTOR'",
                                                                        Long.class)))));
        assertEquals(0, code(created), () -> "新建脱敏验证用户失败：" + created);
        assertEquals("139****5679", userOf(created).get("phoneMasked"), "新建响应只回脱敏手机号");
        assertFalse(String.valueOf(created).contains("13900005679"), "新建响应不得出现明文手机号");

        Map<?, ?> page = get("/api/hospital/system/users?keyword=" + NURSE_USERNAME, adminToken);
        assertEquals(0, code(page));
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) ((Map<?, ?>) data(page)).get("items");
        assertEquals(1, items.size(), "按账号检索命中一行：" + items);
        assertEquals(
                NURSE_PHONE.substring(0, 3) + "****" + NURSE_PHONE.substring(7),
                items.get(0).get("phoneMasked"),
                "列表只回脱敏手机号");
        assertFalse(String.valueOf(page).contains(NURSE_PHONE), "列表响应不得出现明文手机号");
    }

    // ---------------------------------------------------------------- 标准⑤：审计链生效

    @Test
    @Order(5)
    void item5_auditChainsSealedLinkedAndIntact() {
        assertNotNull(tenantCode, "标准①先行");

        // 租户库三张审计表：本动线的登录 / 操作审计自 GENESIS 起整链封存、验链通过。
        // 新建租户没有其他 IT 的维护清理空洞，可做全表强断言（共享库做不到，见 AuditChainMySqlIT）。
        List<AuditChainVerifier.TableVerification> tenantResult =
                inTenant(chainVerifier::verifyTenantDatabase);
        assertEquals(3, tenantResult.size(), "租户库三张审计表");
        for (AuditChainVerifier.TableVerification table : tenantResult) {
            assertTrue(
                    table.intact(),
                    () -> "新租户审计链必须自 GENESIS 完整：" + table.table() + " " + table.breaks());
        }
        AuditChainVerifier.TableVerification loginChain =
                tenantResult.stream()
                        .filter(table -> table.table().equals("aud_login_log"))
                        .findFirst()
                        .orElseThrow();
        assertTrue(loginChain.hashedRows() >= 3, "本动线至少 3 次登录入链：" + loginChain.hashedRows());

        // 本动线的业务动作确实入链（开通产出的租户上：改密 × 2、建用户 × 2）
        assertEquals(
                2,
                inTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                                + " 'USER_PASSWORD_CHANGE' AND hash IS NOT NULL",
                                        Integer.class)),
                "两次自助改密审计已封存");
        assertEquals(
                2,
                inTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                                + " 'USER_CREATE' AND hash IS NOT NULL",
                                        Integer.class)),
                "两次建用户审计已封存");

        // 平台库为共享库（其他 IT 的自清理会留洞），只验本用例新增段：平台登录全部封存且前后衔接
        List<Map<String, Object>> platformLogins =
                TenantContext.callAsPlatform(
                        () ->
                                jdbc.query(
                                        "SELECT id, prev_hash, hash FROM aud_login_log WHERE id > ?"
                                                + " ORDER BY id",
                                        (rs, row) ->
                                                Map.of(
                                                        "id", rs.getLong("id"),
                                                        "prev_hash", rs.getString("prev_hash"),
                                                        "hash", rs.getString("hash")),
                                        platformLoginMaxId));
        assertTrue(platformLogins.size() >= 2, "本动线至少两次平台登录：" + platformLogins.size());
        for (Map<String, Object> row : platformLogins) {
            assertEquals(64, String.valueOf(row.get("hash")).length(), "平台登录行已封存：" + row);
        }
        for (int i = 1; i < platformLogins.size(); i++) {
            assertEquals(
                    platformLogins.get(i - 1).get("hash"),
                    platformLogins.get(i).get("prev_hash"),
                    "平台登录链前后衔接（第 " + i + " 行）");
        }
    }

    // ---------------------------------------------------------------- 自建自清

    /** 整租户回收：库、库账号与平台登记（与开通对称）。平台审计留在链上（只追加，不清理）。 */
    @AfterAll
    void cleanUp() {
        if (root == null) {
            connectRoot();
        }
        recycleLeftoverFromEarlierRun();
    }

    /** 清理本用例名的遗留租户（上次异常中断留下的），保证可重跑。 */
    private void recycleLeftoverFromEarlierRun() {
        List<Map<String, Object>> leftovers =
                platformQueryRows("SELECT id, code FROM plt_tenant WHERE name = ?", TENANT_NAME);
        for (Map<String, Object> row : leftovers) {
            long id = ((Number) row.get("id")).longValue();
            String code = String.valueOf(row.get("code"));
            root.execute("DROP DATABASE IF EXISTS t_" + code);
            root.execute("DROP USER IF EXISTS `t_" + code + "`@'%'");
            platformUpdate("DELETE FROM plt_provision_step WHERE tenant_id = ?", id);
            platformUpdate("DELETE FROM plt_tenant_resource WHERE tenant_id = ?", id);
            platformUpdate("DELETE FROM plt_tenant_module WHERE tenant_id = ?", id);
            platformUpdate("DELETE FROM plt_tenant WHERE id = ?", id);
        }
        tenantCode = null;
        tenantId = 0L;
    }

    private void connectRoot() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        root =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/?useSSL=false&allowPublicKeyRetrieval=true",
                                "root",
                                "DevOnly#root2026"));
    }

    // ---------------------------------------------------------------- 基础设施

    private String platformToken() {
        Map<?, ?> login =
                post(
                        "/api/platform/auth/login",
                        null,
                        Map.of("username", "platform.admin", "password", PLATFORM_PASSWORD));
        assertEquals(0, code(login), () -> "平台登录失败：" + login);
        return token(login);
    }

    private long platformLoginMaxId() {
        return TenantContext.callAsPlatform(
                () ->
                        jdbc.queryForObject(
                                "SELECT COALESCE(MAX(id), 0) FROM aud_login_log", Long.class));
    }

    private void inTenant(Runnable action) {
        TenantContext.runAsTenant(tenantId, tenantCode, action);
    }

    private <T> T inTenant(java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(tenantId, tenantCode, action::get);
    }

    private <T> T platformQuery(String sql, Class<T> type, Object... args) {
        return TenantContext.callAsPlatform(() -> jdbc.queryForObject(sql, type, args));
    }

    private List<Map<String, Object>> platformQueryRows(String sql, Object... args) {
        return TenantContext.callAsPlatform(() -> jdbc.queryForList(sql, args));
    }

    private int platformUpdate(String sql, Object... args) {
        return TenantContext.callAsPlatform(() -> jdbc.update(sql, args));
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        Object code = response == null ? null : response.get("code");
        return code == null ? -1 : Integer.parseInt(code.toString());
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response == null ? null : response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }

    private static Map<?, ?> userOf(Map<?, ?> createdResponse) {
        return (Map<?, ?>) data(createdResponse).get("user");
    }

    private static String token(Map<?, ?> loginResponse) {
        return String.valueOf(data(loginResponse).get("token"));
    }

    private static Object dataRaw(Map<?, ?> response) {
        return response == null ? null : response.get("data");
    }

    @SuppressWarnings("unchecked")
    private List<String> menuTitles(String token) {
        List<String> titles = new ArrayList<>();
        collectTitles((List<?>) dataRaw(get("/api/hospital/menus", token)), titles);
        return titles;
    }

    /** 递归收菜单中文标题（MenuNode.name 是路由名，标题在 meta.title）。 */
    private static void collectTitles(List<?> nodes, List<String> titles) {
        if (nodes == null) {
            return;
        }
        for (Object node : nodes) {
            Map<?, ?> item = (Map<?, ?>) node;
            titles.add(String.valueOf(((Map<?, ?>) item.get("meta")).get("title")));
            collectTitles((List<?>) item.get("children"), titles);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> codes(String token) {
        return (List<String>) dataRaw(get("/api/hospital/auth/codes", token));
    }

    private static Map<String, List<String>> scopeTypeSources(Map<?, ?> view) {
        Map<String, List<String>> sources = new java.util.LinkedHashMap<>();
        for (Object item : (List<?>) view.get("scopeTypes")) {
            Map<?, ?> type = (Map<?, ?>) item;
            sources.put(
                    String.valueOf(type.get("type")),
                    ((List<?>) type.get("sourceRoles")).stream().map(String::valueOf).toList());
        }
        return sources;
    }
}
