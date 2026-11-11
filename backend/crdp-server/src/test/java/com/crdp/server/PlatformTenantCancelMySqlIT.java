package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.crypto.ArchiveCipher;
import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.storage.ObjectStorage;
import com.crdp.framework.tenant.TenantContext;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E05-T08 端到端（验收：注销需审批；销毁出证明）： 申请（停用态 + 书面确认）→ 另一名平台管理员审批（注销中禁登录）→ 冷静期撤销 / 重发起 → 冷静期满生成迁出包（标准结构 +
 * 加密 + 限时下载，本地解密核对 manifest）→ 签收 → 封存法定保留数据（独立密码）→ 双人复核销毁（删库 / 账号 / 对象 / 会话）→ 销毁证明 PDF（下载）→
 * 保留期满补充销毁证明。 门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformTenantCancelMySqlIT {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Shanghai"));
    private static final byte[] PDF_BODY = "%PDF-1.4 书面确认（示例）".getBytes(StandardCharsets.UTF_8);

    private JdbcTemplate root;
    private JdbcTemplate platform;
    private final List<Long> createdTenantIds = new ArrayList<>();
    private final List<String> createdCodes = new ArrayList<>();
    private boolean helperAccountsCreated;

    @Autowired private TestRestTemplate rest;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CryptoService crypto;
    @Autowired private ArchiveCipher archiveCipher;
    @Autowired private ObjectStorage storage;

    private void connect() {
        if (platform != null) {
            return;
        }
        String port = System.getenv("CRDP_IT_MYSQL_PORT");
        root =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/?useSSL=false&allowPublicKeyRetrieval=true",
                                "root",
                                "DevOnly#root2026"));
        platform =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                "jdbc:mysql://127.0.0.1:"
                                        + port
                                        + "/crdp_platform?useSSL=false&allowPublicKeyRetrieval=true",
                                "crdp_app",
                                "DevOnly#app2026"));
    }

    @AfterAll
    void cleanUp() {
        if (platform == null) {
            return;
        }
        for (String code : createdCodes) {
            root.execute("DROP DATABASE IF EXISTS t_" + code);
            root.execute("DROP USER IF EXISTS `t_" + code + "`@'%'");
            List<Long> ids =
                    platform.queryForList(
                            "SELECT id FROM plt_tenant WHERE code = ?", Long.class, code);
            for (Long id : ids) {
                AuditTableMaintenance.delete(
                        platform,
                        "DELETE FROM plt_audit_log WHERE tenant_id = ? OR target = ?",
                        id,
                        "plt_tenant:" + code);
                AuditTableMaintenance.delete(
                        platform, "DELETE FROM plt_tenant_retention WHERE tenant_id = ?", id);
                AuditTableMaintenance.delete(
                        platform, "DELETE FROM plt_destroy_certificate WHERE tenant_id = ?", id);
                AuditTableMaintenance.delete(
                        platform, "DELETE FROM plt_tenant_export WHERE tenant_id = ?", id);
                AuditTableMaintenance.delete(
                        platform, "DELETE FROM plt_cancel_file WHERE tenant_id = ?", id);
                AuditTableMaintenance.delete(
                        platform, "DELETE FROM plt_tenant_cancel WHERE tenant_id = ?", id);
                AuditTableMaintenance.delete(
                        platform, "DELETE FROM plt_provision_step WHERE tenant_id = ?", id);
                AuditTableMaintenance.delete(
                        platform, "DELETE FROM plt_tenant_suspension WHERE tenant_id = ?", id);
                AuditTableMaintenance.delete(
                        platform, "DELETE FROM plt_tenant_resource WHERE tenant_id = ?", id);
                AuditTableMaintenance.delete(
                        platform, "DELETE FROM plt_tenant_module WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant WHERE id = ?", id);
            }
        }
        if (helperAccountsCreated) {
            platform.update(
                    "DELETE ar FROM plt_account_role ar JOIN plt_account a ON a.id = ar.account_id"
                            + " WHERE a.username IN ('platform.admin2.it', 'platform.viewer.it')");
            platform.update(
                    "DELETE FROM plt_account WHERE username IN ('platform.admin2.it',"
                            + " 'platform.viewer.it')");
            platform.update(
                    "DELETE rm FROM plt_role_menu rm JOIN plt_role r ON r.id = rm.role_id"
                            + " WHERE r.code = 'PLATFORM_VIEWER_IT'");
            platform.update("DELETE FROM plt_role WHERE code = 'PLATFORM_VIEWER_IT'");
            AuditTableMaintenance.delete(
                    platform,
                    "DELETE FROM plt_audit_log WHERE event_type = 'TENANT_CANCEL'"
                            + " AND actor_id NOT IN (SELECT id FROM plt_account)");
        }
    }

    // ---------------------------------------------------------------- 工具

    private String loginPlatform() {
        return loginPlatformAs("platform.admin");
    }

    private String loginPlatformAs(String username) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> login =
                rest.postForEntity(
                                "/api/platform/auth/login",
                                new HttpEntity<>(
                                        Map.of("username", username, "password", "Demo@2026"),
                                        headers),
                                Map.class)
                        .getBody();
        return ((Map<?, ?>) login.get("data")).get("token").toString();
    }

    private String reauthOf(String token) {
        Map<?, ?> response =
                body(
                        HttpMethod.POST,
                        "/api/platform/auth/reauth",
                        new HttpEntity<>(Map.of("password", "Demo@2026"), bearer(token)));
        assertEquals(0, code(response), () -> String.valueOf(response));
        return ((Map<?, ?>) response.get("data")).get("reauthToken").toString();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(HttpMethod method, String url, HttpEntity<?> entity) {
        return (Map<String, Object>)
                rest.exchange(URI.create(url), method, entity, Map.class).getBody();
    }

    private static int code(Map<?, ?> response) {
        Object code = response == null ? null : response.get("code");
        return code == null ? -1 : Integer.parseInt(code.toString());
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private long createTenant(String name) {
        Map<?, ?> response =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants",
                        new HttpEntity<>(
                                Map.of(
                                        "name",
                                        name,
                                        "adminUsername",
                                        "admin",
                                        "adminDisplayName",
                                        name + "管理员（示例）",
                                        "adminInitialPassword",
                                        "Init@2026#"),
                                bearer(loginPlatform())));
        assertEquals(0, code(response), () -> String.valueOf(response));
        long tenantId = ((Number) data(response).get("tenantId")).longValue();
        createdTenantIds.add(tenantId);
        createdCodes.add(codeOf(tenantId));
        return tenantId;
    }

    private String codeOf(long tenantId) {
        return platform.queryForObject(
                "SELECT code FROM plt_tenant WHERE id = ?", String.class, tenantId);
    }

    /** 转正后手动停用（注销申请的前置状态）。 */
    private void disableTenant(long tenantId, String token) {
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/status",
                                new HttpEntity<>(
                                        Map.of("action", "ENABLE", "reason", "转正（示例）"),
                                        bearer(token)))));
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/status",
                                new HttpEntity<>(
                                        Map.of("action", "DISABLE", "reason", "停止服务（示例）"),
                                        bearer(token)))));
        assertEquals("DISABLED", statusInDb(tenantId));
    }

    private String statusInDb(long tenantId) {
        return platform.queryForObject(
                "SELECT status FROM plt_tenant WHERE id = ?", String.class, tenantId);
    }

    private String stageInDb(long tenantId) {
        return platform.queryForObject(
                "SELECT stage FROM plt_tenant_cancel WHERE tenant_id = ?", String.class, tenantId);
    }

    private long secondAdminId() {
        return platform.queryForObject(
                "SELECT id FROM plt_account WHERE username = 'platform.admin2.it'", Long.class);
    }

    private void ensureSecondAdmin() {
        if (helperAccountsCreated) {
            return;
        }
        platform.update(
                "INSERT INTO plt_account (username, display_name, password_hash, status)"
                        + " VALUES ('platform.admin2.it', '第二平台管理员（示例）', ?, 'ENABLED')",
                passwordEncoder.encode("Demo@2026"));
        platform.update(
                "INSERT INTO plt_account_role (account_id, role_id)"
                        + " SELECT a.id, r.id FROM plt_account a, plt_role r"
                        + " WHERE a.username = 'platform.admin2.it' AND r.code = 'PLATFORM_ADMIN'");
        helperAccountsCreated = true;
    }

    /** multipart 文件体：带文件名的 ByteArrayResource（RestTemplate 的 FormHttpMessageConverter 可序列化）。 */
    private static org.springframework.core.io.ByteArrayResource pdfResource(String fileName) {
        return new org.springframework.core.io.ByteArrayResource(PDF_BODY) {
            @Override
            public String getFilename() {
                return fileName;
            }
        };
    }

    private long uploadConfirmation(long tenantId, String token) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        org.springframework.util.LinkedMultiValueMap<String, Object> form =
                new org.springframework.util.LinkedMultiValueMap<>();
        form.add("file", pdfResource("hospital-confirmation.pdf"));
        Map<?, ?> response =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantId + "/cancel/confirmation-file",
                        new HttpEntity<>(form, headers));
        assertEquals(0, code(response), () -> String.valueOf(response));
        return ((Number) data(response).get("id")).longValue();
    }

    /** 走完 申请 → 审批（第二管理员）→（可选）冷静期满。 */
    private void applyAndApprove(long tenantId, String token, List<Map<String, Object>> retention) {
        ensureSecondAdmin();
        long fileId = uploadConfirmation(tenantId, token);
        Map<String, Object> request = new HashMap<>();
        request.put("reason", "医院主动终止合作（示例）");
        request.put("confirmationFileId", fileId);
        request.put("retentionItems", retention);
        Map<?, ?> applied =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantId + "/cancel",
                        new HttpEntity<>(request, bearer(token)));
        assertEquals(0, code(applied), () -> String.valueOf(applied));
        String secondToken = loginPlatformAs("platform.admin2.it");
        Map<?, ?> approved =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantId + "/cancel/approve",
                        new HttpEntity<>(
                                Map.of("decision", "APPROVE", "note", "书面确认已核（示例）"),
                                bearer(secondToken)));
        assertEquals(0, code(approved), () -> String.valueOf(approved));
    }

    private void elapseCooling(long tenantId) {
        platform.update(
                "UPDATE plt_tenant_cancel SET cooling_until = DATE_SUB(CURRENT_TIMESTAMP(3),"
                        + " INTERVAL 1 DAY) WHERE tenant_id = ?",
                tenantId);
    }

    // ---------------------------------------------------------------- 用例 1：申请条件、双人审批、禁登录、冷静期撤销与重发起

    @Test
    void applyRequiresDisabledTenantAndApprovalByAnotherAdmin() {
        connect();
        String token = loginPlatform();
        long tenantId = createTenant("虚构示例医院癸");

        // 启用状态不能发起注销（先转正停用才能发起）
        assertEquals(
                40901,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/cancel",
                                new HttpEntity<>(
                                        Map.of("reason", "未停用（示例）", "confirmationFileId", 1L),
                                        bearer(token)))));

        disableTenant(tenantId, token);
        long fileId = uploadConfirmation(tenantId, token);

        // 申请校验：缺书面确认 / 缺原因 / 未知确认文件
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/cancel",
                                new HttpEntity<>(Map.of("reason", "缺书面确认（示例）"), bearer(token)))));
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/cancel",
                                new HttpEntity<>(
                                        Map.of("confirmationFileId", fileId), bearer(token)))));
        assertEquals(
                40400,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/cancel",
                                new HttpEntity<>(
                                        Map.of(
                                                "reason",
                                                "确认文件不存在（示例）",
                                                "confirmationFileId",
                                                999999L),
                                        bearer(token)))));

        Map<?, ?> applied =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantId + "/cancel",
                        new HttpEntity<>(
                                Map.of(
                                        "reason",
                                        "医院主动终止合作（示例）",
                                        "confirmationFileId",
                                        fileId,
                                        "retentionItems",
                                        List.of(
                                                Map.of(
                                                        "scope", "RCT 原始资料",
                                                        "legalBasis", "GCP（示例）",
                                                        "retainUntil", "2099-12-31"))),
                                bearer(token)));
        assertEquals(0, code(applied), () -> String.valueOf(applied));
        assertEquals("PENDING_APPROVAL", stageInDb(tenantId));
        assertEquals("DISABLED", statusInDb(tenantId), "审批前租户保持停用");

        // 发起人不能自审（双人复核）
        assertEquals(
                40901,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/cancel/approve",
                                new HttpEntity<>(Map.of("decision", "APPROVE"), bearer(token)))));

        ensureSecondAdmin();
        String secondToken = loginPlatformAs("platform.admin2.it");
        Map<?, ?> approved =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantId + "/cancel/approve",
                        new HttpEntity<>(
                                Map.of("decision", "APPROVE", "note", "书面确认已核（示例）"),
                                bearer(secondToken)));
        assertEquals(0, code(approved), () -> String.valueOf(approved));
        assertEquals("COOLING", stageInDb(tenantId));
        assertEquals("CANCELLING", statusInDb(tenantId), "审批通过 → 注销中");

        // 注销中不可登录（登录闸门按租户状态拒绝）
        Map<?, ?> hospitalLogin =
                body(
                        HttpMethod.POST,
                        "/api/hospital/auth/login",
                        new HttpEntity<>(
                                Map.of(
                                        "tenantCode", codeOf(tenantId),
                                        "username", "admin",
                                        "password", "Init@2026#"),
                                new HttpHeaders()));
        assertEquals(40101, code(hospitalLogin), "注销中租户不得登录");

        // 冷静期未满：不能生成迁出包、不能撤销后以外的状态流转（续期被拒）
        assertEquals(
                40901,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/cancel/exports",
                                new HttpEntity<>(null, bearer(token)))));
        assertEquals(
                40901,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + tenantId + "/expire",
                                new HttpEntity<>(
                                        Map.of(
                                                "expireType", "LONG_TERM",
                                                "reason", "注销中不能续期（示例）"),
                                        bearer(token)))));

        // 审计与冷静期登记
        assertNotNull(
                platform.queryForObject(
                        "SELECT detail FROM plt_audit_log WHERE event_type = 'TENANT_CANCEL'"
                                + " AND action = 'APPROVE' AND target = ?",
                        String.class,
                        "plt_tenant:" + codeOf(tenantId)));
        assertNotNull(
                platform.queryForObject(
                        "SELECT cooling_until FROM plt_tenant_cancel WHERE tenant_id = ?",
                        java.sql.Timestamp.class,
                        tenantId),
                "审批通过登记冷静期截止");

        // 冷静期内撤销 → 回到停用；再重新发起（重置流程行）
        Map<?, ?> revoked =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantId + "/cancel/revoke",
                        new HttpEntity<>(Map.of("reason", "医院回心转意（示例）"), bearer(token)));
        assertEquals(0, code(revoked));
        assertEquals("REVOKED", stageInDb(tenantId));
        assertEquals("DISABLED", statusInDb(tenantId));

        Map<?, ?> reapplied =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantId + "/cancel",
                        new HttpEntity<>(
                                Map.of("reason", "重新发起（示例）", "confirmationFileId", fileId),
                                bearer(token)));
        assertEquals(0, code(reapplied), "撤销后可重新发起");
        assertEquals("PENDING_APPROVAL", stageInDb(tenantId));

        // 驳回：回到停用，可再发起
        Map<?, ?> rejected =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantId + "/cancel/approve",
                        new HttpEntity<>(
                                Map.of("decision", "REJECT", "note", "书面确认缺章（示例）"),
                                bearer(secondToken)));
        assertEquals(0, code(rejected));
        assertEquals("REJECTED", stageInDb(tenantId));
        assertEquals("DISABLED", statusInDb(tenantId));
    }

    // ---------------------------------------------------------------- 用例 2：迁出包 → 签收 → 封存 → 销毁 → 证明

    @Test
    void exportAckSealDestroyAndCertificate() throws Exception {
        connect();
        String token = loginPlatform();
        long tenantId = createTenant("虚构示例医院甲甲");
        disableTenant(tenantId, token);
        applyAndApprove(
                tenantId,
                token,
                List.of(
                        Map.of(
                                "scope", "RCT 原始资料",
                                "legalBasis", "GCP（示例）",
                                "retainUntil", "2099-12-31")));
        elapseCooling(tenantId);

        // 生成迁出包（标准结构 + 加密 + 限时下载）
        Map<?, ?> generated =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantId + "/cancel/exports",
                        new HttpEntity<>(null, bearer(token)));
        assertEquals(0, code(generated), () -> String.valueOf(generated));
        long exportId = ((Number) data(generated).get("exportId")).longValue();
        assertTrue(((Number) data(generated).get("tableCount")).intValue() > 10, "结构化数据表数");
        assertTrue(((Number) data(generated).get("totalRows")).longValue() >= 0);
        assertEquals("EXPORTED", stageInDb(tenantId));
        Map<String, Object> exportRow =
                platform.queryForMap(
                        "SELECT password_cipher, expires_at, download_count FROM plt_tenant_export"
                                + " WHERE id = ?",
                        exportId);
        assertNotNull(exportRow.get("password_cipher"), "解压密码信封加密存档");
        assertNotNull(exportRow.get("expires_at"), "下载链接登记有效期");

        // 下载：无二次认证 40304；有 X-Reauth-Token 得到加密包并可本地解密核对 manifest
        ResponseEntity<byte[]> noReauth =
                rest.exchange(
                        URI.create(
                                "/api/platform/tenants/"
                                        + tenantId
                                        + "/cancel/exports/"
                                        + exportId
                                        + "/download"),
                        HttpMethod.GET,
                        new HttpEntity<>(bearer(token)),
                        byte[].class);
        assertEquals(403, noReauth.getStatusCode().value(), "缺 X-Reauth-Token 返回 40304");
        assertTrue(new String(noReauth.getBody()).contains("40304"));

        HttpHeaders downloadHeaders = bearer(token);
        downloadHeaders.set("X-Reauth-Token", reauthOf(token));
        ResponseEntity<byte[]> downloaded =
                rest.exchange(
                        URI.create(
                                "/api/platform/tenants/"
                                        + tenantId
                                        + "/cancel/exports/"
                                        + exportId
                                        + "/download"),
                        HttpMethod.GET,
                        new HttpEntity<>(downloadHeaders),
                        byte[].class);
        assertEquals(200, downloaded.getStatusCode().value());
        String fileName = downloaded.getHeaders().getContentDisposition().getFilename().toString();
        assertTrue(fileName.endsWith(".crdpa"), "迁出包为 .crdpa 加密容器：" + fileName);
        String password =
                TenantContext.callAsPlatform(
                        () -> crypto.decrypt(String.valueOf(exportRow.get("password_cipher"))));
        byte[] zipBytes = archiveCipher.open(downloaded.getBody(), password.toCharArray());
        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.add(entry.getName());
            }
        }
        assertTrue(entries.contains("manifest.json"), "包含清单：" + entries);
        assertTrue(
                entries.stream().anyMatch(name -> name.startsWith("data/sys_user.")),
                "包含结构化数据：" + entries);
        assertTrue(entries.contains("dictionary/data-dictionary.csv"), "数据字典：" + entries);
        assertEquals(
                1,
                platform.queryForObject(
                        "SELECT download_count FROM plt_tenant_export WHERE id = ?",
                        Integer.class,
                        exportId),
                "下载计数");

        // 链接过期 → 40901；重新生成（reissue）
        platform.update(
                "UPDATE plt_tenant_export SET expires_at = DATE_SUB(CURRENT_TIMESTAMP(3),"
                        + " INTERVAL 1 DAY) WHERE id = ?",
                exportId);
        assertEquals(
                40901,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/tenants/"
                                        + tenantId
                                        + "/cancel/exports/"
                                        + exportId
                                        + "/download",
                                new HttpEntity<>(downloadHeaders))));
        Map<?, ?> reissued =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantId + "/cancel/exports",
                        new HttpEntity<>(null, bearer(token)));
        assertEquals(0, code(reissued), "过期可重新生成");
        assertTrue((Boolean) data(reissued).get("reissued"));

        // 签收：说明必填；上传签收单后签收 → ACKED
        long ackFileId;
        {
            HttpHeaders headers = bearer(token);
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            org.springframework.util.LinkedMultiValueMap<String, Object> form =
                    new org.springframework.util.LinkedMultiValueMap<>();
            form.add("file", pdfResource("ack-letter.pdf"));
            Map<?, ?> uploaded =
                    body(
                            HttpMethod.POST,
                            "/api/platform/tenants/" + tenantId + "/cancel/ack-file",
                            new HttpEntity<>(form, headers));
            assertEquals(0, code(uploaded));
            ackFileId = ((Number) data(uploaded).get("id")).longValue();
        }
        long reissuedId = ((Number) data(reissued).get("exportId")).longValue();
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/"
                                        + tenantId
                                        + "/cancel/exports/"
                                        + reissuedId
                                        + "/ack",
                                new HttpEntity<>(Map.of("ackFileId", ackFileId), bearer(token)))),
                "签收说明必填");
        Map<?, ?> acked =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/"
                                + tenantId
                                + "/cancel/exports/"
                                + reissuedId
                                + "/ack",
                        new HttpEntity<>(
                                Map.of("note", "医院校验无误（示例）", "ackFileId", ackFileId),
                                bearer(token)));
        assertEquals(0, code(acked), () -> String.valueOf(acked));
        assertEquals("ACKED", stageInDb(tenantId));

        // 销毁前置：未封存的登记项阻断（销毁接口须二次认证，先换 reauthToken）
        HttpHeaders probeHeaders = bearer(token);
        probeHeaders.set("X-Reauth-Token", reauthOf(token));
        assertEquals(
                40901,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/cancel/destroy",
                                new HttpEntity<>(
                                        Map.of("reviewerId", secondAdminId(), "reason", "未封存（示例）"),
                                        probeHeaders))),
                "有未封存保留项不得销毁");

        // 封存：登记项带表 sys_user → 独立密码加密封存
        Map<?, ?> sealed =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantId + "/cancel/retention/seal",
                        new HttpEntity<>(Map.of("reason", "法定保留（示例）"), bearer(token)));
        assertEquals(0, code(sealed), () -> String.valueOf(sealed));
        assertEquals(1, ((Number) data(sealed).get("sealedCount")).intValue());
        Map<String, Object> retentionRow =
                platform.queryForMap(
                        "SELECT status, sealed_object_key, sealed_key_cipher FROM"
                                + " plt_tenant_retention WHERE tenant_id = ?",
                        tenantId);
        assertEquals("SEALED", retentionRow.get("status"));
        assertNotNull(retentionRow.get("sealed_object_key"));
        assertNotNull(retentionRow.get("sealed_key_cipher"), "独立密码信封存档");

        // 销毁：自己复核自己 40901；复核人必须是平台管理员（viewer 不行）
        assertEquals(
                40901,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/cancel/destroy",
                                new HttpEntity<>(
                                        Map.of(
                                                "reviewerId",
                                                currentAccountId(token),
                                                "reason",
                                                "自审（示例）"),
                                        probeHeaders))));

        HttpHeaders destroyHeaders = bearer(token);
        destroyHeaders.set("X-Reauth-Token", reauthOf(token));
        Map<?, ?> destroyed =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/" + tenantId + "/cancel/destroy",
                        new HttpEntity<>(
                                Map.of("reviewerId", secondAdminId(), "reason", "冷静期已满且医院已签收（示例）"),
                                destroyHeaders));
        assertEquals(0, code(destroyed), () -> String.valueOf(destroyed));
        assertEquals("CANCELLED", statusInDb(tenantId), "销毁后 → 已注销");
        assertEquals("DESTROYED", stageInDb(tenantId));
        String code = codeOf(tenantId);

        // 资源真的没了：库 / 专属账号（root 连接查 information_schema 与 mysql.user）
        assertEquals(
                0,
                root.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?",
                        Integer.class,
                        "t_" + code),
                "租户库已删除");
        assertEquals(
                0,
                root.queryForObject(
                        "SELECT COUNT(*) FROM mysql.user WHERE user = ?",
                        Integer.class,
                        "t_" + code),
                "专属账号已删除");
        assertNotNull(
                platform.queryForObject(
                        "SELECT destroyed_at FROM plt_tenant_resource WHERE tenant_id = ?",
                        java.sql.Timestamp.class,
                        tenantId),
                "资源行标记已销毁");
        assertEquals(
                "DESTROYED",
                platform.queryForObject(
                        "SELECT password_cipher FROM plt_tenant_resource WHERE tenant_id = ?",
                        String.class,
                        tenantId),
                "密码密文作废");

        // 销毁证明：行 + PDF 下载（无二次认证 40304）
        Map<String, Object> cert =
                platform.queryForMap(
                        "SELECT id, cert_no, kind, reviewed_by_name FROM plt_destroy_certificate"
                                + " WHERE tenant_id = ? AND kind = 'TENANT'",
                        tenantId);
        assertTrue(String.valueOf(cert.get("cert_no")).startsWith("DEST-"), "证明编号");
        assertTrue(
                String.valueOf(cert.get("reviewed_by_name")).contains("第二平台管理员"), "复核人=另一名平台管理员");
        ResponseEntity<byte[]> certNoReauth =
                rest.exchange(
                        URI.create(
                                "/api/platform/tenants/"
                                        + tenantId
                                        + "/cancel/certificates/"
                                        + cert.get("id")
                                        + "/download"),
                        HttpMethod.GET,
                        new HttpEntity<>(bearer(token)),
                        byte[].class);
        assertEquals(403, certNoReauth.getStatusCode().value());
        assertTrue(new String(certNoReauth.getBody()).contains("40304"));
        HttpHeaders certHeaders = bearer(token);
        certHeaders.set("X-Reauth-Token", reauthOf(token));
        ResponseEntity<byte[]> certPdf =
                rest.exchange(
                        URI.create(
                                "/api/platform/tenants/"
                                        + tenantId
                                        + "/cancel/certificates/"
                                        + cert.get("id")
                                        + "/download"),
                        HttpMethod.GET,
                        new HttpEntity<>(certHeaders),
                        byte[].class);
        assertEquals(200, certPdf.getStatusCode().value());
        assertTrue(
                new String(certPdf.getBody(), 0, 8, StandardCharsets.US_ASCII).startsWith("%PDF"),
                "证明为 PDF");
        assertTrue(certPdf.getBody().length > 500);

        // 平台库保留租户档案与审计（已注销租户行仍在、审计链完整）
        assertNotNull(
                platform.queryForObject(
                        "SELECT detail FROM plt_audit_log WHERE event_type = 'TENANT_CANCEL'"
                                + " AND action = 'DESTROY' AND target = ?",
                        String.class,
                        "plt_tenant:" + code));

        // 已注销租户不再进入作业扇出清单（JdbcTenantDirectory 口径）
        assertEquals(
                0,
                platform.queryForObject(
                        "SELECT COUNT(*) FROM plt_tenant WHERE id = ? AND status IN ('ENABLED',"
                                + " 'TRIAL', 'GRACE')",
                        Integer.class,
                        tenantId));
    }

    // ---------------------------------------------------------------- 用例 3：保留期满销毁补充证明 + 权限

    @Test
    void retentionDueDestroyIssuesSupplementaryCertificateAndPermissionGate() {
        connect();
        String token = loginPlatform();
        long tenantId = createTenant("虚构示例医院乙乙");
        disableTenant(tenantId, token);
        applyAndApprove(
                tenantId,
                token,
                List.of(
                        Map.of(
                                "scope", "审计日志",
                                "legalBasis", "等保要求（示例）",
                                "retainUntil", TODAY.plusDays(1).toString())));
        elapseCooling(tenantId);

        // 保存时替换登记项（可带封存表）
        Map<?, ?> saved =
                body(
                        HttpMethod.PUT,
                        "/api/platform/tenants/" + tenantId + "/cancel/retention",
                        new HttpEntity<>(
                                Map.of(
                                        "items",
                                        List.of(
                                                Map.of(
                                                        "scope", "审计日志",
                                                        "legalBasis", "等保要求（示例）",
                                                        "tablesCsv", "aud_log",
                                                        "retainUntil",
                                                                TODAY.plusDays(1).toString()))),
                                bearer(token)));
        assertEquals(0, code(saved), () -> String.valueOf(saved));

        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/cancel/exports",
                                new HttpEntity<>(null, bearer(token)))));
        long exportId =
                platform.queryForObject(
                        "SELECT id FROM plt_tenant_export WHERE tenant_id = ?"
                                + " ORDER BY id DESC LIMIT 1",
                        Long.class,
                        tenantId);
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/"
                                        + tenantId
                                        + "/cancel/exports/"
                                        + exportId
                                        + "/ack",
                                new HttpEntity<>(Map.of("note", "医院签收（示例）"), bearer(token)))));
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/cancel/retention/seal",
                                new HttpEntity<>(Map.of("reason", "封存审计日志（示例）"), bearer(token)))));
        HttpHeaders destroyHeaders = bearer(token);
        destroyHeaders.set("X-Reauth-Token", reauthOf(token));
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/cancel/destroy",
                                new HttpEntity<>(
                                        Map.of("reviewerId", secondAdminId(), "reason", "销毁（示例）"),
                                        destroyHeaders))));
        long itemId =
                platform.queryForObject(
                        "SELECT id FROM plt_tenant_retention WHERE tenant_id = ?",
                        Long.class,
                        tenantId);

        // 保留期未满：40901
        assertEquals(
                40901,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/"
                                        + tenantId
                                        + "/cancel/retention/"
                                        + itemId
                                        + "/destroy",
                                new HttpEntity<>(
                                        Map.of("reviewerId", secondAdminId(), "reason", "未满（示例）"),
                                        bearer(token)))),
                "保留期未满不得销毁");

        // 期满（直改库模拟时间流逝）→ 补充证明
        platform.update(
                "UPDATE plt_tenant_retention SET retain_until = DATE_SUB(CURRENT_DATE(),"
                        + " INTERVAL 1 DAY) WHERE id = ?",
                itemId);
        Map<?, ?> due =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants/"
                                + tenantId
                                + "/cancel/retention/"
                                + itemId
                                + "/destroy",
                        new HttpEntity<>(
                                Map.of("reviewerId", secondAdminId(), "reason", "保留期满（示例）"),
                                bearer(token)));
        assertEquals(0, code(due), () -> String.valueOf(due));
        assertEquals(
                "DESTROYED",
                platform.queryForObject(
                        "SELECT status FROM plt_tenant_retention WHERE id = ?",
                        String.class,
                        itemId));
        long certId =
                platform.queryForObject(
                        "SELECT certificate_id FROM plt_tenant_retention WHERE id = ?",
                        Long.class,
                        itemId);
        assertEquals(
                "RETENTION",
                platform.queryForObject(
                        "SELECT kind FROM plt_destroy_certificate WHERE id = ?",
                        String.class,
                        certId),
                "保留期满销毁出补充证明");

        // 封存对象已删除（platform 前缀下读不回）
        String sealedKey =
                platform.queryForObject(
                        "SELECT sealed_object_key FROM plt_tenant_retention WHERE id = ?",
                        String.class,
                        itemId);
        String sealedRelative = sealedKey.substring("platform/".length());
        assertNull(TenantContext.callAsPlatform(() -> storage.get(sealedRelative)), "期满销毁后封存对象删除");

        // 无权限：只读账号（platform:tenant:view）发起注销 / 销毁一律 40300
        platform.update(
                "INSERT INTO plt_role (code, name, built_in, status) VALUES"
                        + " ('PLATFORM_VIEWER_IT', '平台只读（IT 示例）', 0, 'ENABLED')");
        platform.update(
                "INSERT INTO plt_account (username, display_name, password_hash, status)"
                        + " VALUES ('platform.viewer.it', '平台只读（示例）', ?, 'ENABLED')",
                passwordEncoder.encode("Demo@2026"));
        platform.update(
                "INSERT INTO plt_account_role (account_id, role_id)"
                        + " SELECT a.id, r.id FROM plt_account a, plt_role r"
                        + " WHERE a.username = 'platform.viewer.it' AND r.code ="
                        + " 'PLATFORM_VIEWER_IT'");
        platform.update(
                "INSERT INTO plt_role_menu (role_id, menu_id)"
                        + " SELECT r.id, 111 FROM plt_role r WHERE r.code = 'PLATFORM_VIEWER_IT'");
        String viewerToken = loginPlatformAs("platform.viewer.it");

        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/tenants/" + tenantId + "/cancel",
                                new HttpEntity<>(bearer(viewerToken)))),
                "总览是只读信息，platform:tenant:view 即可查看");
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/cancel",
                                new HttpEntity<>(Map.of("reason", "无权（示例）"), bearer(viewerToken)))),
                "无 platform:tenant:cancel 不得发起注销");
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.POST,
                                "/api/platform/tenants/" + tenantId + "/cancel/destroy",
                                new HttpEntity<>(
                                        Map.of("reviewerId", secondAdminId(), "reason", "无权（示例）"),
                                        bearer(viewerToken)))),
                "无 platform:tenant:destroy 不得销毁");
        assertEquals("DESTROYED", stageInDb(tenantId), "被拒请求不得改流程阶段");
    }

    private long currentAccountId(String token) {
        Map<?, ?> me =
                body(HttpMethod.GET, "/api/platform/auth/me", new HttpEntity<>(bearer(token)));
        return ((Number) data(me).get("userId")).longValue();
    }
}
