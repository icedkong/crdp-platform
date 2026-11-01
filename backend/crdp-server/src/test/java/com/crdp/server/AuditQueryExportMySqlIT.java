package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.audit.AuditEntry;
import com.crdp.framework.audit.AuditLogger;
import com.crdp.framework.audit.AuditResult;
import com.crdp.framework.tenant.TenantContext;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E08-T04 / PLT-AUD-005、006、007 端到端（完整应用 + 真实 MySQL/Redis，HTTP 直连）。 验收原文：导出需审批，导出本身记审计。
 *
 * <ol>
 *   <li>查询（005）：时间 / 模块 / 结果 / IP / traceId 组合过滤 + 患者页签 + 跨度上限 40001；
 *   <li>详情（006）：前后值对比（敏感值摘要）+ 同 traceId 关联；
 *   <li>导出（007）：租户管理员免审批直接生成、普通用户待审批（下载被拦 40901）→ 租户管理员审批 → 生成 → 下载（CSV 含水印行）；无权限
 *       40300；平台账号导出由另一名平台管理员审批、不能自审（40901）；
 *   <li>导出全程记审计：提交（EXPORT）、审批（OPERATION 前后值）、下载（DOWNLOAD 带文件哈希）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT。自建自清（标记前缀 it.e08t04 / it-audit-e08t04），全部虚构演示账号。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class AuditQueryExportMySqlIT {

    private static final String TENANT = "demoa";
    private static final String PASSWORD = "Demo@2026";
    private static final String ADMIN = "nurse.demo";
    private static final String VIEWER = "doctor.demo";
    private static final String USER = "it.e08t04.user";
    private static final String PLT_SECOND = "it.e08t04.plt2";
    private static final String MARKER = "it-audit-e08t04";
    private static final String TRACE = "it-trace-e08t04";
    private static final String IP = "10.198";

    private JdbcTemplate platform;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuditLogger auditLogger;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeAll
    void setUp() {
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        platform =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                                "crdp_app",
                                "DevOnly#app2026"));
        // 普通用户：自建角色只授查看 + 导出（35 / 351），不授审批（352）——审批必须走租户管理员
        runInTenant(
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status, role_type)"
                                    + " VALUES ('IT_E08T04_VIEWER', 'IT 审计导出角色（示例）', 0, 'ENABLED',"
                                    + " 'CUSTOM')");
                    Long roleId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_role WHERE code = 'IT_E08T04_VIEWER'",
                                    Long.class);
                    jdbc.update(
                            "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 35)", roleId);
                    jdbc.update(
                            "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 351)", roleId);
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                    + " must_change_password) VALUES (?, 'IT 审计用户（示例）', ?, 'ENABLED', 0)",
                            USER,
                            passwordEncoder.encode(PASSWORD));
                    jdbc.update(
                            "INSERT INTO sys_user_role (user_id, role_id) SELECT u.id, r.id FROM sys_user u,"
                                    + " sys_role r WHERE u.username = ? AND r.code = 'IT_E08T04_VIEWER'",
                            USER);
                });
        // 第二名平台管理员（审批人不能是申请人）
        TenantContext.runAsPlatform(
                () -> {
                    platform.update(
                            "INSERT INTO plt_account (username, display_name, password_hash, status)"
                                    + " VALUES (?, 'IT 平台二号管理员（示例）', ?, 'ENABLED')",
                            PLT_SECOND,
                            passwordEncoder.encode(PASSWORD));
                    platform.update(
                            "INSERT INTO plt_account_role (account_id, role_id) SELECT a.id, r.id FROM"
                                    + " plt_account a, plt_role r WHERE a.username = ? AND r.code ="
                                    + " 'PLATFORM_ADMIN'",
                            PLT_SECOND);
                });
    }

    @AfterAll
    void cleanUp() {
        if (platform == null) {
            return;
        }
        runInTenant(
                () -> {
                    jdbc.update(
                            "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id WHERE"
                                    + " r.code = 'IT_E08T04_VIEWER'");
                    jdbc.update(
                            "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id WHERE"
                                    + " u.username = ?",
                            USER);
                    jdbc.update("DELETE FROM sys_role WHERE code = 'IT_E08T04_VIEWER'");
                    jdbc.update("DELETE FROM sys_user WHERE username = ?", USER);
                    jdbc.update(
                            "DELETE FROM job_export WHERE condition_json LIKE '%it-audit-e08t04%'");
                    jdbc.update(
                            "DELETE FROM job_export_approval WHERE export_id NOT IN (SELECT id FROM"
                                    + " job_export)");
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_log WHERE module = ?", MARKER);
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_data_access_log WHERE module = ?", MARKER);
                });
        TenantContext.runAsPlatform(
                () -> {
                    platform.update(
                            "DELETE ar FROM plt_account_role ar JOIN plt_account a ON a.id = ar.account_id"
                                    + " WHERE a.username = ?",
                            PLT_SECOND);
                    platform.update("DELETE FROM plt_account WHERE username = ?", PLT_SECOND);
                    platform.update(
                            "DELETE FROM job_export WHERE condition_json LIKE '%it-audit-e08t04%'");
                    platform.update(
                            "DELETE FROM job_export_approval WHERE export_id NOT IN (SELECT id FROM"
                                    + " job_export)");
                    AuditTableMaintenance.delete(
                            platform, "DELETE FROM plt_audit_log WHERE module = ?", MARKER);
                    AuditTableMaintenance.delete(
                            platform,
                            "DELETE FROM aud_platform_access_log WHERE module IN ('platform', ?)",
                            MARKER);
                });
    }

    @BeforeEach
    void seedAuditRows() {
        runInTenant(
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_log WHERE module = ?", MARKER);
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_data_access_log WHERE module = ?", MARKER);
                    auditLogger.append(
                            AuditEntry.operation("it-audit-e08t04", "IT_E08T04_OP")
                                    .description("E08-T04 查询探针（示例）")
                                    .objectType("PATIENT")
                                    .objectId("99001")
                                    .beforeJson("{\"status\":\"ENABLED\"}")
                                    .afterJson("{\"phone\":\"sha256:9f86d0\"}")
                                    .result(AuditResult.SUCCESS)
                                    .ip(IP + ".7.1")
                                    .traceId(TRACE)
                                    .build());
                    auditLogger.append(
                            AuditEntry.dataAccess("it-audit-e08t04", "VIEW_PLAIN")
                                    .description("查看明文探针（示例）")
                                    .objectType("PATIENT")
                                    .objectId("99001")
                                    .recordCount(1L)
                                    .result(AuditResult.SUCCESS)
                                    .ip(IP + ".7.2")
                                    .traceId(TRACE)
                                    .build());
                });
        TenantContext.runAsPlatform(
                () ->
                        auditLogger.append(
                                AuditEntry.operation("it-audit-e08t04", "IT_E08T04_PLT")
                                        .tenantId(1L)
                                        .description("E08-T04 平台探针（示例）")
                                        .objectType("plt_tenant")
                                        .objectId("1")
                                        .result(AuditResult.SUCCESS)
                                        .traceId(TRACE)
                                        .build()));
    }

    // ---------------------------------------------------------------- 查询与详情（PLT-AUD-005 / 006）

    @Test
    void searchFiltersByModuleResultIpAndTrace() {
        String token = login(ADMIN);
        Map<?, ?> response =
                post(
                        "/api/hospital/system/audit/operation/search",
                        token,
                        Map.of(
                                "startTime",
                                windowStart(),
                                "endTime",
                                windowEnd(),
                                "module",
                                MARKER,
                                "result",
                                "SUCCESS",
                                "traceId",
                                TRACE),
                        null);
        assertEquals(0, codeOf(response), "查询失败：" + response);
        Map<?, ?> page = data(response);
        assertEquals(1L, ((Number) map(page).get("total")).longValue());
        assertTrue(map(page).get("items").toString().contains("IT_E08T04_OP"));

        // IP 精确过滤：另一条数据访问日志在 data-access 页签
        Map<?, ?> byIpResponse =
                post(
                        "/api/hospital/system/audit/data-access/search",
                        token,
                        Map.of(
                                "startTime", windowStart(),
                                "endTime", windowEnd(),
                                "ip", IP + ".7.2"),
                        null);
        assertEquals(0, codeOf(byIpResponse), "查询失败：" + byIpResponse);
        Map<?, ?> byIp = data(byIpResponse);
        assertTrue(map(byIp).get("items").toString().contains("VIEW_PLAIN"), "IP 过滤应命中数据访问日志");
    }

    @Test
    void detailShowsBeforeAfterAndTraceLinks() {
        String token = login(ADMIN);
        Long opId =
                runInTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM aud_log WHERE module = ? AND action ="
                                                + " 'IT_E08T04_OP'",
                                        Long.class,
                                        MARKER));
        Map<?, ?> detailResponse = get("/api/hospital/system/audit/operation/" + opId, token, null);
        assertEquals(0, codeOf(detailResponse), "详情失败：" + detailResponse);
        Map<?, ?> detail = data(detailResponse);
        assertEquals("PATIENT", map(detail.get("row")).get("objectType"));
        assertEquals("{\"status\":\"ENABLED\"}", detail.get("beforeJson"));
        assertNotNull(detail.get("afterJson"));
        // 敏感值只存摘要：详情里的 afterJson 是哈希摘要，不含明文手机号
        assertTrue(
                detail.get("afterJson").toString().contains("sha256:"),
                "敏感字段应只存摘要：" + detail.get("afterJson"));
        // 同 traceId 关联：操作日志详情应链到数据访问日志
        assertTrue(detail.get("traceLinks").toString().contains("DATA_ACCESS"));
    }

    @Test
    void patientLogsTabReturnsPatientScopedRows() {
        String token = login(ADMIN);
        Map<?, ?> logsResponse = get("/api/hospital/followup/patients/99001/logs", token, null);
        assertEquals(0, codeOf(logsResponse), "患者日志失败：" + logsResponse);
        Map<?, ?> page = data(logsResponse);
        assertTrue(((Number) map(page).get("total")).longValue() >= 2L);
        assertTrue(page.get("items").toString().contains("IT_E08T04_OP"));
        assertTrue(page.get("items").toString().contains("VIEW_PLAIN"));
    }

    @Test
    void searchRejectsSpanOverThreeMonths() {
        String token = login(ADMIN);
        Map<?, ?> response =
                post(
                        "/api/hospital/system/audit/operation/search",
                        token,
                        Map.of(
                                "startTime", "2026-01-01T00:00:00+08:00",
                                "endTime", "2026-12-31T00:00:00+08:00"),
                        null);
        assertEquals(40001, codeOf(response));
    }

    @Test
    void viewerWithoutAuditPermissionGets40300() {
        String token = login(VIEWER);
        assertEquals(
                40300,
                codeOf(
                        post(
                                "/api/hospital/system/audit/operation/search",
                                token,
                                Map.of("startTime", windowStart(), "endTime", windowEnd()),
                                null)));
    }

    // ---------------------------------------------------------------- 导出与审批（PLT-AUD-007）

    @Test
    void tenantAdminSelfExportSkipsApprovalAndIsAudited() {
        String token = login(ADMIN);
        Map<?, ?> submittedResponse =
                post(
                        "/api/hospital/system/audit/exports",
                        token,
                        Map.of(
                                "type", "operation",
                                "condition",
                                        Map.of(
                                                "startTime", windowStart(),
                                                "endTime", windowEnd(),
                                                "module", MARKER),
                                "reason", "E08-T04 用例（示例）"),
                        null);
        assertEquals(0, codeOf(submittedResponse), "提交失败：" + submittedResponse);
        Map<?, ?> submitted = data(submittedResponse);
        assertEquals("GENERATING", submitted.get("status"), "租户管理员本人导出无需审批");
        long exportId = ((Number) submitted.get("exportId")).longValue();
        awaitReady(token, exportId);

        // 下载：CSV 首列水印 + 命中探针行
        byte[] body = downloadTenant(token, exportId);
        String csv = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("nurse.demo"), "导出文件应含水印（账号）");
        assertTrue(csv.contains("IT_E08T04_OP"), "导出文件应包含命中行");
        assertTrue(csv.startsWith("\uFEFF"), "CSV 应带 BOM");

        // 导出本身记审计：EXPORT + DOWNLOAD 各一条（DOWNLOAD 带文件哈希）
        runInTenant(
                () -> {
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_data_access_log WHERE module = 'system'"
                                            + " AND access_method = 'EXPORT' AND object_id = ?",
                                    Long.class,
                                    String.valueOf(exportId)),
                            "提交导出应写 EXPORT 审计");
                    assertEquals(
                            1L,
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM aud_data_access_log WHERE module = 'system'"
                                            + " AND access_method = 'DOWNLOAD' AND object_id = ? AND"
                                            + " file_hash IS NOT NULL",
                                    Long.class,
                                    String.valueOf(exportId)),
                            "下载应写 DOWNLOAD 审计（含文件哈希）");
                });
    }

    @Test
    void normalUserExportNeedsTenantAdminApproval() {
        String token = login(USER);
        Map<?, ?> submittedResponse =
                post(
                        "/api/hospital/system/audit/exports",
                        token,
                        Map.of(
                                "type", "data-access",
                                "condition",
                                        Map.of(
                                                "startTime", windowStart(),
                                                "endTime", windowEnd(),
                                                "module", MARKER),
                                "reason", "E08-T04 审批用例（示例）"),
                        null);
        assertEquals(0, codeOf(submittedResponse), "提交失败：" + submittedResponse);
        Map<?, ?> submitted = data(submittedResponse);
        assertEquals("PENDING_APPROVAL", submitted.get("status"));
        long exportId = ((Number) submitted.get("exportId")).longValue();

        // 未审批时下载 → 40901（import-export.md §4）
        assertEquals(
                40901,
                codeOf(
                        get(
                                "/api/hospital/system/audit/exports/" + exportId + "/download",
                                token,
                                null)));

        // 普通用户无审批权限 → 40300
        assertEquals(
                40300,
                codeOf(
                        post(
                                "/api/hospital/system/export-approvals/" + exportId + "/approve",
                                token,
                                Map.of("comment", "自审应被拒"),
                                null)));

        // 租户管理员看到待审批并通过
        String admin = login(ADMIN);
        Map<?, ?> pendingResponse =
                get("/api/hospital/system/export-approvals?status=PENDING", admin, null);
        assertEquals(0, codeOf(pendingResponse), "待审批列表失败：" + pendingResponse);
        Map<?, ?> pending = data(pendingResponse);
        assertTrue(
                map(pending).get("items").toString().contains(String.valueOf(exportId)),
                "待审批列表应包含该申请");
        Map<?, ?> approvedResponse =
                post(
                        "/api/hospital/system/export-approvals/" + exportId + "/approve",
                        admin,
                        Map.of("comment", "同意（示例）"),
                        null);
        assertEquals(0, codeOf(approvedResponse), "审批失败：" + approvedResponse);
        Map<?, ?> approved = data(approvedResponse);
        assertEquals("GENERATING", approved.get("status"));
        awaitReady(token, exportId);
        assertTrue(downloadTenant(token, exportId).length > 0, "审批通过后申请人可下载");

        // 审批写 OPERATION 审计（前后值 = 状态迁移）
        runInTenant(
                () ->
                        assertEquals(
                                1L,
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE action ="
                                                + " 'AUDIT_EXPORT_APPROVE' AND object_id = ? AND"
                                                + " before_json LIKE '%PENDING_APPROVAL%' AND after_json"
                                                + " LIKE '%GENERATING%'",
                                        Long.class, String.valueOf(exportId))));

        // 重复审批 → 40901
        assertEquals(
                40901,
                codeOf(
                        post(
                                "/api/hospital/system/export-approvals/" + exportId + "/approve",
                                admin,
                                Map.of("comment", "重复审批"),
                                null)));
    }

    @Test
    void platformExportRequiresAnotherPlatformAdmin() {
        String admin = loginPlatform("platform.admin");
        Map<?, ?> submittedResponse =
                post(
                        "/api/platform/audit/exports",
                        admin,
                        Map.of(
                                "type", "operation",
                                "condition",
                                        Map.of(
                                                "startTime", windowStart(),
                                                "endTime", windowEnd(),
                                                "module", MARKER),
                                "reason", "E08-T04 平台导出（示例）"),
                        null);
        assertEquals(0, codeOf(submittedResponse), "提交失败：" + submittedResponse);
        Map<?, ?> submitted = data(submittedResponse);
        assertEquals("PENDING_APPROVAL", submitted.get("status"), "平台账号导出一律待审批");
        long exportId = ((Number) submitted.get("exportId")).longValue();

        // 平台端查询（PLT-AUD-005 按租户 + 控制面过滤）
        Map<?, ?> searchedResponse =
                post(
                        "/api/platform/audit/control-plane/search",
                        admin,
                        Map.of(
                                "startTime",
                                windowStart(),
                                "endTime",
                                windowEnd(),
                                "tenantId",
                                1,
                                "module",
                                MARKER),
                        null);
        assertEquals(0, codeOf(searchedResponse), "查询失败：" + searchedResponse);
        Map<?, ?> searched = data(searchedResponse);
        assertTrue(
                map2(searched).get("items").toString().contains("IT_E08T04_PLT"),
                "控制面（tenant_id 非空）应命中平台探针");

        // 申请人不能审批自己的申请（PLT-AUD-007 已确认）
        assertEquals(
                40901,
                codeOf(
                        post(
                                "/api/platform/audit/export-approvals/" + exportId + "/approve",
                                admin,
                                Map.of("comment", "自审"),
                                null)));

        // 另一名平台管理员审批 → 生成 → 原申请人下载
        String second = loginPlatform(PLT_SECOND);
        Map<?, ?> approvedResponse =
                post(
                        "/api/platform/audit/export-approvals/" + exportId + "/approve",
                        second,
                        Map.of("comment", "同意（示例）"),
                        null);
        assertEquals(0, codeOf(approvedResponse), "审批失败：" + approvedResponse);
        Map<?, ?> approved = data(approvedResponse);
        awaitReadyPlatform(admin, exportId);
        byte[] body = downloadPlatform(admin, exportId);
        String csv = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(csv.contains("platform.admin"), "平台导出文件应含水印（账号）");
        assertTrue(csv.contains("IT_E08T04_PLT"));

        // 平台导出全程记审计：EXPORT / DOWNLOAD 落平台访问日志（PLT-AUD-004 仅存平台侧）
        TenantContext.runAsPlatform(
                () -> {
                    assertEquals(
                            1L,
                            platform.queryForObject(
                                    "SELECT COUNT(*) FROM aud_platform_access_log WHERE module ="
                                            + " 'platform' AND access_method = 'EXPORT' AND object_id = ?",
                                    Long.class,
                                    String.valueOf(exportId)));
                    assertEquals(
                            1L,
                            platform.queryForObject(
                                    "SELECT COUNT(*) FROM aud_platform_access_log WHERE module ="
                                            + " 'platform' AND access_method = 'DOWNLOAD' AND object_id ="
                                            + " ?",
                                    Long.class,
                                    String.valueOf(exportId)));
                });
    }

    @Test
    void hospitalTokenCannotAccessPlatformAudit() {
        String token = login(ADMIN);
        assertEquals(
                40300,
                codeOf(
                        post(
                                "/api/platform/audit/operation/search",
                                token,
                                Map.of("startTime", windowStart(), "endTime", windowEnd()),
                                null)));
    }

    @Test
    void integrityReturnsLatestChainChecks() {
        String admin = loginPlatform("platform.admin");
        Map<?, ?> response = get("/api/platform/audit/integrity", admin, null);
        assertEquals(0, codeOf(response));
        // 平台库自身（tenantId 为空）：三张表各一行最近结果
        Object list = response.get("data");
        assertNotNull(list);
        assertTrue(list.toString().contains("plt_audit_log"), "应返回平台库验链结果");
    }

    // ---------------------------------------------------------------- 辅助

    private String login(String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", TENANT, "username", username, "password", PASSWORD),
                        null);
        assertEquals(0, codeOf(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    private String loginPlatform(String username) {
        Map<?, ?> response =
                post(
                        "/api/platform/auth/login",
                        null,
                        Map.of("username", username, "password", PASSWORD),
                        null);
        assertEquals(0, codeOf(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    private void awaitReady(String token, long exportId) {
        awaitStatus("/api/hospital/system/audit/exports", token, exportId);
    }

    private void awaitReadyPlatform(String token, long exportId) {
        awaitStatus("/api/platform/audit/exports", token, exportId);
    }

    private void awaitStatus(String listPath, String token, long exportId) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Map<?, ?> mine = data(get(listPath, token, null));
            for (Object item : (java.util.List<?>) map(mine).get("items")) {
                if (((Number) map2(item).get("id")).longValue() == exportId) {
                    String status = (String) map2(item).get("status");
                    if ("READY".equals(status) || "FAILED".equals(status)) {
                        assertEquals("READY", status, "导出生成失败：" + map2(item).get("failReason"));
                        return;
                    }
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("导出未在 15 秒内生成完成");
    }

    private byte[] downloadTenant(String token, long exportId) {
        return rest.exchange(
                        "/api/hospital/system/audit/exports/" + exportId + "/download",
                        HttpMethod.GET,
                        new HttpEntity<>(jsonHeaders(token)),
                        byte[].class)
                .getBody();
    }

    private byte[] downloadPlatform(String token, long exportId) {
        return rest.exchange(
                        "/api/platform/audit/exports/" + exportId + "/download",
                        HttpMethod.GET,
                        new HttpEntity<>(jsonHeaders(token)),
                        byte[].class)
                .getBody();
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body, String ip) {
        HttpHeaders headers = jsonHeaders(token);
        if (ip != null) {
            headers.set("X-Forwarded-For", ip);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> get(String path, String token, String ip) {
        HttpHeaders headers = jsonHeaders(token);
        if (ip != null) {
            headers.set("X-Forwarded-For", ip);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        headers.set("User-Agent", "IT-E08T04-UA/1.0");
        return headers;
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }

    private static Map<?, ?> items(Map<?, ?> page) {
        return page == null ? Map.of() : page;
    }

    private static Map<?, ?> map(Object value) {
        return value == null ? Map.of() : (Map<?, ?>) value;
    }

    private static Map<?, ?> map2(Object value) {
        return map(value);
    }

    private void runInTenant(Runnable action) {
        TenantContext.runAsTenant(1L, TENANT, action);
    }

    private <T> T runInTenant(java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(1L, TENANT, action::get);
    }

    /**
     * 查询窗口按租户库钟面构造：本地 / CI 的 MySQL 容器时钟是 UTC，而连接时区是北京时间， created_at（DEFAULT
     * CURRENT_TIMESTAMP）落在库内钟面上。窗口直接取库内 NOW() ± 2 小时并按 +08:00 原样传参（parseTime 只换算时区不改钟面
     * 语义），保证与行数据同一钟面比较；生产 RDS 时区为北京时间时两者天然一致。
     */
    private LocalDateTime dbNow() {
        return runInTenant(() -> jdbc.queryForObject("SELECT NOW()", LocalDateTime.class));
    }

    private String iso(LocalDateTime time) {
        return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'+08:00'"));
    }

    private String windowStart() {
        return iso(dbNow().minusHours(2));
    }

    private String windowEnd() {
        return iso(dbNow().plusHours(2));
    }
}
