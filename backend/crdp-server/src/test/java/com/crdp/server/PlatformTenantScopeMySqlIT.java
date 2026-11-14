package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * E05-T07 端到端（验收原文：收回范围后租户角色同步收回）。场景按序：
 *
 * <ol>
 *   <li>授权范围视图（PLT-SCOPE-001、002、004）：base 固定能力可分配；模板默认项可分配；敏感按钮默认 「已开通未放开」；
 *   <li>收回（PLT-SCOPE-005、006、008）：影响分析列出受影响角色与用户数 → 保存即从租户库 sys_role_menu
 *       移除超出项、受影响用户权限版本递增——医院端同一在线会话下一次请求即失去菜单与权限码，写变更记录与审计；
 *   <li>敏感权限（PLT-SCOPE-004）：按租户放开（原因必填）后可授予角色并进入会话权限码；再收回即同步收回；
 *   <li>校验与权限（第 6 节）：base 固定能力不可收回、授权范围外的项 40001、无权限 40300；
 *   <li>模板（PLT-SCOPE-003）：默认项收缩对无覆盖租户立即生效；敏感项不能预置进模板；
 *   <li>模块关闭（PLT-SCOPE-001）：授权范围自动收缩，未开通模块的项不能进入可分配范围。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清（恢复模板、覆盖、敏感清单、 租户库角色授权与模块状态，删除本 IT
 * 的变更记录 / 审计 / 临时角色与账号）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformTenantScopeMySqlIT {

    private static final String DEMO_PASSWORD = "Demo@2026";
    private static final long TENANT_A = 1L; // demoa，种子已开通 followup
    private static final long TENANT_B = 2L; // demob

    /** 期望可分配全集（base 10/30/31 + followup 模板默认 20/21/23，收回 22 随访任务）。 */
    /**
     * base 固定能力全集（V100 + V10/V11 的按钮 311/312/313 + V23 的按钮 314 用户维护 + V29 的按钮 315 批量导入用户 + V19 的按钮
     * 331 + V21 的菜单 34 机构设置 + V22 的按钮 321 维护角色与授权 + V24 的按钮 341/342 租户参数与字典维护 + V28 的菜单 35 审计中心与按钮
     * 351/352 审计导出与审批；343 维护租户 Webhook 为 E09-T06 新增 base 按钮（平台库 V47）），收回请求必须原样带上。
     */
    private static final List<Object> BASE_ALL =
            List.of(
                    10L, 30L, 31L, 32L, 33L, 34L, 35L, 311L, 312L, 313L, 314L, 315L, 321L, 331L,
                    341L, 342L, 343L, 351L, 352L);

    /**
     * 期望可分配全集（base 全部 + followup 模板默认 20/21/23/36/37/231，收回 22 随访任务）。 36 通知设置为 E09-T04 新增的非敏感
     * followup 菜单（V32 进授权范围模板，与 V102 同口径）；231 上传按钮为 E10-T01 新增的非敏感 followup 项（V33 同口径）； 37 字段字典为
     * E11-T01 新增的非敏感 followup 菜单（V35 同口径）；214 编辑患者资料按钮为 E13-T03 新增（平台库 V38 同口径）； 215 患者详情隐藏菜单为
     * E13-T05 新增（平台库 V39 同口径）。
     */
    private static final List<Object> WITHOUT_TASK =
            java.util.stream.Stream.concat(
                            BASE_ALL.stream(),
                            // 218 审批患者导出随 E13-T09 进模板（V41）；
                            // 24/25/219/220/221 随访计划菜单与按钮随 E14-T01 进模板（V42）；
                            // 222 录入随访记录按钮随 E25-T01 进模板（V43）；
                            // 224 批量指定责任医生按钮与 225 批量入组隐藏路由随 E13-T07 进模板（V44）；
                            // 226 锁定/解锁基线按钮与 227 基线分节编辑隐藏路由随 E13-T02 进模板（V48）；
                            // 39 报告单设置为 E16-T01 新增的非敏感 followup 菜单（V53 同口径）；
                            // 233 删除报告单按钮为 E16-T09 新增（平台库 V55 同口径，进授权范围模板）；
                            // 40 对比规则为 E17-T01 新增的非敏感 followup 菜单（V57 同口径）；
                            // 353 导入向导隐藏路由为 E18-T06 新增（平台库 V58 同口径，进授权范围模板）；
                            // 234 首页统计卡片按钮为 E19-T02 新增（平台库 V59 同口径，进授权范围模板）；
                            // 41 首页卡片配置为 E19-T06 新增的非敏感 followup 菜单（平台库 V60 同口径）；
                            // 42 统计页为 E19-T07 新增的非敏感 followup 菜单（平台库 V61 同口径，进授权范围模板）
                            // 228 编辑植入记录按钮为 E21-T01 新增（平台库 V69 同口径，进授权范围模板；
                            // 挂患者档案 21 下，收回「随访任务」不连带收回）；
                            // 354 待归档区为 E21-T16 新增的非敏感 followup 菜单（平台库 V74 同口径，
                            // 进授权范围模板；挂随访目录 20 下，收回「随访任务」不连带收回）
                            List.of(
                                    20L, 21L, 23L, 24L, 25L, 36L, 37L, 38L, 39L, 40L, 41L, 42L,
                                    213L, 214L, 215L, 216L, 218L, 219L, 220L, 221L, 222L, 224L,
                                    225L, 226L, 227L, 228L, 231L, 233L, 234L, 353L, 354L)
                                    .stream())
                    .toList();

    private static JdbcTemplate platform;
    private static JdbcTemplate root;
    private static boolean viewerCreated;

    @Autowired private TestRestTemplate rest;

    private static JdbcTemplate connect() {
        if (platform == null) {
            String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
            String params = "?useSSL=false&allowPublicKeyRetrieval=true";
            platform =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    base + "/crdp_platform" + params,
                                    "crdp_app",
                                    "DevOnly#app2026"));
            root =
                    new JdbcTemplate(
                            new DriverManagerDataSource(
                                    base + "/" + params, "root", "DevOnly#root2026"));
        }
        return platform;
    }

    @AfterAll
    static void cleanUp() {
        if (platform == null) {
            return;
        }
        // 覆盖与变更记录：回到模板默认
        platform.update("DELETE FROM plt_tenant_scope");
        platform.update("DELETE FROM plt_scope_log");
        // 模板与敏感清单回到 V18 种子
        platform.update("DELETE FROM plt_scope_template");
        platform.update(
                "INSERT INTO plt_scope_template (module_code, menu_id)"
                        + " SELECT 'followup', m.id FROM plt_menu m"
                        + " WHERE m.side = 'HOSPITAL' AND m.status = 'ENABLED'"
                        + " AND m.module_code = 'followup'"
                        + " AND (m.perm_code IS NULL OR m.perm_code NOT IN"
                        + " (SELECT perm_code FROM plt_sensitive_perm))");
        platform.update("DELETE FROM plt_sensitive_perm");
        // 与迁移后的种子一致（V18 七项 + V41 的 followup:patient:delete + V72 的
        // followup:record:edit-submitted，E21-T11 / C-FU-026）：漏掉 217 的码会让上面的
        // 模板重建把 217 当非敏感项收回模板，下一次运行 revoke 用例 removed 变 2 条而失败（本地持久库才会踩到）；
        // 漏掉 V72 的 229 同理——229 进模板后 WITHOUT_TASK 不含它，removed 会多出第三条（E23-T22 本机重跑实测）
        platform.update(
                "INSERT INTO plt_sensitive_perm (perm_code, name) VALUES"
                        + " ('followup:patient:plain', '敏感字段明文查看（证件号、手机号、住址等原值）'),"
                        + " ('followup:patient:export', '患者与业务数据导出（含批量导出）'),"
                        + " ('followup:record:delete', '删除业务数据（患者、随访记录等）'),"
                        + " ('followup:patient:delete', '删除患者（软删除，数据长期保留）'),"
                        + " ('followup:record:edit-submitted', '修改已提交的随访记录（留字段级历史，Q17）'),"
                        + " ('followup:scan:download', '原件与附件下载（报告单、电子化资料、通话录音）'),"
                        + " ('tenant:audit:export', '审计日志导出'),"
                        + " ('tenant:user:export', '用户导出（含手机号等个人信息）'),"
                        + " ('followup:notice:batch', '批量群发通知（短信成本与骚扰风险）')");
        // 本 IT 的审计与租户 2 的模块关闭痕迹
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM plt_audit_log WHERE event_type IN"
                        + " ('SCOPE_UPDATE', 'SCOPE_TEMPLATE_UPDATE', 'SENSITIVE_PERM_UPDATE')");
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM plt_audit_log WHERE event_type = 'MODULE_CLOSE' AND target LIKE '%demob%'");
        platform.update("DELETE FROM plt_tenant_module_log WHERE tenant_id = 2");
        platform.update(
                "UPDATE plt_tenant_module SET status = 'OPEN', closed_at = NULL, reason = NULL,"
                        + " operator_id = NULL WHERE tenant_id = 2 AND module_code = 'followup'");
        // 租户库角色授权恢复种子：22 重新授予两个角色，211 的临时授予清掉
        root.update(
                "INSERT IGNORE INTO t_demoa.sys_role_menu (role_id, menu_id)"
                        + " SELECT r.id, 22 FROM t_demoa.sys_role r"
                        + " WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')");
        // E13-T01：新建患者（213）与 22 同为角色默认授权，收回测试同样会清掉
        root.update(
                "INSERT IGNORE INTO t_demoa.sys_role_menu (role_id, menu_id)"
                        + " SELECT r.id, 213 FROM t_demoa.sys_role r"
                        + " WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')");
        // E13-T03：编辑患者资料按钮（214）同上（followup V39 / 种子 V113 的默认授权）
        root.update(
                "INSERT IGNORE INTO t_demoa.sys_role_menu (role_id, menu_id)"
                        + " SELECT r.id, 214 FROM t_demoa.sys_role r"
                        + " WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')");
        // E13-T05：患者详情隐藏菜单（215）同上（followup V41 / 种子 V114 的默认授权）
        root.update(
                "INSERT IGNORE INTO t_demoa.sys_role_menu (role_id, menu_id)"
                        + " SELECT r.id, 215 FROM t_demoa.sys_role r"
                        + " WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')");
        // E13-T08：转病区按钮（216）同上（followup V43 / 种子 V115 的默认授权）
        root.update(
                "INSERT IGNORE INTO t_demoa.sys_role_menu (role_id, menu_id)"
                        + " SELECT r.id, 216 FROM t_demoa.sys_role r"
                        + " WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')");
        // E06-T05 模板角色的默认菜单授权恢复（种子 V106 的下发态，收回测试会清掉 sys_role_menu 行）
        root.update(
                "INSERT IGNORE INTO t_demoa.sys_role_menu (role_id, menu_id)"
                        + " SELECT r.id, tm.menu_id FROM t_demoa.sys_role r"
                        + " JOIN crdp_platform.plt_role_template t ON t.code = r.code"
                        + " JOIN crdp_platform.plt_role_template_menu tm ON tm.template_id = t.id"
                        + " WHERE r.role_type = 'TEMPLATE'");
        // 模块重开的兜底直授（OQ-46 ①）：TENANT_ADMIN 持有 followup 全部医院端菜单
        root.update(
                "INSERT IGNORE INTO t_demoa.sys_role_menu (role_id, menu_id)"
                        + " SELECT r.id, m.id FROM t_demoa.sys_role r JOIN crdp_platform.plt_menu m"
                        + " ON m.side = 'HOSPITAL' AND m.status = 'ENABLED'"
                        + " AND m.module_code = 'followup' WHERE r.code = 'TENANT_ADMIN'");
        root.update(
                "DELETE FROM t_demoa.sys_role_menu WHERE menu_id = 211 AND role_id IN"
                        + " (SELECT id FROM t_demoa.sys_role WHERE code = 'FOLLOWUP_DOCTOR')");
        if (viewerCreated) {
            platform.update(
                    "DELETE ar FROM plt_account_role ar JOIN plt_account a ON a.id = ar.account_id"
                            + " WHERE a.username = 'platform.scope.viewer.it'");
            platform.update("DELETE FROM plt_account WHERE username = 'platform.scope.viewer.it'");
            platform.update(
                    "DELETE rm FROM plt_role_menu rm JOIN plt_role r ON r.id = rm.role_id"
                            + " WHERE r.code = 'PLATFORM_TENANT_SCOPE_VIEWER_IT'");
            platform.update("DELETE FROM plt_role WHERE code = 'PLATFORM_TENANT_SCOPE_VIEWER_IT'");
        }
    }

    // ---------------------------------------------------------------- 基础设施

    private String loginHospital(String tenantCode, String username) {
        return token(
                post(
                        Map.of(
                                "tenantCode", tenantCode,
                                "username", username,
                                "password", DEMO_PASSWORD),
                        "/api/hospital/auth/login",
                        null));
    }

    private String loginPlatform(String username) {
        return token(
                post(
                        Map.of("username", username, "password", DEMO_PASSWORD),
                        "/api/platform/auth/login",
                        null));
    }

    /** 非零响应打印出来，便于定位断言失败的原因。 */
    private static void dumpOnFailure(String path, Map<String, Object> response) {
        if (code(response) != 0) {
            System.out.println("[IT] " + path + " -> " + response);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> post(Map<String, Object> body, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        Map<String, Object> response =
                (Map<String, Object>)
                        rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class)
                                .getBody();
        dumpOnFailure(path, response);
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> request(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<?> entity =
                body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        Map<String, Object> response =
                (Map<String, Object>)
                        rest.exchange(URI.create(path), method, entity, Map.class).getBody();
        dumpOnFailure(path, response);
        return response;
    }

    private static int code(Map<?, ?> response) {
        Object value = response == null ? null : response.get("code");
        return value == null ? -1 : Integer.parseInt(value.toString());
    }

    private static String token(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? null : ((Map<?, ?>) data).get("token").toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<?, ?> response) {
        return (Map<String, Object>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> dataItems(Map<?, ?> response) {
        return (List<Map<String, Object>>) response.get("data");
    }

    private static Map<String, Object> scopeBody(
            List<Object> menuIds, Map<String, String> sensitiveReasons, String reason) {
        return Map.of(
                "assignableMenuIds", menuIds,
                "sensitiveReasons", sensitiveReasons,
                "reason", reason);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scopeModule(Map<?, ?> scopeView, String code) {
        return ((List<Map<String, Object>>) scopeView.get("modules"))
                .stream().filter(m -> code.equals(m.get("code"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scopeItem(Map<?, ?> module, long menuId) {
        return ((List<Map<String, Object>>) module.get("items"))
                .stream()
                        .filter(item -> ((Number) item.get("id")).longValue() == menuId)
                        .findFirst()
                        .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> templateItem(Map<?, ?> module, long menuId) {
        return ((List<Map<String, Object>>) module.get("items"))
                .stream()
                        .filter(item -> ((Number) item.get("menuId")).longValue() == menuId)
                        .findFirst()
                        .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static List<String> menuTitles(Map<?, ?> menusResponse) {
        List<String> titles = new ArrayList<>();
        for (Map<String, Object> node : dataItems(menusResponse)) {
            flatten(node, titles);
        }
        return titles;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, Object> node, List<String> titles) {
        Object meta = node.get("meta");
        if (meta instanceof Map<?, ?> metaMap && metaMap.get("title") != null) {
            titles.add(String.valueOf(metaMap.get("title")));
        }
        Object childNodes = node.get("children");
        if (childNodes instanceof List<?> list) {
            for (Object child : list) {
                flatten((Map<String, Object>) child, titles);
            }
        }
    }

    private void createViewer() {
        if (viewerCreated) {
            return;
        }
        // 上一次运行中断可能残留，先清理保证幂等
        platform.update(
                "DELETE ar FROM plt_account_role ar JOIN plt_account a ON a.id = ar.account_id"
                        + " WHERE a.username = 'platform.scope.viewer.it'");
        platform.update("DELETE FROM plt_account WHERE username = 'platform.scope.viewer.it'");
        platform.update(
                "DELETE rm FROM plt_role_menu rm JOIN plt_role r ON r.id = rm.role_id"
                        + " WHERE r.code = 'PLATFORM_TENANT_SCOPE_VIEWER_IT'");
        platform.update("DELETE FROM plt_role WHERE code = 'PLATFORM_TENANT_SCOPE_VIEWER_IT'");
        String passwordHash =
                new org.springframework.security.crypto.argon2.Argon2PasswordEncoder(
                                16, 32, 1, 65536, 3)
                        .encode(DEMO_PASSWORD);
        platform.update(
                "INSERT INTO plt_role (code, name, built_in, status)"
                        + " VALUES ('PLATFORM_TENANT_SCOPE_VIEWER_IT', '租户授权范围只读（IT）', 0, 'ENABLED')");
        // 只授租户管理页（platform:tenant:view），不含 133/134（scope:view / scope:edit）
        platform.update(
                "INSERT INTO plt_role_menu (role_id, menu_id)"
                        + " SELECT r.id, 111 FROM plt_role r WHERE r.code ="
                        + " 'PLATFORM_TENANT_SCOPE_VIEWER_IT'");
        platform.update(
                "INSERT INTO plt_account (username, display_name, password_hash, status)"
                        + " VALUES ('platform.scope.viewer.it', '授权范围只读（IT 示例）', ?, 'ENABLED')",
                passwordHash);
        platform.update(
                "INSERT INTO plt_account_role (account_id, role_id)"
                        + " SELECT a.id, r.id FROM plt_account a, plt_role r"
                        + " WHERE a.username = 'platform.scope.viewer.it'"
                        + " AND r.code = 'PLATFORM_TENANT_SCOPE_VIEWER_IT'");
        viewerCreated = true;
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @Order(1)
    void scopeViewShowsThreeStates() {
        connect();
        Map<String, Object> scope =
                data(
                        request(
                                HttpMethod.GET,
                                "/api/platform/tenants/" + TENANT_A + "/scope",
                                loginPlatform("platform.admin"),
                                null));
        assertEquals("demoa", scope.get("tenantCode"));

        Map<String, Object> base = scopeModule(scope, "base");
        assertEquals(Boolean.TRUE, base.get("opened"));
        assertEquals(Boolean.TRUE, base.get("baseModule"));
        for (long menuId : new long[] {10, 30, 31}) {
            Map<String, Object> item = scopeItem(base, menuId);
            assertEquals("ASSIGNABLE", item.get("state"), "base 固定能力恒可分配");
            assertEquals(Boolean.TRUE, item.get("fixed"));
        }

        Map<String, Object> followup = scopeModule(scope, "followup");
        assertEquals(Boolean.TRUE, followup.get("opened"));
        assertEquals("ASSIGNABLE", scopeItem(followup, 21L).get("state"), "模板默认项可分配");
        assertEquals("ASSIGNABLE", scopeItem(followup, 22L).get("state"));
        // 敏感按钮默认不可分配（PLT-SCOPE-004）：已开通未放开 + 敏感标记
        Map<String, Object> plain = scopeItem(followup, 211L);
        assertEquals("NOT_RELEASED", plain.get("state"));
        assertEquals(Boolean.TRUE, plain.get("sensitive"));
        assertEquals(Boolean.FALSE, plain.get("defaultAssignable"));
        assertEquals("NOT_RELEASED", scopeItem(followup, 212L).get("state"));
    }

    @Test
    @Order(2)
    void revokeShrinksTenantRolesAndSessions() {
        connect();
        // 前置归一（本机持久库重跑用；CI 全新库天然满足）：补齐种子授予——22 六角色、
        // 223 隐藏路由（管理员与随访医生，followup V55 / 平台库种子 V106，E15-T02）；
        // E23-T04 起医院 A 预置角色也持 22（病区医生 / 随访员 / 科主任），病区医生与随访员另持 223；
        // E23-T18 起医院 B 预置角色（种子 V136，与 A 同构）也持 22 / 223；
        // E23-T22 起医院 C 预置角色（种子 V137）医生 / 随访员 / 科主任持 22，医生 / 随访员 / 护士另持 223。
        // 上一次运行到 Order(3) 会把 22/223 一并收回，重跑前先还原。
        root.update(
                """
                INSERT INTO t_demoa.sys_role_menu (role_id, menu_id)
                SELECT r.id, m.menu_id FROM t_demoa.sys_role r
                JOIN (SELECT 'TENANT_ADMIN' AS code, 223 AS menu_id
                      UNION ALL SELECT 'FOLLOWUP_DOCTOR', 223
                      UNION ALL SELECT 'C_DOCTOR', 223
                      UNION ALL SELECT 'C_FOLLOWUP_STAFF', 223
                      UNION ALL SELECT 'C_NURSE', 223
                      UNION ALL SELECT 'TENANT_ADMIN', 22
                      UNION ALL SELECT 'FOLLOWUP_DOCTOR', 22
                      UNION ALL SELECT 'FOLLOWUP_NURSE', 22
                      UNION ALL SELECT 'FOLLOWUP_DUTY_DOCTOR', 22
                      UNION ALL SELECT 'FOLLOWUP_RESEARCHER', 22
                      UNION ALL SELECT 'FOLLOWUP_CRC', 22
                      UNION ALL SELECT 'A_WARD_DOCTOR', 223
                      UNION ALL SELECT 'A_WARD_DOCTOR', 22
                      UNION ALL SELECT 'A_FOLLOWUP_NURSE', 223
                      UNION ALL SELECT 'A_FOLLOWUP_NURSE', 22
                      UNION ALL SELECT 'A_DIRECTOR', 22
                      UNION ALL SELECT 'B_WARD_DOCTOR', 223
                      UNION ALL SELECT 'B_WARD_DOCTOR', 22
                      UNION ALL SELECT 'B_FOLLOWUP_NURSE', 223
                      UNION ALL SELECT 'B_FOLLOWUP_NURSE', 22
                      UNION ALL SELECT 'B_DIRECTOR', 22
                      UNION ALL SELECT 'C_DOCTOR', 22
                      UNION ALL SELECT 'C_FOLLOWUP_STAFF', 22
                      UNION ALL SELECT 'C_DIRECTOR', 22) m ON m.code = r.code
                WHERE NOT EXISTS (SELECT 1 FROM t_demoa.sys_role_menu rm
                                  WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id)
                """);
        String platformToken = loginPlatform("platform.admin");
        String doctorToken = loginHospital("demoa", "doctor.demo");
        // 收回前：随访医生（种子角色授权 10/20/21/22）能看到随访任务，模块接口放行
        assertTrue(
                menuTitles(request(HttpMethod.GET, "/api/hospital/menus", doctorToken, null))
                        .contains("我的任务")); // E15-T02：菜单 22 改标题「我的任务」
        assertEquals(
                0, code(request(HttpMethod.GET, "/api/hospital/followup/ping", doctorToken, null)));

        // 影响分析（PLT-SCOPE-005）：将被移除的项、受影响角色（含权限码）与受影响用户数
        Map<String, Object> preview =
                data(
                        post(
                                scopeBody(WITHOUT_TASK, Map.of(), "按合同收回随访任务（示例）"),
                                "/api/platform/tenants/" + TENANT_A + "/scope/preview",
                                platformToken));
        List<Map<String, Object>> removed = (List<Map<String, Object>>) preview.get("removed");
        // E15-T02 起 22 下有隐藏路由 223（随访录入），收回父菜单连带收回
        assertEquals(2, removed.size(), "收回 22 连带其下隐藏路由 223（E15-T02 随访录入）");
        List<Long> removedIds =
                removed.stream()
                        .map(item -> ((Number) item.get("menuId")).longValue())
                        .sorted()
                        .toList();
        assertEquals(List.of(22L, 223L), removedIds);
        Map<?, ?> taskRemoved =
                removed.stream()
                        .filter(item -> ((Number) item.get("menuId")).longValue() == 22L)
                        .findFirst()
                        .orElseThrow();
        assertEquals("followup:task:view", taskRemoved.get("permCode"));
        List<Map<String, Object>> roles = (List<Map<String, Object>>) preview.get("affectedRoles");
        // E06-T05 起，随访模板（随访员 / 责任医生 / 研究者 / CRC）默认也持有 22；
        // E23-T04 起，医院 A 预置角色（病区医生 / 随访员 / 科主任）也持有 22；
        // E23-T18 起，医院 B 预置角色（种子 V136，与 A 同构）也持有 22；
        // E23-T22 起，医院 C 预置角色（种子 V137）医生 / 随访员 / 科主任持 22、医生 / 随访员 / 护士另持 223
        // （收回 22 连带 223，影响面取两菜单持有者并集，护士只持 223 也计入），共 16 个角色受影响
        assertEquals(
                16,
                roles.size(),
                "TENANT_ADMIN、FOLLOWUP_DOCTOR、4 个随访模板、A / B 预置各 3 个持 22 的角色与 C 预置医生 / 随访员 / 科主任（22）+ 护士（223）都受影响");
        assertEquals(
                85,
                ((Number) preview.get("affectedUserCount")).intValue(),
                "2 名演示用户 + A、B 预置各 28 名用户（16 病区医生 + 8 随访员 + 4 科主任）+ C 预置全部 27 名用户（护士持 223 也计入收回影响）受影响");

        // 保存（PLT-SCOPE-006、008）：自动收回租户库角色授权 + 受影响用户权限版本递增 + 变更记录
        Map<String, Object> change =
                data(
                        request(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + TENANT_A + "/scope",
                                platformToken,
                                scopeBody(WITHOUT_TASK, Map.of(), "按合同收回随访任务（示例）")));
        assertEquals(
                List.of(22, 223),
                ((List<?>) change.get("removedMenuIds"))
                        .stream().map(n -> ((Number) n).intValue()).sorted().toList(),
                "收回 22 连带其下隐藏路由 223（E15-T02 随访录入）");
        assertEquals(
                24,
                ((Number) change.get("revokedGrants")).intValue(),
                "16 个角色的 22 授权同步收回；TENANT_ADMIN / FOLLOWUP_DOCTOR、A / B 预置的病区医生 / 随访员与 C 预置的医生 / 随访员 / 护士另持 223");
        assertEquals(16, ((Number) change.get("revokedRoles")).intValue());
        assertEquals(
                85,
                ((Number) change.get("refreshedUsers")).intValue(),
                "2 名演示用户 + A、B 预置各 28 名用户 + C 预置全部 27 名用户权限版本递增");
        assertEquals(
                0,
                root.queryForObject(
                        "SELECT COUNT(*) FROM t_demoa.sys_role_menu WHERE menu_id = 22",
                        Integer.class),
                "收回范围后租户角色同步收回（验收）：sys_role_menu 里不再有 22");

        // 同一在线会话：下一次请求即重算（权限版本递增），菜单与权限码消失，但 base 能力不受影响
        List<String> after =
                menuTitles(request(HttpMethod.GET, "/api/hospital/menus", doctorToken, null));
        assertFalse(after.contains("我的任务"), "收回后菜单应消失：" + after);
        // E19-T02（平台库 V59）：医院端首页菜单改名「首页」（pages.md §1）
        assertTrue(after.contains("首页"), "base 固定能力不受影响：" + after);
        assertEquals(
                40300,
                code(request(HttpMethod.GET, "/api/hospital/followup/ping", doctorToken, null)),
                "followup:task:view 已随收回消失（40300）");

        // 痕迹：覆盖行（22 收回）+ 变更记录 + 审计
        assertEquals(
                1,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_tenant_scope WHERE tenant_id = 1 AND menu_id = 22"
                                + " AND assignable = 0",
                        Integer.class));
        assertEquals(
                1,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_scope_log WHERE tenant_id = 1 AND reason ="
                                + " '按合同收回随访任务（示例）'",
                        Integer.class));
        assertEquals(
                1,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_audit_log WHERE event_type = 'SCOPE_UPDATE'",
                        Integer.class));
        Map<String, Object> view =
                data(
                        request(
                                HttpMethod.GET,
                                "/api/platform/tenants/" + TENANT_A + "/scope",
                                platformToken,
                                null));
        assertEquals("NOT_RELEASED", scopeItem(scopeModule(view, "followup"), 22L).get("state"));
        List<Map<String, Object>> logs =
                dataItems(
                        request(
                                HttpMethod.GET,
                                "/api/platform/tenants/" + TENANT_A + "/scope/logs",
                                platformToken,
                                null));
        String detail = logs.get(0).get("detail").toString();
        assertTrue(
                detail.contains("随访任务") || detail.contains("我的任务"),
                "变更记录含前后差异（E15-T02 起菜单 22 标题为「我的任务」）：" + detail);
    }

    @Test
    @Order(3)
    void sensitiveReleaseAndRevoke() {
        connect();
        String platformToken = loginPlatform("platform.admin");
        List<Object> withPlain =
                java.util.stream.Stream.concat(WITHOUT_TASK.stream(), List.of(211L).stream())
                        .toList();
        // 前置归一：模块重开（E05-T05 兜底直授）可能已把 211 授给 TENANT_ADMIN，清掉保证断言确定
        root.update("DELETE FROM t_demoa.sys_role_menu WHERE menu_id = 211");

        // 新放开敏感项：原因必填（PLT-SCOPE-004）
        assertEquals(
                40001,
                code(
                        request(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + TENANT_A + "/scope",
                                platformToken,
                                scopeBody(withPlain, Map.of(), "放开明文（示例）"))));
        Map<String, Object> release =
                data(
                        request(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + TENANT_A + "/scope",
                                platformToken,
                                scopeBody(withPlain, Map.of("211", "科研协作需要（示例）"), "放开明文（示例）")));
        assertEquals(
                List.of(211),
                ((List<?>) release.get("addedMenuIds"))
                        .stream().map(n -> ((Number) n).intValue()).toList());
        assertEquals(
                1,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_tenant_scope WHERE tenant_id = 1 AND menu_id = 211"
                                + " AND assignable = 1 AND reason = '科研协作需要（示例）'",
                        Integer.class),
                "敏感放开留原因（PLT-SCOPE-008）");

        // 在可分配范围内授予角色（模拟租户管理员保存角色，逐项校验属 E06 落地）→ 会话拿到权限码
        root.update(
                "INSERT IGNORE INTO t_demoa.sys_role_menu (role_id, menu_id)"
                        + " SELECT r.id, 211 FROM t_demoa.sys_role r WHERE r.code = 'FOLLOWUP_DOCTOR'");
        String doctor = loginHospital("demoa", "doctor.demo");
        List<?> codes =
                dataItems(request(HttpMethod.GET, "/api/hospital/auth/codes", doctor, null));
        assertTrue(codes.contains("followup:patient:plain"), "放开后敏感权限码进入会话：" + codes);

        // 再收回：角色授权同步移除，会话权限码消失
        Map<String, Object> revoke =
                data(
                        request(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + TENANT_A + "/scope",
                                platformToken,
                                scopeBody(WITHOUT_TASK, Map.of(), "收回明文（示例）")));
        assertEquals(
                List.of(211),
                ((List<?>) revoke.get("removedMenuIds"))
                        .stream().map(n -> ((Number) n).intValue()).toList());
        assertEquals(1, ((Number) revoke.get("revokedGrants")).intValue());
        assertEquals(
                0,
                root.queryForObject(
                        "SELECT COUNT(*) FROM t_demoa.sys_role_menu rm JOIN t_demoa.sys_role r ON"
                                + " r.id = rm.role_id WHERE rm.menu_id = 211 AND r.code ="
                                + " 'FOLLOWUP_DOCTOR'",
                        Integer.class));
        String after = loginHospital("demoa", "doctor.demo");
        assertFalse(
                dataItems(request(HttpMethod.GET, "/api/hospital/auth/codes", after, null))
                        .contains("followup:patient:plain"));
    }

    @Test
    @Order(4)
    void validationAndPermissionBoundaries() {
        connect();
        String platformToken = loginPlatform("platform.admin");
        String scopePath = "/api/platform/tenants/" + TENANT_A + "/scope";
        // base 固定能力不能收回
        assertEquals(
                40001,
                code(
                        request(
                                HttpMethod.PUT,
                                scopePath,
                                platformToken,
                                scopeBody(
                                        java.util.stream.Stream.concat(
                                                        BASE_ALL.stream()
                                                                .filter(
                                                                        id ->
                                                                                ((Number) id)
                                                                                                .longValue()
                                                                                        != 31L),
                                                        List.of(20L, 21L, 23L).stream())
                                                .toList(),
                                        Map.of(),
                                        "误操作（示例）"))));
        // 授权范围外：未知菜单、平台端菜单（111 租户管理）
        assertEquals(
                40001,
                code(
                        request(
                                HttpMethod.PUT,
                                scopePath,
                                platformToken,
                                scopeBody(
                                        java.util.stream.Stream.concat(
                                                        WITHOUT_TASK.stream(),
                                                        List.of(999_999L).stream())
                                                .toList(),
                                        Map.of(),
                                        "误操作（示例）"))));
        assertEquals(
                40001,
                code(
                        request(
                                HttpMethod.PUT,
                                scopePath,
                                platformToken,
                                scopeBody(
                                        java.util.stream.Stream.concat(
                                                        WITHOUT_TASK.stream(),
                                                        List.of(111L).stream())
                                                .toList(),
                                        Map.of(),
                                        "误操作（示例）"))));
        // 变更原因必填
        assertEquals(
                40001,
                code(
                        request(
                                HttpMethod.PUT,
                                scopePath,
                                platformToken,
                                scopeBody(WITHOUT_TASK, Map.of(), " "))));

        // 无 scope:view / scope:edit 的平台账号：查看与保存都 40300（第 6 节：无权限）
        createViewer();
        String viewer = loginPlatform("platform.scope.viewer.it");
        assertEquals(40300, code(request(HttpMethod.GET, scopePath, viewer, null)));
        assertEquals(
                40300,
                code(
                        request(
                                HttpMethod.PUT,
                                scopePath,
                                viewer,
                                scopeBody(WITHOUT_TASK, Map.of(), "越权（示例）"))));
        // 租户不存在 40400
        assertEquals(
                40400,
                code(
                        request(
                                HttpMethod.GET,
                                "/api/platform/tenants/999999/scope",
                                platformToken,
                                null)));
    }

    @Test
    @Order(5)
    void templateDefaultsAffectTenantsWithoutOverrides() {
        connect();
        String platformToken = loginPlatform("platform.admin");
        // 模板视图：followup 默认项 20/21/22/23；敏感项不能预置；清单含明文查看
        Map<String, Object> template =
                data(request(HttpMethod.GET, "/api/platform/scope-template", platformToken, null));
        Map<String, Object> followup =
                ((List<Map<String, Object>>) template.get("modules"))
                        .stream()
                                .filter(m -> "followup".equals(m.get("code")))
                                .findFirst()
                                .orElseThrow();
        assertEquals(Boolean.TRUE, templateItem(followup, 21L).get("inTemplate"));
        assertEquals(Boolean.TRUE, templateItem(followup, 211L).get("sensitive"));
        assertTrue(
                ((List<Map<String, Object>>) template.get("sensitivePerms"))
                        .stream()
                                .anyMatch(
                                        row ->
                                                "followup:patient:plain"
                                                        .equals(row.get("permCode"))));

        // 收缩模板默认项（去掉 23 报告单）：无覆盖的租户立即按新默认收敛
        Map<String, Object> updated =
                data(
                        request(
                                HttpMethod.PUT,
                                "/api/platform/scope-template/followup",
                                platformToken,
                                Map.of("menuIds", List.of(20, 21, 22))));
        Map<String, Object> shrunk =
                ((List<Map<String, Object>>) updated.get("modules"))
                        .stream()
                                .filter(m -> "followup".equals(m.get("code")))
                                .findFirst()
                                .orElseThrow();
        assertEquals(Boolean.FALSE, templateItem(shrunk, 23L).get("inTemplate"));
        for (long tenantId : new long[] {TENANT_A, TENANT_B}) {
            Map<String, Object> scope =
                    data(
                            request(
                                    HttpMethod.GET,
                                    "/api/platform/tenants/" + tenantId + "/scope",
                                    platformToken,
                                    null));
            assertEquals(
                    "NOT_RELEASED",
                    scopeItem(scopeModule(scope, "followup"), 23L).get("state"),
                    "租户 " + tenantId + " 按模板默认收缩");
        }
        // 恢复模板
        request(
                HttpMethod.PUT,
                "/api/platform/scope-template/followup",
                platformToken,
                Map.of("menuIds", List.of(20, 21, 22, 23)));

        // 模板校验：敏感项不能预置；跨模块菜单与 base 模块 40001
        assertEquals(
                40001,
                code(
                        request(
                                HttpMethod.PUT,
                                "/api/platform/scope-template/followup",
                                platformToken,
                                Map.of("menuIds", List.of(20, 21, 211)))));
        assertEquals(
                40001,
                code(
                        request(
                                HttpMethod.PUT,
                                "/api/platform/scope-template/followup",
                                platformToken,
                                Map.of("menuIds", List.of(31)))));
        assertEquals(
                40001,
                code(
                        request(
                                HttpMethod.PUT,
                                "/api/platform/scope-template/base",
                                platformToken,
                                Map.of("menuIds", List.of()))));

        // 敏感清单整表替换：格式校验 + 原样回写（幂等）
        assertEquals(
                40001,
                code(
                        request(
                                HttpMethod.PUT,
                                "/api/platform/scope-template/sensitive-perms",
                                platformToken,
                                Map.of(
                                        "items",
                                        List.of(
                                                Map.of(
                                                        "permCode", "followup:patient:plain",
                                                        "name", "明文查看"),
                                                Map.of(
                                                        "permCode",
                                                        "not-a-code",
                                                        "name",
                                                        "格式错误"))))));
        List<Map<String, Object>> current =
                (List<Map<String, Object>>) template.get("sensitivePerms");
        List<Map<String, String>> same =
                current.stream()
                        .map(
                                row ->
                                        Map.of(
                                                "permCode",
                                                String.valueOf(row.get("permCode")),
                                                "name",
                                                String.valueOf(row.get("name"))))
                        .toList();
        Map<String, Object> rewritten =
                data(
                        request(
                                HttpMethod.PUT,
                                "/api/platform/scope-template/sensitive-perms",
                                platformToken,
                                Map.of("items", same)));
        assertEquals(current.size(), ((List<?>) rewritten.get("sensitivePerms")).size());
    }

    @Test
    @Order(6)
    void closedModuleShrinksScopeAndRejectsItems() {
        connect();
        String platformToken = loginPlatform("platform.admin");
        // 关闭租户 2 的 followup（PLT-MOD-005：数据与角色授权保留）
        assertEquals(
                0,
                code(
                        post(
                                Map.of("reason", "范围收缩验证（示例）"),
                                "/api/platform/tenants/" + TENANT_B + "/modules/followup/close",
                                platformToken)));
        Map<String, Object> scope =
                data(
                        request(
                                HttpMethod.GET,
                                "/api/platform/tenants/" + TENANT_B + "/scope",
                                platformToken,
                                null));
        Map<String, Object> followup = scopeModule(scope, "followup");
        assertEquals(Boolean.FALSE, followup.get("opened"), "模块关闭即授权范围收缩");
        assertEquals("CLOSED", scopeItem(followup, 21L).get("state"));
        // 未开通模块的项不能进入可分配范围
        assertEquals(
                40001,
                code(
                        request(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + TENANT_B + "/scope",
                                platformToken,
                                scopeBody(
                                        java.util.stream.Stream.concat(
                                                        BASE_ALL.stream(),
                                                        List.of(20L, 21L).stream())
                                                .toList(),
                                        Map.of(),
                                        "误操作（示例）"))));
        // base 固定能力不受模块关闭影响
        assertEquals("ASSIGNABLE", scopeItem(scopeModule(scope, "base"), 31L).get("state"));
    }
}
