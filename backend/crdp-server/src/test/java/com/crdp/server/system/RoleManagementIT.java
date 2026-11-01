package com.crdp.server.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * E06-T05 端到端（验收原文：模块开通时下发角色模板）。场景按序：
 *
 * <ol>
 *   <li>模块开通下发模板（PLT-ROLE-005）：清空 demob 的模板角色 → 平台关闭 / 重新开通 followup → 租户库回到 5 个 TEMPLATE
 *       角色与默认菜单；新建租户向导勾选 followup 同样下发（openForOnboarding）；
 *   <li>租户内置角色（PLT-ROLE-004）：TENANT_ADMIN 为 BUILT_IN，不可停用 / 不可改权限 / 不可删、不能移除最后一名启用成员；
 *   <li>角色管理（PLT-ORG-004）：新建 / 编辑 / 复制 / 停用 / 删除、菜单只能选可分配范围（敏感按钮 40001）、数据范围七类型与自定义科室；
 *   <li>模板应用（PLT-ORG-005）：从模板复制生成自定义角色；模板只读、不能直接分配成员；
 *   <li>权限与隔离：无 tenant:role:view 40300、角色不存在 40400、demoa 与 demob 角色互不可见；全部写审计。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（ITR- 前缀角色、模块状态恢复、向导租户库）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RoleManagementIT {

    private static final String PASSWORD = "Demo@2026";
    private static final long TENANT_B = 2L; // demob
    private static final List<String> TEMPLATE_CODES =
            List.of(
                    "FOLLOWUP_NURSE",
                    "FOLLOWUP_DUTY_DOCTOR",
                    "FOLLOWUP_RESEARCHER",
                    "FOLLOWUP_CRC",
                    "FOLLOWUP_DATA_CLERK");

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    private static String wizardCode;

    /** 只清理本 IT 建的数据（ITR- 前缀）与审计，不碰种子演示数据。 */
    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code LIKE 'ITR-%'");
                        jdbc.update(
                                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                        + " WHERE r.code LIKE 'ITR-%'");
                        jdbc.update(
                                "DELETE rd FROM sys_role_scope_dept rd JOIN sys_role r ON r.id = rd.role_id"
                                        + " WHERE r.code LIKE 'ITR-%'");
                        jdbc.update(
                                "DELETE rd FROM sys_role_scope_dept rd JOIN sys_role r ON r.id = rd.role_id"
                                        + " WHERE r.code LIKE 'ITR-%'");
                        jdbc.update(
                                "DELETE fp FROM sys_role_field_policy fp JOIN sys_role r ON r.id ="
                                        + " fp.role_id WHERE r.code LIKE 'ITR-%'");
                        jdbc.update("DELETE FROM sys_role WHERE code LIKE 'ITR-%'");
                        AuditTableMaintenance.delete(
                                jdbc, "DELETE FROM aud_log WHERE event_type LIKE 'ROLE_%'");
                    });
        }
    }

    @AfterAll
    static void cleanUp(@Autowired JdbcTemplate platformJdbc) {
        if (wizardCode != null) {
            TenantContext.runAsPlatform(
                    () -> {
                        List<Long> ids =
                                platformJdbc.queryForList(
                                        "SELECT id FROM plt_tenant WHERE code = ?",
                                        Long.class,
                                        wizardCode);
                        for (Long id : ids) {
                            platformJdbc.update(
                                    "DELETE FROM plt_provision_step WHERE tenant_id = ?", id);
                            platformJdbc.update(
                                    "DELETE FROM plt_tenant_module_log WHERE tenant_id = ?", id);
                            platformJdbc.update(
                                    "DELETE FROM plt_tenant_module WHERE tenant_id = ?", id);
                            platformJdbc.update(
                                    "DELETE FROM plt_tenant_resource WHERE tenant_id = ?", id);
                            platformJdbc.update("DELETE FROM plt_tenant WHERE id = ?", id);
                        }
                    });
            // 应用账号无删库权限，用 root 清理向导租户库（与 PlatformTenantModuleMySqlIT 同做法）
            rootJdbc().update("DROP DATABASE IF EXISTS t_" + wizardCode);
            rootJdbc().update("DROP USER IF EXISTS `t_" + wizardCode + "`@'%'");
            wizardCode = null;
        }
        // demob 的 followup 恢复 OPEN；本 IT 的开通记录与平台审计清掉
        TenantContext.runAsPlatform(
                () -> {
                    platformJdbc.update(
                            "UPDATE plt_tenant_module SET status = 'OPEN', closed_at = NULL,"
                                    + " reason = NULL, operator_id = NULL"
                                    + " WHERE tenant_id = 2 AND module_code = 'followup'");
                    platformJdbc.update("DELETE FROM plt_tenant_module_log WHERE tenant_id = 2");
                    AuditTableMaintenance.delete(
                            platformJdbc,
                            "DELETE FROM plt_audit_log WHERE event_type IN"
                                    + " ('MODULE_OPEN', 'MODULE_CLOSE') AND target LIKE '%:demob:%'");
                });
    }

    private static JdbcTemplate rootJdbc() {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        return new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        base + "?useSSL=false&allowPublicKeyRetrieval=true",
                        "root",
                        "DevOnly#root2026"));
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @Order(1)
    void moduleOpenIssuesRoleTemplatesToTenantDatabase() {
        // 前置：清掉 demob 的模板角色，模拟从未走过开通流程的租户库
        runInTenant(
                "demob",
                () -> {
                    for (String code : TEMPLATE_CODES) {
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code = ?",
                                code);
                        jdbc.update("DELETE FROM sys_role WHERE code = ?", code);
                    }
                });

        String platformToken = loginPlatform("platform.admin");
        // 关闭 → 重新开通（PLT-MOD-004 开通流程第 4 步：初始化配置默认值与业务角色模板）
        assertEquals(
                0,
                code(
                        post(
                                "/api/platform/tenants/2/modules/followup/close",
                                platformToken,
                                Map.of("reason", "IT 验证模板下发（示例）"))));
        Map<?, ?> opened =
                post(
                        "/api/platform/tenants/2/modules/followup/open",
                        platformToken,
                        Map.of("reason", "IT 验证模板下发（示例）"));
        assertEquals(0, code(opened), () -> "重新开通失败：" + opened);

        // 租户库回到 5 个模板角色（role_type=TEMPLATE，来源 followup），各带默认菜单授权
        Integer templates =
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role WHERE role_type = 'TEMPLATE'"
                                                + " AND module_code = 'followup'",
                                        Integer.class));
        assertEquals(TEMPLATE_CODES.size(), templates, "模块开通应下发 5 个业务角色模板");
        // 随访员模板带默认菜单（目录 + 患者档案 / 随访任务 / 报告单）；资料录入员不含随访任务
        Integer nurseMenus =
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON"
                                                + " r.id = rm.role_id WHERE r.code = 'FOLLOWUP_NURSE'",
                                        Integer.class));
        assertEquals(4, nurseMenus, "随访员模板默认 4 项菜单（20/21/22/23）");
        Integer clerkTaskMenu =
                runInTenant(
                        "demob",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON"
                                                + " r.id = rm.role_id WHERE r.code ="
                                                + " 'FOLLOWUP_DATA_CLERK' AND rm.menu_id = 22",
                                        Integer.class));
        assertEquals(0, clerkTaskMenu, "资料录入员模板不含随访任务菜单");

        // 开通记录与平台审计留痕（下发数量写进 detail）
        String detail =
                TenantContext.callAsPlatform(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT detail FROM plt_tenant_module_log WHERE tenant_id = 2"
                                                + " AND module_code = 'followup' AND action = 'OPEN'"
                                                + " ORDER BY id DESC LIMIT 1",
                                        String.class));
        assertNotNull(detail);
        assertTrue(detail.contains("角色模板 5 个"), "开通记录应写明下发的模板数：" + detail);
    }

    @Test
    @Order(2)
    void wizardOnboardingIssuesRoleTemplates() {
        String platformToken = loginPlatform("platform.admin");
        wizardCode =
                "trole"
                        + Long.toString(System.currentTimeMillis(), 36)
                                .toLowerCase()
                                .replaceAll("[^a-z0-9]", "");
        Map<?, ?> created =
                post(
                        "/api/platform/tenants",
                        platformToken,
                        Map.of(
                                "name", "虚构角色模板医院",
                                "code", wizardCode,
                                "adminUsername", "role.admin",
                                "adminDisplayName", "角色管理员（示例）",
                                "adminInitialPassword", "Init@2026#",
                                "modules", List.of("followup")));
        assertEquals(0, code(created), () -> "向导开通失败：" + created);

        // 新租户库同样有 5 个模板；内置角色 TENANT_ADMIN 是 BUILT_IN（PLT-ROLE-004）
        Integer templates =
                runInTenant(
                        wizardCode,
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role WHERE role_type ="
                                                + " 'TEMPLATE' AND module_code = 'followup'",
                                        Integer.class));
        assertEquals(TEMPLATE_CODES.size(), templates, "新建租户应随模块开通收到角色模板");
        String adminType =
                runInTenant(
                        wizardCode,
                        () ->
                                jdbc.queryForObject(
                                        "SELECT role_type FROM sys_role WHERE code = 'TENANT_ADMIN'",
                                        String.class));
        assertEquals("BUILT_IN", adminType, "租户初始化生成的租户管理员是内置角色");
        // 开通编排给内置管理员补授「维护角色与授权」按钮（plt_menu 321）
        Integer roleEditGranted =
                runInTenant(
                        wizardCode,
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role_menu rm JOIN sys_role r ON"
                                                + " r.id = rm.role_id WHERE r.code = 'TENANT_ADMIN'"
                                                + " AND rm.menu_id = 321",
                                        Integer.class));
        assertEquals(1, roleEditGranted);
    }

    @Test
    @Order(3)
    void builtInRoleIsProtectedOnEveryMutation() {
        String token = adminToken("demoa");
        long builtInId = roleId("demoa", "TENANT_ADMIN");

        assertEquals(
                40901,
                code(
                        post(
                                "/api/hospital/system/roles/" + builtInId + "/status",
                                token,
                                Map.of("action", "DISABLE", "reason", "IT（示例）"))));
        assertEquals(
                40901,
                code(
                        put(
                                "/api/hospital/system/roles/" + builtInId,
                                token,
                                Map.of("code", "TENANT_ADMIN", "name", "改名（示例）"))));
        assertEquals(
                40901,
                code(
                        put(
                                "/api/hospital/system/roles/" + builtInId + "/menus",
                                token,
                                Map.of("menuIds", List.of(21)))));
        assertEquals(
                40901,
                code(
                        put(
                                "/api/hospital/system/roles/" + builtInId + "/scope",
                                token,
                                Map.of("scopeTypes", List.of("ALL"), "customDeptIds", List.of()))));
        assertEquals(40901, code(delete("/api/hospital/system/roles/" + builtInId, token)));
        // 不能移除最后一名启用的租户管理员
        assertEquals(
                40901,
                code(
                        put(
                                "/api/hospital/system/roles/" + builtInId + "/members",
                                token,
                                Map.of("userIds", List.of()))));
    }

    @Test
    @Order(4)
    void roleLifecycleWithMenusScopeMembersAndAudit() {
        String token = adminToken("demoa");

        // 新建（校验失败 → 40001；编码重复语义由复制用例覆盖）
        assertEquals(
                40001,
                code(
                        post(
                                "/api/hospital/system/roles",
                                token,
                                Map.of("code", "", "name", "示例角色"))));
        Map<?, ?> created =
                post(
                        "/api/hospital/system/roles",
                        token,
                        Map.of(
                                "code",
                                "ITR-NURSE-A",
                                "name",
                                "IT 随访护士（示例）",
                                "description",
                                "IT 用例角色"));
        assertEquals(0, code(created));
        assertEquals("CUSTOM", data(created).get("roleType"));
        long roleId = ((Number) data(created).get("id")).longValue();

        // 编辑：名称可改，编码不可改（40001）
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId,
                                token,
                                Map.of("code", "ITR-NURSE-A", "name", "IT 随访护士改（示例）"))));
        assertEquals(
                40001,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId,
                                token,
                                Map.of("code", "ITR-CHANGED", "name", "IT 随访护士改（示例）"))));

        // 菜单页签：可分配范围树（敏感按钮明文 211 不可分配）+ 保存
        Map<?, ?> menusView = get("/api/hospital/system/roles/" + roleId + "/menus", token);
        assertEquals(0, code(menusView));
        String menusBody = String.valueOf(data(menusView));
        assertTrue(menusBody.contains("assignable"), "菜单树应带可分配标记：" + menusBody);
        assertEquals(
                40001,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/menus",
                                token,
                                Map.of("menuIds", List.of(21, 211)))),
                "敏感按钮（明文查看）不在可分配范围，保存应 40001");
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/menus",
                                token,
                                Map.of("menuIds", List.of(20, 21, 22)))));
        assertEquals(
                3,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = ?",
                                        Integer.class,
                                        roleId)));

        // 数据范围：七类型校验、自定义科室必勾、保存后回读
        assertEquals(
                40001,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/scope",
                                token,
                                Map.of(
                                        "scopeTypes",
                                        List.of("HOSPITAL"),
                                        "customDeptIds",
                                        List.of()))));
        assertEquals(
                40001,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/scope",
                                token,
                                Map.of(
                                        "scopeTypes",
                                        List.of("CUSTOM_DEPT"),
                                        "customDeptIds",
                                        List.of()))));
        long deptId = deptIdOfDemoa();
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/scope",
                                token,
                                Map.of(
                                        "scopeTypes",
                                        List.of("WARD", "CUSTOM_DEPT"),
                                        "customDeptIds",
                                        List.of(deptId)))));
        Map<?, ?> scopeView = data(get("/api/hospital/system/roles/" + roleId + "/scope", token));
        assertTrue(String.valueOf(scopeView.get("scopeTypes")).contains("WARD"));
        assertTrue(String.valueOf(scopeView.get("scopeTypes")).contains("CUSTOM_DEPT"));
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role_scope_dept WHERE role_id = ?",
                                        Integer.class,
                                        roleId)));

        // 成员：加启用用户；移除后可删除
        long nurseId = userId("demoa", "nurse.demo");
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/members",
                                token,
                                Map.of("userIds", List.of(nurseId)))));
        assertEquals(
                40901, code(delete("/api/hospital/system/roles/" + roleId, token)), "有成员时不可删除");
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/members",
                                token,
                                Map.of("userIds", List.of()))));

        // 停用（写审计；重复停用 40901）→ 启用 → 删除
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/system/roles/" + roleId + "/status",
                                token,
                                Map.of("action", "DISABLE", "reason", "IT 停用（示例）"))));
        assertEquals(
                40901,
                code(
                        post(
                                "/api/hospital/system/roles/" + roleId + "/status",
                                token,
                                Map.of("action", "DISABLE", "reason", "IT 停用（示例）"))));
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/system/roles/" + roleId + "/status",
                                token,
                                Map.of("action", "ENABLE"))));
        assertEquals(0, code(delete("/api/hospital/system/roles/" + roleId, token)));
        assertEquals(
                0,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role WHERE id = ?",
                                        Integer.class,
                                        roleId)));

        // 审计（租户库 aud_log）：建、改、授权、范围、成员、状态、删除全留痕
        Integer audits =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(DISTINCT event_type) FROM aud_log WHERE"
                                                + " event_type LIKE 'ROLE_%'",
                                        Integer.class));
        assertTrue(audits != null && audits >= 7, "角色操作应写审计，实际 " + audits);
    }

    @Test
    @Order(5)
    void templateCopyCreatesAdjustableCustomRole() {
        String token = adminToken("demoa");
        long templateId = roleId("demoa", "FOLLOWUP_NURSE");

        // 模板只读：不能改菜单 / 直接分配成员
        assertEquals(
                40901,
                code(
                        put(
                                "/api/hospital/system/roles/" + templateId + "/menus",
                                token,
                                Map.of("menuIds", List.of(21)))));
        assertEquals(
                40901,
                code(
                        put(
                                "/api/hospital/system/roles/" + templateId + "/members",
                                token,
                                Map.of("userIds", List.of(userId("demoa", "nurse.demo"))))));

        // 从模板复制（PLT-ORG-005）：生成 CUSTOM 副本，菜单随模板带来
        Map<?, ?> copied =
                post(
                        "/api/hospital/system/roles/from-template",
                        token,
                        Map.of(
                                "templateId", templateId,
                                "code", "ITR-FROM-TPL",
                                "name", "IT 随访员副本（示例）"));
        assertEquals(0, code(copied), () -> "模板复制失败：" + copied);
        assertEquals("CUSTOM", data(copied).get("roleType"));
        assertEquals(4, ((Number) data(copied).get("menuCount")).intValue(), "副本继承模板默认菜单");
        long copyId = ((Number) data(copied).get("id")).longValue();

        // 副本可调整（菜单收窄到患者档案 + 随访任务）
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + copyId + "/menus",
                                token,
                                Map.of("menuIds", List.of(20, 21)))));
        assertEquals(
                2,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role_menu WHERE role_id = ?",
                                        Integer.class,
                                        copyId)));

        // 复制自定义角色（PLT-ORG-004「复制」）：菜单与范围一并带来
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + copyId + "/scope",
                                token,
                                Map.of(
                                        "scopeTypes",
                                        List.of("RESPONSIBLE_PATIENT"),
                                        "customDeptIds",
                                        List.of()))));
        Map<?, ?> copiedAgain =
                post(
                        "/api/hospital/system/roles/" + copyId + "/copy",
                        token,
                        Map.of("code", "ITR-COPY-OF-COPY", "name", "IT 再副本（示例）"));
        assertEquals(0, code(copiedAgain));
        Map<?, ?> secondView =
                data(
                        get(
                                "/api/hospital/system/roles/"
                                        + ((Number) data(copiedAgain).get("id")).longValue()
                                        + "/scope",
                                token));
        assertTrue(String.valueOf(secondView.get("scopeTypes")).contains("RESPONSIBLE_PATIENT"));

        // 模板应用对非模板角色 40901
        assertEquals(
                40901,
                code(
                        post(
                                "/api/hospital/system/roles/from-template",
                                token,
                                Map.of(
                                        "templateId",
                                        copyId,
                                        "code",
                                        "ITR-BAD",
                                        "name",
                                        "IT（示例）"))));
    }

    @Test
    @Order(6)
    void permissionDeniedMissingRoleAndCrossTenantIsolation() {
        String doctor = hospitalToken("demoa", "doctor.demo");
        assertEquals(40300, code(get("/api/hospital/system/roles", doctor)));
        assertEquals(40300, code(get("/api/hospital/system/roles/1/menus", doctor)));
        assertEquals(40300, code(get("/api/hospital/system/roles/1/field-policies", doctor)));

        String admin = adminToken("demoa");
        assertEquals(40400, code(get("/api/hospital/system/roles/999999/menus", admin)));
        assertEquals(40400, code(get("/api/hospital/system/roles/999999/members", admin)));

        // 隔离：demoa 建的角色 demob 看不到；模板角色各自的库互不影响
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/system/roles",
                                admin,
                                Map.of("code", "ITR-ISO", "name", "IT 隔离角色（示例）"))));
        List<Map<String, Object>> demobRoles =
                dataItems(get("/api/hospital/system/roles", adminToken("demob")));
        assertFalse(
                demobRoles.stream().anyMatch(role -> "ITR-ISO".equals(role.get("code"))),
                "demob 不应看到 demoa 的角色");
        assertTrue(
                demobRoles.stream().anyMatch(role -> "TENANT_ADMIN".equals(role.get("code"))),
                "demob 有自己的内置角色");
    }

    @Test
    @Order(7)
    void fieldPolicyTabSavesOverlayAndFallsBackToPlatformDefault() {
        String token = adminToken("demoa");
        Map<?, ?> created =
                post(
                        "/api/hospital/system/roles",
                        token,
                        Map.of("code", "ITR-FIELD", "name", "IT 字段策略角色（示例）"));
        assertEquals(0, code(created));
        long roleId = ((Number) data(created).get("id")).longValue();

        // 页签视图：敏感字段目录（患者身份五位字段），未配置时全部为平台默认 MASKED
        Map<?, ?> view =
                data(get("/api/hospital/system/roles/" + roleId + "/field-policies", token));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = (List<Map<String, Object>>) view.get("fields");
        assertEquals(5, fields.size(), "敏感字段目录应含 5 个患者身份字段：" + fields);
        assertTrue(
                fields.stream().allMatch(field -> "MASKED".equals(field.get("policy"))),
                "未配置的角色应回落平台默认（脱敏）");

        // 校验失败：字段不在目录 / 非法策略值（40001）
        assertEquals(
                40001,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/field-policies",
                                token,
                                Map.of(
                                        "policies",
                                        List.of(
                                                Map.of(
                                                        "resource",
                                                        "patient",
                                                        "field",
                                                        "nickname",
                                                        "policy",
                                                        "VISIBLE"))))));
        assertEquals(
                40001,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/field-policies",
                                token,
                                Map.of(
                                        "policies",
                                        List.of(
                                                Map.of(
                                                        "resource",
                                                        "patient",
                                                        "field",
                                                        "name",
                                                        "policy",
                                                        "PLAIN"))))));
        // 模板角色只读（40901）
        long templateId = roleId("demoa", "FOLLOWUP_NURSE");
        assertEquals(
                40901,
                code(
                        put(
                                "/api/hospital/system/roles/" + templateId + "/field-policies",
                                token,
                                Map.of(
                                        "policies",
                                        List.of(
                                                Map.of(
                                                        "resource",
                                                        "patient",
                                                        "field",
                                                        "name",
                                                        "policy",
                                                        "VISIBLE"))))));

        // 保存覆盖：姓名可见、手机号隐藏；其余继续跟随默认
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/field-policies",
                                token,
                                Map.of(
                                        "policies",
                                        List.of(
                                                Map.of(
                                                        "resource", "patient",
                                                        "field", "name",
                                                        "policy", "VISIBLE"),
                                                Map.of(
                                                        "resource", "patient",
                                                        "field", "phone",
                                                        "policy", "HIDDEN"))))));
        Map<?, ?> saved =
                data(get("/api/hospital/system/roles/" + roleId + "/field-policies", token));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> savedFields = (List<Map<String, Object>>) saved.get("fields");
        assertTrue(
                savedFields.stream()
                        .anyMatch(
                                field ->
                                        "name".equals(field.get("field"))
                                                && "VISIBLE".equals(field.get("policy"))
                                                && Boolean.TRUE.equals(field.get("overridden"))),
                "姓名应覆盖为可见：" + savedFields);
        assertTrue(
                savedFields.stream()
                        .anyMatch(
                                field ->
                                        "phone".equals(field.get("field"))
                                                && "HIDDEN".equals(field.get("policy"))),
                "手机号应覆盖为隐藏：" + savedFields);
        assertTrue(
                savedFields.stream()
                        .anyMatch(
                                field ->
                                        "idCard".equals(field.get("field"))
                                                && "MASKED".equals(field.get("policy"))
                                                && Boolean.FALSE.equals(field.get("overridden"))),
                "未覆盖字段应继续跟随平台默认：" + savedFields);
        // 存储与审计留痕（ROLE_FIELD_POLICY）
        Integer rows =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role_field_policy WHERE role_id = ?",
                                        Integer.class,
                                        roleId));
        assertEquals(2, rows);
        Integer audits =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type ="
                                                + " 'ROLE_FIELD_POLICY'",
                                        Integer.class));
        assertTrue(audits != null && audits >= 1, "字段策略保存应写审计，实际 " + audits);

        // 空清单 = 全部回落平台默认
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/field-policies",
                                token,
                                Map.of("policies", List.of()))));
        Map<?, ?> reset =
                data(get("/api/hospital/system/roles/" + roleId + "/field-policies", token));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> resetFields = (List<Map<String, Object>>) reset.get("fields");
        assertTrue(
                resetFields.stream()
                        .noneMatch(field -> Boolean.TRUE.equals(field.get("overridden"))),
                "空清单保存后不应再有覆盖项：" + resetFields);
    }

    // ---------------------------------------------------------------- 基础设施

    private String adminToken(String tenant) {
        return hospitalToken(tenant, "nurse.demo");
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, code(response));
        return (String) data(response).get("token");
    }

    private String loginPlatform(String username) {
        Map<?, ?> response =
                post(
                        "/api/platform/auth/login",
                        null,
                        Map.of("username", username, "password", PASSWORD));
        assertEquals(0, code(response));
        return (String) data(response).get("token");
    }

    private long roleId(String tenant, String code) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_role WHERE code = ?", Long.class, code));
    }

    private long userId(String tenant, String username) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    private long deptIdOfDemoa() {
        return runInTenant(
                "demoa", () -> jdbc.queryForObject("SELECT id FROM sys_dept LIMIT 1", Long.class));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T runInTenant(String tenant, Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    private long tenantIdOf(String tenant) {
        if ("demoa".equals(tenant)) {
            return 1L;
        }
        if ("demob".equals(tenant)) {
            return 2L;
        }
        // 向导租户：查平台库拿 id（IT 环境两演示租户固定 1/2，向导租户动态注册）
        return TenantContext.callAsPlatform(
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM plt_tenant WHERE code = ?", Long.class, tenant));
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private Map<?, ?> delete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class)
                .getBody();
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dataItems(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? List.of() : (List<Map<String, Object>>) data;
    }
}
