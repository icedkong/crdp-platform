package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.crdp.framework.notify.SmsCodeSender;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * E20-T06 端到端（验收原文：默认关闭，开启需授权并记审计）——代为配置（PLT-VIEW-013）、代重置医院用户密码
 * （PLT-VIEW-017）与编辑业务数据（PLT-VIEW-018），全部走真实视角会话（进入 → 换视角令牌 → 调医院端接口）：
 *
 * <ol>
 *   <li>默认关闭：无代为权限的角色（自建 IT 角色，三个开关全关）在视角中写配置 / 写业务 / 代重置全部 40303， 写 VIEW_DENIED 平台审计；
 *   <li>开启需授权：平台管理员（预置角色三开关全开）带二次确认原因（X-View-Reason）—— 代为配置（PUT 用户）与编辑业务数据（POST 患者，预置 followup
 *       可编辑规则命中）放行并写 VIEW_PROXY_CONFIG / VIEW_PROXY_EDIT（对象、原因、写前快照与请求载荷的前后值）， 台账 proxy_count
 *       递增；缺原因 40001；「可编辑业务数据」但未命中可编辑规则的自建角色仍只读（PLT-VIEW-018 任一不满足即只读）；
 *   <li>记审计：医院侧操作日志的操作者匿名「平台支持人员 会话号」（不落平台账号与姓名，platform-spec §8 已确认）；
 *   <li>代重置密码（PLT-VIEW-017）：新密码只经短信发给用户本人（MockitoBean 短信口捕获），任何响应不含新密码； 医院侧重置口对视角令牌仍
 *       40303（专用代理口）；短信失败重置不生效（密码哈希不变）；写 VIEW_PROXY_RESET 审计。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清（IT 角色 / 账号、医院用户 it.proxyuser*、 患者
 * legacy_no 前缀 IT-E20T06-、视角会话行与 VIEW_ 审计）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformTenantViewProxyMySqlIT {

    private static final String PLATFORM_PASSWORD = "Demo@2026";
    private static final String HOSPITAL_PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E20T06";
    private static final String REASON = "配置核对后代为修正（示例）";
    private static final String NEW_PASSWORD = "View#2026xB";

    /** 本 IT 创建的平台账号 / 角色 → 清理用。 */
    private static final List<String> createdAccounts = new ArrayList<>();

    private static final List<Long> createdRoles = new ArrayList<>();

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private StringRedisTemplate redis;

    @MockitoBean private SmsCodeSender smsCodeSender;

    private static String capturedSmsPassword;

    private String viewToken;
    private String sessionNo;
    private long demoaId;

    @BeforeEach
    void cleanState() {
        deleteKeys("crdp:sess:*");
        deleteKeys("crdp:login:fail:*");
        deleteKeys("crdp:view-enter:*");
        JdbcTemplate platform = platformDirect();
        platform.update(
                "UPDATE plt_view_session SET status = 'ENDED', end_method = 'LOGOUT'"
                        + " WHERE account_name IN ('platform.admin', 'it.view.noproxy',"
                        + " 'it.view.editonly') AND status = 'ACTIVE'");
        demoaId =
                platform.queryForObject(
                        "SELECT id FROM plt_tenant WHERE code = ?", Long.class, "demoa");
        viewToken = null;
        sessionNo = null;
    }

    // ---------------------------------------------------------------- 1. 默认关闭（验收：无授权全部拒绝）

    @Test
    @Order(1)
    void unauthorizedRoleWritesAreAllRejected() {
        String admin = platformToken();
        long roleId = createRole(admin, "视角只读角色（示例）", false, false, false);
        createAccount(admin, "it.view.noproxy", "视角只读账号（示例）", roleId);
        enterView("it.view.noproxy", NEW_PASSWORD);

        assertEquals(
                40303,
                codeOf(putWithReason("/api/hospital/system/users/1", viewToken, Map.of(), REASON)),
                "不可代为配置：配置类写拒绝（默认关闭）");
        assertEquals(
                40303,
                codeOf(
                        postWithReason(
                                "/api/hospital/followup/patients",
                                viewToken,
                                patientBody(),
                                REASON)),
                "不可编辑业务数据：业务写拒绝");
        assertEquals(
                40303,
                codeOf(
                        postWithReason(
                                "/api/hospital/view/users/1/reset-password",
                                viewToken,
                                Map.of("reason", REASON),
                                REASON)),
                "不可代重置密码（角色字段默认否）");

        JdbcTemplate platform = platformDirect();
        Integer denied =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM aud_platform_access_log WHERE action ="
                                + " 'VIEW_DENIED' AND view_session_id = ?",
                        Integer.class,
                        sessionNo);
        assertTrue(denied != null && denied >= 3, "被拒写平台审计（含代重置口）：" + denied);

        exitView();
    }

    // ---------------------------------------------------------------- 2. 开启需授权：代为配置 +
    // 记审计（PLT-VIEW-013）

    @Test
    @Order(2)
    void authorizedConfigWriteReleasesWithBeforeAfterAudit() {
        String hospitalAdmin = hospitalToken("nurse.demo");
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        hospitalAdmin,
                        Map.of(
                                "username",
                                "it.proxyuser",
                                "displayName",
                                "被代改用户（示例）",
                                "phone",
                                "13900007766",
                                "mainDeptId",
                                mainDeptId(),
                                "roleIds",
                                List.of(roleIdByCode("FOLLOWUP_DOCTOR")),
                                "wardIds",
                                List.of(),
                                "deptScopeIds",
                                List.of()));
        assertEquals(0, codeOf(created), "医院侧建用户：" + created.get("message"));
        long userId = ((Number) ((Map<?, ?>) data(created).get("user")).get("id")).longValue();
        String hashBefore =
                tenantJdbc()
                        .queryForObject(
                                "SELECT password_hash FROM sys_user WHERE id = ?",
                                String.class,
                                userId);

        enterView("platform.admin", PLATFORM_PASSWORD);

        // 缺原因：二次确认必填（40001），不落任何写
        assertEquals(
                40001,
                codeOf(put("/api/hospital/system/users/" + userId, viewToken, Map.of())),
                "放行须带 X-View-Reason（二次确认并填写原因）");

        // 带原因的代为配置：放行 + VIEW_PROXY_CONFIG（对象 / 原因 / 前后值）+ proxy_count
        Map<?, ?> detail = get("/api/hospital/system/users/" + userId, viewToken);
        Map<?, ?> updated =
                putWithReason(
                        "/api/hospital/system/users/" + userId,
                        viewToken,
                        editBodyOf(data(detail), "被代改用户已修正（示例）"),
                        REASON);
        assertEquals(0, codeOf(updated), "代为配置放行：" + updated.get("message"));
        assertEquals("被代改用户已修正（示例）", data(updated).get("displayName"), "配置变更生效");

        JdbcTemplate platform = platformDirect();
        Map<String, Object> proxyRow =
                platform.queryForMap(
                        "SELECT object_type, object_id, reason, before_json, after_json FROM"
                                + " aud_platform_access_log WHERE action = 'VIEW_PROXY_CONFIG'"
                                + " AND view_session_id = ? AND object_id = ?",
                        sessionNo,
                        String.valueOf(userId));
        assertEquals("user", proxyRow.get("object_type"), "object_type 取权限码资源段（与 VIEW_QUERY 同口径）");
        assertEquals(REASON, proxyRow.get("reason"));
        String beforeJson = String.valueOf(proxyRow.get("before_json"));
        String afterJson = String.valueOf(proxyRow.get("after_json"));
        assertTrue(beforeJson.contains("被代改用户（示例）"), "写前同路径 GET 快照记前值：" + beforeJson);
        assertTrue(afterJson.contains("已修正"), "请求载荷记后值：" + afterJson);
        Map<String, Object> ledger =
                platform.queryForMap(
                        "SELECT proxy_count FROM plt_view_session WHERE session_no = ?", sessionNo);
        assertTrue(((Number) ledger.get("proxy_count")).longValue() >= 1, "台账代为操作计数递增");

        // 医院侧操作日志操作者匿名（platform-spec §8 已确认）：「平台支持人员 会话号」，不落平台账号与姓名
        Map<String, Object> tenantLog =
                tenantJdbc()
                        .queryForMap(
                                "SELECT user_name, display_name FROM aud_log WHERE"
                                        + " event_type = 'USER_UPDATE' AND view_session_id = ?",
                                sessionNo);
        assertEquals("platform_view", tenantLog.get("user_name"));
        assertEquals("平台支持人员 " + sessionNo, tenantLog.get("display_name"));

        // 代为配置不触碰凭据
        String hashAfter =
                tenantJdbc()
                        .queryForObject(
                                "SELECT password_hash FROM sys_user WHERE id = ?",
                                String.class,
                                userId);
        assertEquals(hashBefore, hashAfter, "代为配置不改密码哈希");

        exitView();
    }

    // ---------------------------------------------------------------- 3. 编辑业务数据（PLT-VIEW-018：角色 ∧
    // 规则缺一即只读）

    @Test
    @Order(3)
    void businessEditRequiresBothRoleGrantAndEditableRule() {
        // 3a 预置管理员（角色 ∧ 预置 followup 可编辑规则）→ 放行并写 VIEW_PROXY_EDIT
        enterView("platform.admin", PLATFORM_PASSWORD);
        Map<?, ?> registered =
                postWithReason(
                        "/api/hospital/followup/patients", viewToken, patientBody(), "排障补登患者（示例）");
        assertEquals(0, codeOf(registered), "编辑业务数据放行：" + registered.get("message"));
        assertNotNull(data(registered).get("id"), "患者已建档");
        JdbcTemplate platform = platformDirect();
        Integer edits =
                platform.queryForObject(
                        "SELECT COUNT(*) FROM aud_platform_access_log WHERE action ="
                                + " 'VIEW_PROXY_EDIT' AND view_session_id = ?",
                        Integer.class,
                        sessionNo);
        assertTrue(edits != null && edits >= 1, "业务数据编辑写 VIEW_PROXY_EDIT 审计");
        exitView();

        // 3b「可编辑业务数据」但未命中可编辑规则的自建角色（全局默认只读）→ 只读（任一不满足即只读）
        String admin = platformToken();
        long roleId = createRole(admin, "可编辑角色（示例）", false, false, true);
        createAccount(admin, "it.view.editonly", "可编辑账号（示例）", roleId);
        enterView("it.view.editonly", NEW_PASSWORD);
        assertEquals(
                40303,
                codeOf(
                        postWithReason(
                                "/api/hospital/followup/patients",
                                viewToken,
                                patientBody(),
                                REASON)),
                "未命中可编辑规则（全局默认只读）→ 只读（PLT-VIEW-018）");
        exitView();
    }

    // ---------------------------------------------------------------- 4. 代重置医院用户密码（PLT-VIEW-017）

    @Test
    @Order(4)
    void proxyResetSendsPasswordBySmsOnly() {
        String hospitalAdmin = hospitalToken("nurse.demo");
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        hospitalAdmin,
                        Map.of(
                                "username",
                                "it.proxyuser2",
                                "displayName",
                                "被代重置用户（示例）",
                                "phone",
                                "13900007777",
                                "mainDeptId",
                                mainDeptId(),
                                "roleIds",
                                List.of(roleIdByCode("FOLLOWUP_DOCTOR")),
                                "wardIds",
                                List.of(),
                                "deptScopeIds",
                                List.of()));
        assertEquals(0, codeOf(created), "医院侧建用户：" + created.get("message"));
        long userId = ((Number) ((Map<?, ?>) data(created).get("user")).get("id")).longValue();
        String hashBefore =
                tenantJdbc()
                        .queryForObject(
                                "SELECT password_hash FROM sys_user WHERE id = ?",
                                String.class,
                                userId);

        enterView("platform.admin", PLATFORM_PASSWORD);
        // 医院侧重置口对视角令牌仍拒绝（重置只走专用代理口）
        assertEquals(
                40303,
                codeOf(
                        postWithReason(
                                "/api/hospital/system/users/" + userId + "/reset-password",
                                viewToken,
                                Map.of(),
                                REASON)),
                "医院侧重置口不放行（动作 reset 永不释放）");

        // 短信失败：重置不生效（密码哈希不变，如实报错）
        doThrow(new RuntimeException("channel down"))
                .when(smsCodeSender)
                .sendTemporaryPassword(anyString(), anyString());
        Map<?, ?> failed =
                postWithReason(
                        "/api/hospital/view/users/" + userId + "/reset-password",
                        viewToken,
                        Map.of("reason", "代重置（示例）"),
                        REASON);
        assertEquals(50000, codeOf(failed), "短信下发失败整体失败（不给已生效假象）");
        String hashAfterFailure =
                tenantJdbc()
                        .queryForObject(
                                "SELECT password_hash FROM sys_user WHERE id = ?",
                                String.class,
                                userId);
        assertEquals(hashBefore, hashAfterFailure, "短信失败时密码未变化");

        // 短信成功：落库 + 只回账号与脱敏手机号（绝不回新密码）+ VIEW_PROXY_RESET 审计
        doAnswer(
                        invocation -> {
                            capturedSmsPassword = invocation.getArgument(1);
                            return null;
                        })
                .when(smsCodeSender)
                .sendTemporaryPassword(anyString(), anyString());
        Map<?, ?> reset =
                postWithReason(
                        "/api/hospital/view/users/" + userId + "/reset-password",
                        viewToken,
                        Map.of("reason", "用户来电忘记密码（示例）"),
                        REASON);
        assertEquals(0, codeOf(reset), "代重置放行：" + reset.get("message"));
        assertEquals("it.proxyuser2", data(reset).get("username"));
        assertEquals("139****7777", data(reset).get("sentTo"), "只回脱敏手机号（短信去向）");
        assertFalse(String.valueOf(reset).contains(capturedSmsPassword), "响应不含新密码");
        assertFalse(String.valueOf(data(reset)).toLowerCase().contains("password"), "响应不含任何密码字段");
        verify(smsCodeSender, org.mockito.Mockito.atLeastOnce())
                .sendTemporaryPassword(eq("13900007777"), anyString());

        String hashAfter =
                tenantJdbc()
                        .queryForObject(
                                "SELECT password_hash FROM sys_user WHERE id = ?",
                                String.class,
                                userId);
        assertNotEquals(hashBefore, hashAfter, "新密码已落库");
        assertEquals(
                1,
                tenantJdbc()
                        .queryForObject(
                                "SELECT must_change_password FROM sys_user WHERE id = ?",
                                Integer.class,
                                userId),
                "首次登录强制改密");
        String resetAudit =
                platformDirect()
                        .queryForObject(
                                "SELECT description FROM aud_platform_access_log WHERE action ="
                                        + " 'VIEW_PROXY_RESET' AND view_session_id = ? AND"
                                        + " object_id = ?",
                                String.class,
                                sessionNo,
                                String.valueOf(userId));
        assertFalse(resetAudit.contains(capturedSmsPassword), "审计不含新密码");
        assertTrue(resetAudit.contains("用户来电忘记密码"), "审计含操作原因：" + resetAudit);

        // 横幅会话带代为操作能力（前端按钮显隐依据）
        Map<?, ?> shell = get("/api/hospital/view/session", viewToken);
        Map<?, ?> capabilities = (Map<?, ?>) data(shell).get("capabilities");
        assertTrue((Boolean) capabilities.get("canConfigure"), "平台管理员可代为配置");
        assertTrue((Boolean) capabilities.get("canResetPassword"), "平台管理员可代重置密码");
        assertTrue((Boolean) capabilities.get("canEditBusiness"), "平台管理员可编辑业务数据");
        assertTrue((Boolean) capabilities.get("businessEditable"), "预置可编辑规则命中");

        exitView();
    }

    // ---------------------------------------------------------------- 造数与清理

    private Map<String, Object> patientBody() {
        return new HashMap<>(
                Map.of(
                        "medicalRecordNo",
                        "IT-MRN-E20T06-" + System.nanoTime() % 100000,
                        "name",
                        "王代改（示例）",
                        "gender",
                        "F",
                        "birthDate",
                        "1950-05-05",
                        "wardId",
                        wardIdByCode("W-DEMO-1"),
                        "surgeryDate",
                        "2026-03-01",
                        "admissionDate",
                        "2026-02-25",
                        "dischargeDate",
                        "2026-03-08",
                        "dischargeStatus",
                        1,
                        "legacyNo",
                        LEGACY));
    }

    private long wardIdByCode(String code) {
        return com.crdp.framework.tenant.TenantContext.callAsTenant(
                1L,
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private long roleIdByCode(String code) {
        return com.crdp.framework.tenant.TenantContext.callAsTenant(
                1L,
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_role WHERE code = ?", Long.class, code));
    }

    private long mainDeptId() {
        return com.crdp.framework.tenant.TenantContext.callAsTenant(
                1L, "demoa", () -> jdbc.queryForObject("SELECT MIN(id) FROM sys_dept", Long.class));
    }

    /** 以医院详情构造编辑体（全量覆盖口径），改显示名；手机号传空 = 保持不变。 */
    private Map<String, Object> editBodyOf(Map<String, Object> detail, String displayName) {
        return new HashMap<>(
                Map.of(
                        "displayName",
                        displayName,
                        "staffNo",
                        detail.get("staffNo") == null ? "" : detail.get("staffNo"),
                        "phone",
                        "",
                        "mainDeptId",
                        detail.get("mainDeptId") == null ? mainDeptId() : detail.get("mainDeptId"),
                        "roleIds",
                        detail.get("roleIds") == null ? List.of() : detail.get("roleIds"),
                        "wardIds",
                        detail.get("wardIds") == null ? List.of() : detail.get("wardIds"),
                        "deptScopeIds",
                        detail.get("deptScopeIds") == null
                                ? List.of()
                                : detail.get("deptScopeIds")));
    }

    private long createRole(
            String adminToken, String name, boolean configure, boolean reset, boolean edit) {
        Map<?, ?> response =
                post(
                        "/api/platform/roles",
                        adminToken,
                        Map.of(
                                "name",
                                name,
                                "menuIds",
                                List.of(menuIdByPerm("platform:view:enter")),
                                "viewCanEnter",
                                true,
                                "viewVisibleScope",
                                "CONFIG_AND_BUSINESS",
                                "viewTenantScope",
                                "ALL",
                                "viewCanConfigure",
                                configure,
                                "viewCanResetPassword",
                                reset,
                                "viewCanEditBusiness",
                                edit));
        assertEquals(0, codeOf(response), "建 IT 角色：" + response.get("message"));
        long id = ((Number) data(response).get("id")).longValue();
        createdRoles.add(id);
        return id;
    }

    private long menuIdByPerm(String permCode) {
        return platformDirect()
                .queryForObject(
                        "SELECT id FROM plt_menu WHERE perm_code = ?", Long.class, permCode);
    }

    private void createAccount(
            String adminToken, String username, String displayName, long roleId) {
        Map<?, ?> response =
                post(
                        "/api/platform/accounts",
                        adminToken,
                        Map.of(
                                "username",
                                username,
                                "displayName",
                                displayName,
                                "phone",
                                "139000099" + (10 + createdAccounts.size() % 80),
                                "password",
                                NEW_PASSWORD + "A",
                                "roleIds",
                                List.of(roleId)));
        assertEquals(0, codeOf(response), "建 IT 账号：" + response.get("message"));
        createdAccounts.add(username);
        // 首次登录强制改密：改回测试口令，后续登录直接可用
        String first = loginToken(username, NEW_PASSWORD + "A");
        Map<?, ?> changed =
                post(
                        "/api/platform/auth/password",
                        first,
                        Map.of("oldPassword", NEW_PASSWORD + "A", "newPassword", NEW_PASSWORD));
        assertEquals(0, codeOf(changed), "IT 账号改密：" + changed.get("message"));
    }

    @AfterAll
    static void cleanUp() {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params = "?useSSL=false&allowPublicKeyRetrieval=true";
        JdbcTemplate platform =
                new JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));
        AuditTableMaintenance.delete(
                platform,
                "DELETE FROM aud_platform_access_log WHERE view_session_id IN"
                        + " (SELECT session_no FROM plt_view_session WHERE account_name IN"
                        + " ('platform.admin', 'it.view.noproxy', 'it.view.editonly'))");
        platform.update(
                "DELETE FROM plt_view_session WHERE account_name IN"
                        + " ('platform.admin', 'it.view.noproxy', 'it.view.editonly')");
        for (Long roleId : createdRoles) {
            platform.update("DELETE FROM plt_role_menu WHERE role_id = ?", roleId);
            platform.update("DELETE FROM plt_role WHERE id = ?", roleId);
        }
        for (String username : createdAccounts) {
            platform.update(
                    "DELETE FROM plt_account_role WHERE account_id IN"
                            + " (SELECT id FROM plt_account WHERE username = ?)",
                    username);
            platform.update("DELETE FROM plt_account WHERE username = ?", username);
        }

        JdbcTemplate tenant =
                new JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                base + "/t_demoa" + params, "crdp_app", "DevOnly#app2026"));
        List<String> sessionNos =
                platform.queryForList(
                        "SELECT session_no FROM plt_view_session WHERE account_name IN"
                                + " ('platform.admin', 'it.view.noproxy', 'it.view.editonly')",
                        String.class);
        if (!sessionNos.isEmpty()) {
            String placeholders =
                    String.join(",", java.util.Collections.nCopies(sessionNos.size(), "?"));
            AuditTableMaintenance.delete(
                    tenant,
                    "DELETE FROM aud_log WHERE view_session_id IN (" + placeholders + ")",
                    sessionNos.toArray());
        }
        for (String username : List.of("it.proxyuser", "it.proxyuser2")) {
            tenant.update(
                    "DELETE FROM sys_user_role WHERE user_id IN"
                            + " (SELECT id FROM sys_user WHERE username = ?)",
                    username);
            tenant.update(
                    "DELETE FROM sys_password_history WHERE user_id IN"
                            + " (SELECT id FROM sys_user WHERE username = ?)",
                    username);
            tenant.update("DELETE FROM sys_user WHERE username = ?", username);
        }
        tenant.update(
                "DELETE FROM pt_patient_summary WHERE patient_id NOT IN"
                        + " (SELECT id FROM pt_patient)");
        tenant.update("DELETE FROM pt_patient WHERE legacy_no = ?", LEGACY);
    }

    // ---------------------------------------------------------------- 视角会话进出与 HTTP 辅助

    private void enterView(String username, String password) {
        String platform = loginToken(username, password);
        Map<?, ?> entered =
                post(
                        "/api/platform/tenant-view/sessions",
                        platform,
                        Map.of(
                                "tenantId",
                                demoaId,
                                "purpose",
                                "CONFIG_CHECK",
                                "note",
                                "代为操作验证（示例）"));
        assertEquals(0, codeOf(entered), "进入视角：" + entered.get("message"));
        sessionNo = (String) data(entered).get("sessionNo");
        Map<?, ?> ticket =
                post("/api/platform/tenant-view/sessions/current/entry-ticket", platform, Map.of());
        assertEquals(0, codeOf(ticket), "签发进入凭据：" + ticket.get("message"));
        Map<?, ?> exchanged =
                post(
                        "/api/hospital/view/enter",
                        null,
                        Map.of("ticket", data(ticket).get("ticket")));
        assertEquals(0, codeOf(exchanged), "换取视角令牌：" + exchanged.get("message"));
        viewToken = (String) data(exchanged).get("token");
        assertNotNull(viewToken);
    }

    private void exitView() {
        if (viewToken != null) {
            delete("/api/hospital/view/session", viewToken);
            viewToken = null;
        }
    }

    private String platformToken() {
        return loginToken("platform.admin", PLATFORM_PASSWORD);
    }

    private String hospitalToken(String username) {
        Map<?, ?> login =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of(
                                "tenantCode",
                                "demoa",
                                "username",
                                username,
                                "password",
                                HOSPITAL_PASSWORD));
        assertEquals(0, codeOf(login), "医院登录：" + login.get("message"));
        return (String) data(login).get("token");
    }

    private String loginToken(String username, String password) {
        Map<?, ?> login =
                post(
                        "/api/platform/auth/login",
                        null,
                        Map.of("username", username, "password", password));
        assertEquals(0, codeOf(login), "平台登录：" + login.get("message"));
        return (String) data(login).get("token");
    }

    private void deleteKeys(String pattern) {
        var keys = redis.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    /** 租户库直连（demoa，带 utf8——断言中文审计文案）。 */
    private JdbcTemplate tenantJdbc() {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params =
                "?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=utf8";
        return new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        base + "/t_demoa" + params, "crdp_app", "DevOnly#app2026"));
    }

    private JdbcTemplate platformDirect() {
        String base = "jdbc:mysql://127.0.0.1:" + System.getenv("CRDP_IT_MYSQL_PORT");
        String params =
                "?useSSL=false&allowPublicKeyRetrieval=true&useUnicode=true&characterEncoding=utf8";
        return new JdbcTemplate(
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        base + "/crdp_platform" + params, "crdp_app", "DevOnly#app2026"));
    }

    private Map<?, ?> post(String path, String token, Map<String, ?> body) {
        return exchange(path, HttpMethod.POST, token, body, null);
    }

    private Map<?, ?> postWithReason(
            String path, String token, Map<String, ?> body, String reason) {
        return exchange(path, HttpMethod.POST, token, body, reason);
    }

    private Map<?, ?> put(String path, String token, Map<String, ?> body) {
        return exchange(path, HttpMethod.PUT, token, body, null);
    }

    private Map<?, ?> putWithReason(String path, String token, Map<String, ?> body, String reason) {
        return exchange(path, HttpMethod.PUT, token, body, reason);
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(path, HttpMethod.GET, token, null, null);
    }

    private Map<?, ?> delete(String path, String token) {
        return exchange(path, HttpMethod.DELETE, token, null, null);
    }

    private Map<?, ?> exchange(
            String path, HttpMethod method, String token, Map<String, ?> body, String reason) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (reason != null) {
            // 头值按 encodeURIComponent 百分号编码（中文原因无损）
            headers.set(
                    "X-View-Reason",
                    java.net.URLEncoder.encode(reason, java.nio.charset.StandardCharsets.UTF_8));
        }
        HttpEntity<?> entity =
                body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return rest.exchange(path, method, entity, Map.class).getBody();
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<?, ?> response) {
        return response.get("data") == null
                ? new HashMap<>()
                : (Map<String, Object>) response.get("data");
    }
}
