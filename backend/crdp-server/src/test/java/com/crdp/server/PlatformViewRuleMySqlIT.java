package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.server.platform.ViewRuleResolver;
import com.crdp.server.platform.ViewRuleService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E20-T02 端到端（验收原文：预览与实际执行一致）。场景按序：
 *
 * <ol>
 *   <li>默认规则（PLT-VIEW-008，种子 V109）：16 条初始规则落库——全局只读禁导出、配置类 / 业务数据模块默认、 敏感字段脱敏、原件附件隐藏、RCT
 *       设盲；业务数据只读默认绑「实施 + 客服」、平台管理员预置可编辑 （OQ-99 布局，避免同层更严合并吞掉预置）。
 *   <li>配置（PLT-VIEW-007）：新建 / 维护 / 停用与校验（层级形状、脱敏码一致、引用存在性 40001、未知规则 40400）；写平台访问审计
 *       VIEW_RULE_CREATE / VIEW_RULE_DISABLE（审计中心「视角规则变更」页签口径）。
 *   <li>优先级与效果预览（PLT-VIEW-009，验收）：模块层只读被平台管理员预置可编辑压制、租户层压全局默认、 同层多角色取更严；字段效果（脱敏 / 隐藏 /
 *       设盲）与目录一致；预览接口输出与 {@link ViewRuleResolver} 对同一 ACTIVE 规则集的解析逐维一致（预览 = 执行的同一引擎）。
 *   <li>生效规则版本：进入视角时 plt_view_session.rule_version = ACTIVE 规则集内容哈希（V50 预留列写实值）， 规则一变即换值。
 *   <li>权限边界：无 platform:view:rule:view 的角色 40300（读、写分别拦）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清（删除本 IT 建的规则、
 * 角色绑定、账号、视角会话行与审计事件；种子默认规则只读不改动）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformViewRuleMySqlIT {

    private static final String ADMIN_USERNAME = "platform.admin";

    private static final String ADMIN_PASSWORD = "Demo@2026";

    private static final String NEW_PASSWORD = "Rule#2026x";

    /** 本 IT 创建的规则 id 与账号 → 清理用。 */
    private static final List<Long> createdRuleIds = new ArrayList<>();

    private static final List<String> createdUsernames = new ArrayList<>();

    @Autowired private TestRestTemplate rest;

    @Autowired private StringRedisTemplate redis;

    @Autowired private ViewRuleService viewRuleService;

    private long demoaId;

    private long demobId;

    @BeforeEach
    void cleanState() {
        deleteKeys("crdp:sess:*");
        deleteKeys("crdp:login:fail:*");
        demoaId = tenantIdByCode("demoa");
        demobId = tenantIdByCode("demob");
    }

    @AfterAll
    static void cleanUp() {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate platform =
                new JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));
        platform.update(
                "DELETE FROM plt_view_session WHERE account_name = 'platform.admin'"
                        + " OR account_id IN (SELECT id FROM plt_account WHERE username LIKE"
                        + " 'it.rule.%')");
        for (Long ruleId : createdRuleIds) {
            platform.update("DELETE FROM plt_view_rule_role WHERE rule_id = ?", ruleId);
            platform.update("DELETE FROM plt_view_rule WHERE id = ?", ruleId);
        }
        platform.update(
                "DELETE FROM plt_account_tenant WHERE account_id IN"
                        + " (SELECT id FROM plt_account WHERE username LIKE 'it.rule.%')");
        platform.update(
                "DELETE FROM plt_password_history WHERE account_id IN"
                        + " (SELECT id FROM plt_account WHERE username LIKE 'it.rule.%')");
        platform.update(
                "DELETE FROM plt_account_role WHERE account_id IN"
                        + " (SELECT id FROM plt_account WHERE username LIKE 'it.rule.%')");
        platform.update("DELETE FROM plt_account WHERE username LIKE 'it.rule.%'");
        platform.update(
                "DELETE FROM plt_role_menu WHERE role_id IN"
                        + " (SELECT id FROM plt_role WHERE code LIKE 'CUSTOM\\_%')");
        platform.update(
                "DELETE FROM plt_account_role WHERE role_id IN"
                        + " (SELECT id FROM plt_role WHERE code LIKE 'CUSTOM\\_%')");
        platform.update("DELETE FROM plt_role WHERE code LIKE 'CUSTOM\\_%'");
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM aud_platform_access_log WHERE action LIKE 'VIEW_RULE%'"
                        + " OR action IN ('VIEW_ENTER', 'VIEW_EXIT')");
    }

    // ---------------------------------------------------------------- 1. 默认规则（PLT-VIEW-008）

    @Test
    @Order(1)
    void seededDefaultRulesPresent() {
        String token = login();
        Map<?, ?> response = get("/api/platform/view-rules", token);
        assertEquals(0, codeOf(response), String.valueOf(response.get("message")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rules = listOf(response.get("data"));

        // 初始默认清单（种子 V109）：全局 1 + 模块 3 + 字段 12
        assertTrue(rules.size() >= 16, "默认规则齐备，实际 " + rules.size());
        Map<String, Map<String, Object>> byCode = byCode(rules);
        assertTrue(byCode.containsKey("VR-GLOBAL-DEFAULT"), "全局默认规则");
        assertEquals("PLAIN", byCode.get("VR-GLOBAL-DEFAULT").get("visibility"), "全局默认：可见、只读、禁止导出");
        assertEquals("READONLY", byCode.get("VR-GLOBAL-DEFAULT").get("operation"));
        assertEquals(false, byCode.get("VR-GLOBAL-DEFAULT").get("allowExport"));
        assertEquals(
                List.of("IMPLEMENTATION", "SUPPORT"),
                byCode.get("VR-FOLLOWUP-RO").get("roleCodes"),
                "业务数据只读默认绑实施 + 客服（OQ-99 布局）");
        assertEquals(
                List.of("PLATFORM_ADMIN"),
                byCode.get("VR-FOLLOWUP-ADMIN-EDIT").get("roleCodes"),
                "平台管理员预置可编辑规则（PLT-VIEW-008 已确认）");
        assertEquals("EDITABLE", byCode.get("VR-FOLLOWUP-ADMIN-EDIT").get("operation"));
        assertEquals("MASKED", byCode.get("VR-FIELD-PATIENT-NAME").get("visibility"), "敏感字段默认脱敏");
        assertEquals("name", byCode.get("VR-FIELD-PATIENT-NAME").get("maskRule"));
        assertEquals(
                "HIDDEN", byCode.get("VR-FIELD-REPORT-ORIGINAL").get("visibility"), "原件附件默认隐藏");
        assertEquals(
                "BLINDED", byCode.get("VR-FIELD-RCT-GROUPING").get("visibility"), "RCT 设盲字段默认设盲");

        // 层级过滤
        Map<?, ?> modules = get("/api/platform/view-rules?layer=MODULE", token);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> moduleRules = listOf(modules.get("data"));
        assertEquals(3, moduleRules.size(), "模块层默认规则 3 条");

        // 目录（FIELD 层引用与预览用）
        Map<?, ?> catalog = get("/api/platform/view-rules/catalog", token);
        assertEquals(0, codeOf(catalog));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fields = listOf(dataOf(catalog).get("fields"));
        assertTrue(fields.size() >= 12, "字段目录（5 患者 + 2 用户 + 2 原件录音 + 3 RCT）");
        @SuppressWarnings("unchecked")
        List<String> maskRules = (List<String>) dataOf(catalog).get("maskRules");
        assertTrue(maskRules.containsAll(List.of("name", "phone", "idCard", "generic")));
    }

    // ---------------------------------------------------------------- 2. 配置（PLT-VIEW-007）

    @Test
    @Order(2)
    void createUpdateAndValidateRules() {
        String token = login();

        // 校验失败：层级形状、脱敏码、引用存在性
        assertEquals(40001, codeOf(post(Map.of("name", "缺租户（示例）", "layer", "TENANT"), token)));
        assertEquals(
                40001,
                codeOf(
                        post(
                                Map.of(
                                        "name",
                                        "脱敏缺码（示例）",
                                        "layer",
                                        "FIELD",
                                        "resource",
                                        "patient",
                                        "field",
                                        "phone",
                                        "visibility",
                                        "MASKED",
                                        "operation",
                                        "READONLY"),
                                token)));
        assertEquals(
                40001,
                codeOf(
                        post(
                                Map.of(
                                        "name",
                                        "未知模块（示例）",
                                        "layer",
                                        "MODULE",
                                        "moduleCode",
                                        "nirvana",
                                        "visibility",
                                        "PLAIN",
                                        "operation",
                                        "READONLY"),
                                token)));
        assertEquals(
                40001,
                codeOf(
                        post(
                                Map.of(
                                        "name",
                                        "未知租户（示例）",
                                        "layer",
                                        "TENANT",
                                        "tenantId",
                                        999999L,
                                        "visibility",
                                        "PLAIN",
                                        "operation",
                                        "READONLY"),
                                token)));
        assertEquals(
                40001,
                codeOf(
                        post(
                                Map.of(
                                        "name",
                                        "未知角色（示例）",
                                        "layer",
                                        "GLOBAL",
                                        "roleIds",
                                        List.of(999999L),
                                        "visibility",
                                        "PLAIN",
                                        "operation",
                                        "READONLY"),
                                token)),
                "适用角色须存在且启用");

        // 新建租户层规则（demob 排障临时放宽操作，后续预览用例消费）
        Map<?, ?> created =
                post(
                        Map.of(
                                "name",
                                "demob 排障放宽（示例）",
                                "layer",
                                "TENANT",
                                "tenantId",
                                demobId,
                                "visibility",
                                "PLAIN",
                                "operation",
                                "EDITABLE",
                                "allowExport",
                                false,
                                "status",
                                "ACTIVE"),
                        token);
        assertEquals(0, codeOf(created), String.valueOf(created.get("message")));
        long ruleId = ((Number) dataOf(created).get("id")).longValue();
        createdRuleIds.add(ruleId);
        String code = (String) dataOf(created).get("code");
        assertTrue(code.matches("VR-\\d{5}"), "规则编号服务端生成：" + code);
        assertEquals(1, ((Number) dataOf(created).get("version")).intValue());
        assertEquals(
                1,
                platformDirect()
                        .queryForObject(
                                "SELECT COUNT(*) FROM aud_platform_access_log WHERE action ="
                                        + " 'VIEW_RULE_CREATE' AND object_id = ?",
                                Integer.class,
                                code),
                "新建写平台访问审计 VIEW_RULE_CREATE");

        // 维护（改名 + 停用）：版本 +1，审计 VIEW_RULE_DISABLE
        Map<?, ?> updated =
                put(
                        "/api/platform/view-rules/" + ruleId,
                        Map.of(
                                "name",
                                "demob 排障放宽（示例）",
                                "layer",
                                "TENANT",
                                "tenantId",
                                demobId,
                                "visibility",
                                "PLAIN",
                                "operation",
                                "EDITABLE",
                                "allowExport",
                                false,
                                "status",
                                "DISABLED"),
                        token);
        assertEquals(0, codeOf(updated), String.valueOf(updated.get("message")));
        assertEquals(2, ((Number) dataOf(updated).get("version")).intValue(), "版本每次变更 +1");
        assertEquals("DISABLED", dataOf(updated).get("status"));
        assertEquals(
                1,
                platformDirect()
                        .queryForObject(
                                "SELECT COUNT(*) FROM aud_platform_access_log WHERE action ="
                                        + " 'VIEW_RULE_DISABLE' AND object_id = ?",
                                Integer.class,
                                code),
                "停用写平台访问审计 VIEW_RULE_DISABLE");

        // 恢复生效（预览用例要用）
        Map<?, ?> enabled =
                put(
                        "/api/platform/view-rules/" + ruleId,
                        Map.of(
                                "name",
                                "demob 排障放宽（示例）",
                                "layer",
                                "TENANT",
                                "tenantId",
                                demobId,
                                "visibility",
                                "PLAIN",
                                "operation",
                                "EDITABLE",
                                "allowExport",
                                false,
                                "status",
                                "ACTIVE"),
                        token);
        assertEquals(0, codeOf(enabled));
        assertEquals(3, ((Number) dataOf(enabled).get("version")).intValue());

        // 未知规则 40400
        assertEquals(
                40400,
                codeOf(
                        put(
                                "/api/platform/view-rules/999999",
                                Map.of(
                                        "name",
                                        "不存在的（示例）",
                                        "layer",
                                        "GLOBAL",
                                        "visibility",
                                        "PLAIN",
                                        "operation",
                                        "READONLY"),
                                token)));
    }

    // ---------------------------------------------------------------- 3. 优先级与效果预览（PLT-VIEW-009，验收）

    @Test
    @Order(3)
    void previewMatchesResolverAndAppliesPriority() {
        String token = login();
        long adminRole = roleIdByCode("PLATFORM_ADMIN");
        long supportRole = roleIdByCode("SUPPORT");
        long opsRole = roleIdByCode("OPERATIONS");

        // 平台管理员 + demoa：followup 可编辑（预置压全局默认）、base 只读、字段默认效果
        Map<?, ?> adminPreview =
                dataOf(
                        post(
                                Map.of("tenantId", demoaId, "roleIds", List.of(adminRole)),
                                "/api/platform/view-rules/preview",
                                token));
        assertEquals("demoa", adminPreview.get("tenantCode"));
        Map<String, Map<String, Object>> modules =
                byModuleCode(listOf(adminPreview.get("modules")));
        assertEquals(
                "EDITABLE",
                modules.get("followup").get("operation"),
                "平台管理员预置可编辑（模块层压全局默认，PLT-VIEW-008）");
        assertEquals("READONLY", modules.get("base").get("operation"), "配置类默认只读");
        assertFalse((Boolean) modules.get("followup").get("allowExport"), "默认禁止导出");
        assertTrue((Boolean) modules.get("followup").get("opened"), "demoa 已开通 followup");
        Map<String, Map<String, Object>> fields = byKey(listOf(adminPreview.get("fields")));
        assertEquals("MASKED", fields.get("patient.name").get("visibility"));
        assertEquals("name", fields.get("patient.name").get("maskRule"));
        assertEquals("HIDDEN", fields.get("report").get("visibility"), "原件附件隐藏");
        assertEquals("BLINDED", fields.get("rct.grouping").get("visibility"), "RCT 字段设盲");
        assertEquals("MASKED", fields.get("user.phone").get("visibility"), "配置类用户手机号脱敏");

        // 客服支持 + demoa：同层只读压过平台管理员可编辑（多角色更严，spec 已确认）
        Map<?, ?> supportPreview =
                dataOf(
                        post(
                                Map.of("tenantId", demoaId, "roleIds", List.of(supportRole)),
                                "/api/platform/view-rules/preview",
                                token));
        assertEquals(
                "READONLY",
                byModuleCode(listOf(supportPreview.get("modules")))
                        .get("followup")
                        .get("operation"),
                "实施 / 客服业务数据只读");
        Map<?, ?> bothPreview =
                dataOf(
                        post(
                                Map.of(
                                        "tenantId",
                                        demoaId,
                                        "roleIds",
                                        List.of(adminRole, supportRole)),
                                "/api/platform/view-rules/preview",
                                token));
        assertEquals(
                "READONLY",
                byModuleCode(listOf(bothPreview.get("modules"))).get("followup").get("operation"),
                "同一账号多角色同层命中：只读 > 可编辑（已确认）");

        // 租户层压全局默认：demob 的租户层可编辑规则让「无模块层命中」的运维角色在 followup 变可编辑；
        // demoa 无该规则仍只读（租户选择器收窄）
        Map<?, ?> opsDemob =
                dataOf(
                        post(
                                Map.of("tenantId", demobId, "roleIds", List.of(opsRole)),
                                "/api/platform/view-rules/preview",
                                token));
        assertEquals(
                "EDITABLE",
                byModuleCode(listOf(opsDemob.get("modules"))).get("followup").get("operation"),
                "租户层（demob 放宽）压全局默认只读");
        Map<?, ?> opsDemoa =
                dataOf(
                        post(
                                Map.of("tenantId", demoaId, "roleIds", List.of(opsRole)),
                                "/api/platform/view-rules/preview",
                                token));
        assertEquals(
                "READONLY",
                byModuleCode(listOf(opsDemoa.get("modules"))).get("followup").get("operation"),
                "租户层规则只作用于 demob，demoa 回落全局默认");
        // 模块层压租户层：客服在 demob 上有模块层只读命中（VR-FOLLOWUP-RO 空租户选择器）
        Map<?, ?> supportDemob =
                dataOf(
                        post(
                                Map.of("tenantId", demobId, "roleIds", List.of(supportRole)),
                                "/api/platform/view-rules/preview",
                                token));
        assertEquals(
                "READONLY",
                byModuleCode(listOf(supportDemob.get("modules"))).get("followup").get("operation"),
                "模块层压租户层（字段 > 模块 > 租户 > 全局默认）");

        // 验收核心：预览输出 == 同一 ACTIVE 规则集喂 ViewRuleResolver 的解析（预览与实际执行一致）
        List<com.crdp.server.platform.ViewRuleService.RuleRow> active =
                viewRuleService.activeRules();
        List<com.crdp.server.platform.ViewRuleResolver.Rule> resolverRules =
                active.stream()
                        .map(com.crdp.server.platform.ViewRuleService.RuleRow::toResolverRule)
                        .toList();
        for (Map<String, Object> field : listOf(adminPreview.get("fields"))) {
            String resource = (String) field.get("resource");
            String fieldCode = (String) field.get("field");
            String moduleCode = (String) field.get("moduleCode");
            com.crdp.server.platform.ViewRuleResolver.Effect effect =
                    ViewRuleResolver.resolve(
                            resolverRules,
                            Set.of("PLATFORM_ADMIN"),
                            demoaId,
                            moduleCode,
                            resource,
                            fieldCode);
            assertEquals(
                    effect.visibility(),
                    field.get("visibility"),
                    "字段 " + resource + "." + fieldCode);
            assertEquals(effect.maskRule(), field.get("maskRule"));
            assertEquals(effect.operation(), field.get("operation"));
            assertEquals(effect.allowExport(), field.get("allowExport"));
        }
        for (Map<String, Object> module : listOf(adminPreview.get("modules"))) {
            String moduleCode = (String) module.get("moduleCode");
            com.crdp.server.platform.ViewRuleResolver.Effect effect =
                    ViewRuleResolver.resolve(
                            resolverRules,
                            Set.of("PLATFORM_ADMIN"),
                            demoaId,
                            moduleCode,
                            null,
                            null);
            assertEquals(effect.visibility(), module.get("visibility"), "模块 " + moduleCode);
            assertEquals(effect.operation(), module.get("operation"));
            assertEquals(effect.allowExport(), module.get("allowExport"));
        }

        // 预览参数校验：角色必选、引用存在
        assertEquals(
                40001,
                codeOf(
                        post(
                                Map.of("tenantId", demoaId, "roleIds", List.of()),
                                "/api/platform/view-rules/preview",
                                token)));
        assertEquals(
                40400,
                codeOf(
                        post(
                                Map.of("tenantId", 999999L, "roleIds", List.of(adminRole)),
                                "/api/platform/view-rules/preview",
                                token)));
        assertEquals(
                40001,
                codeOf(
                        post(
                                Map.of("tenantId", demoaId, "roleIds", List.of(999999L)),
                                "/api/platform/view-rules/preview",
                                token)));
    }

    // ---------------------------------------------------------------- 4. 生效规则版本（进入盖章）

    @Test
    @Order(4)
    void enterStampsActiveRuleVersionHash() {
        String token = login();
        Map<?, ?> enterResponse =
                post(
                        Map.of(
                                "tenantId",
                                demoaId,
                                "purpose",
                                "CONFIG_CHECK",
                                "note",
                                "核对视角规则（示例）"),
                        "/api/platform/tenant-view/sessions",
                        token);
        assertEquals(0, codeOf(enterResponse), String.valueOf(enterResponse.get("message")));
        Map<?, ?> entered = dataOf(enterResponse);
        String sessionNo = (String) entered.get("sessionNo");
        String expected = viewRuleService.activeRuleVersion();
        String stamped =
                platformDirect()
                        .queryForObject(
                                "SELECT rule_version FROM plt_view_session WHERE session_no = ?",
                                String.class,
                                sessionNo);
        assertNotEquals("DEFAULT", stamped, "E20-T02 起写实值（V50 预留列）");
        assertEquals(expected, stamped, "进入时盖 ACTIVE 规则集内容哈希");
        assertTrue(stamped.matches("[0-9a-f]{16}"), "SHA-256 前 16 位十六进制");

        // 规则一变版本即换值：新建一条规则后再进入
        Map<?, ?> created =
                post(
                        Map.of(
                                "name",
                                "版本探针（示例）",
                                "layer",
                                "GLOBAL",
                                "visibility",
                                "PLAIN",
                                "operation",
                                "READONLY"),
                        token);
        assertEquals(0, codeOf(created));
        createdRuleIds.add(((Number) dataOf(created).get("id")).longValue());
        delete("/api/platform/tenant-view/sessions/" + entered.get("sessionId"), token);
        Map<?, ?> reenterResponse =
                post(
                        Map.of("tenantId", demoaId, "purpose", "CONFIG_CHECK", "note", "再核对一次（示例）"),
                        "/api/platform/tenant-view/sessions",
                        token);
        assertEquals(0, codeOf(reenterResponse), String.valueOf(reenterResponse.get("message")));
        Map<?, ?> reentered = dataOf(reenterResponse);
        String restamped =
                platformDirect()
                        .queryForObject(
                                "SELECT rule_version FROM plt_view_session WHERE session_no = ?",
                                String.class,
                                reentered.get("sessionNo"));
        assertEquals(viewRuleService.activeRuleVersion(), restamped);
        assertNotEquals(stamped, restamped, "规则变化后新会话的生效规则版本换值");
        assertEquals(
                0,
                codeOf(
                        delete(
                                "/api/platform/tenant-view/sessions/" + reentered.get("sessionId"),
                                token)));
    }

    // ---------------------------------------------------------------- 5. 权限边界

    @Test
    @Order(5)
    void permissionBoundary() {
        String adminToken = login();
        Map<?, ?> created =
                post(
                        Map.of("name", "无规则权限角色（示例）", "menuIds", List.of(100L, 111L)),
                        "/api/platform/roles",
                        adminToken);
        assertEquals(0, codeOf(created), String.valueOf(created.get("message")));
        long roleId = ((Number) dataOf(created).get("id")).longValue();
        createAccount(adminToken, "it.rule.reader", "规则只读账号（示例）", roleId);
        createdUsernames.add("it.rule.reader");
        String token = loginWithForcedChange("it.rule.reader");

        assertEquals(
                40300,
                codeOf(get("/api/platform/view-rules", token)),
                "无 platform:view:rule:view 的角色不可看规则");
        assertEquals(
                40300,
                codeOf(
                        post(
                                Map.of(
                                        "name",
                                        "越权新建（示例）",
                                        "layer",
                                        "GLOBAL",
                                        "visibility",
                                        "PLAIN",
                                        "operation",
                                        "READONLY"),
                                token)),
                "无 platform:view:rule:edit 的角色不可新建规则");
        assertEquals(
                40300,
                codeOf(
                        post(
                                Map.of("tenantId", demoaId, "roleIds", List.of(roleId)),
                                "/api/platform/view-rules/preview",
                                token)),
                "预览同样受查看权限约束");
        assertNotNull(adminToken);
    }

    // ---------------------------------------------------------------- 辅助

    private String login() {
        Map<?, ?> response =
                post(
                        Map.of("username", ADMIN_USERNAME, "password", ADMIN_PASSWORD),
                        "/api/platform/auth/login",
                        null);
        assertEquals(0, codeOf(response), "登录成功：" + String.valueOf(response.get("message")));
        return (String) dataOf(response).get("token");
    }

    private String loginWithForcedChange(String username) {
        Map<?, ?> first =
                post(
                        Map.of("username", username, "password", NEW_PASSWORD + "A"),
                        "/api/platform/auth/login",
                        null);
        assertEquals(0, codeOf(first), String.valueOf(first.get("message")));
        String token = (String) dataOf(first).get("token");
        Map<?, ?> changed =
                post(
                        Map.of(
                                "oldPassword",
                                NEW_PASSWORD + "A",
                                "newPassword",
                                NEW_PASSWORD + "B"),
                        "/api/platform/auth/password",
                        token);
        assertEquals(0, codeOf(changed), "首次改密成功：" + String.valueOf(changed.get("message")));
        return token;
    }

    private long createAccount(
            String adminToken, String username, String displayName, long roleId) {
        Map<?, ?> response =
                post(
                        Map.of(
                                "username",
                                username,
                                "displayName",
                                displayName,
                                "phone",
                                "139000077" + (10 + createdUsernames.size() % 80),
                                "password",
                                NEW_PASSWORD + "A",
                                "roleIds",
                                List.of(roleId)),
                        "/api/platform/accounts",
                        adminToken);
        assertEquals(0, codeOf(response), "建号成功：" + String.valueOf(response.get("message")));
        return ((Number) dataOf(response).get("id")).longValue();
    }

    private Map<?, ?> post(Map<String, Object> body, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    /** 新建规则的默认路径（POST /api/platform/view-rules）。 */
    private Map<?, ?> post(Map<String, Object> body, String token) {
        return post(body, "/api/platform/view-rules", token);
    }

    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private Map<?, ?> put(String path, Map<String, Object> body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    private Map<?, ?> delete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class)
                .getBody();
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<String, Object> dataOf(Map<?, ?> response) {
        return response.get("data") == null ? Map.of() : (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOf(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static Map<String, Map<String, Object>> byCode(List<Map<String, Object>> rows) {
        return byField(rows, "code");
    }

    /** 模块效果按 moduleCode 索引。 */
    private static Map<String, Map<String, Object>> byModuleCode(List<Map<String, Object>> rows) {
        return byField(rows, "moduleCode");
    }

    private static Map<String, Map<String, Object>> byField(
            List<Map<String, Object>> rows, String keyField) {
        java.util.LinkedHashMap<String, Map<String, Object>> map = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            map.put((String) row.get(keyField), row);
        }
        return map;
    }

    private static Map<String, Map<String, Object>> byKey(List<Map<String, Object>> rows) {
        java.util.LinkedHashMap<String, Map<String, Object>> map = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String key =
                    (String) row.get("resource")
                            + (row.get("field") == null ? "" : "." + row.get("field"));
            map.put(key, row);
        }
        return map;
    }

    private JdbcTemplate platformDirect() {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        return new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));
    }

    private long tenantIdByCode(String code) {
        return platformDirect()
                .queryForObject("SELECT id FROM plt_tenant WHERE code = ?", Long.class, code);
    }

    private long roleIdByCode(String code) {
        return platformDirect()
                .queryForObject("SELECT id FROM plt_role WHERE code = ?", Long.class, code);
    }

    private void deleteKeys(String pattern) {
        Set<String> keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }
}
