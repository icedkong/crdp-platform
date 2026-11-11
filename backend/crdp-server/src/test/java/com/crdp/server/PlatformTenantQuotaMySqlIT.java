package com.crdp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * E05-T09 端到端（验收：默认不限；设上限后新建用户校验）。场景：
 *
 * <ol>
 *   <li>向导带初始配额建租户（userLimit / storageLimitBytes 落 plt_tenant）；配额视图带实时用量与预警； PUT 维护写 TENANT_QUOTA
 *       审计；非法上限 40001；无编辑权限 40300；恢复不限（null）后新建放行；
 *   <li>PLT-ORG-010：达到用户数上限后医院端新建（40901）、停用腾出名额后再建成功、 启用同样受配额拦截、平台上调上限后启用放行；
 *   <li>PLT-TEN-014 存储上限：存量顶到上限后上传 40001（不落台账），上调后放行。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（docker compose 的 MySQL 与 Redis），自建自清（每用例独立租户，收尾 drop 库）。 全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformTenantQuotaMySqlIT {

    private static final String ADMIN_PASSWORD = "Init@2026#";
    private static final String NEW_PASSWORD = "Quota-One@2027a";

    private JdbcTemplate root;
    private JdbcTemplate platform;
    private final List<String> createdCodes = new java.util.ArrayList<>();
    private boolean viewerCreated;

    @Autowired private TestRestTemplate rest;
    @Autowired private PasswordEncoder passwordEncoder;

    /** 应用内的路由 JdbcTemplate（TenantContext 决定库）；platform 是直连 crdp_platform 的测试连接。 */
    @Autowired private JdbcTemplate jdbc;

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
            AuditTableMaintenance.delete(
                    platform, "DELETE FROM plt_audit_log WHERE target = ?", "plt_tenant:" + code);
            List<Long> ids =
                    platform.queryForList(
                            "SELECT id FROM plt_tenant WHERE code = ?", Long.class, code);
            for (Long id : ids) {
                platform.update("DELETE FROM plt_provision_step WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_resource WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant_module WHERE tenant_id = ?", id);
                platform.update("DELETE FROM plt_tenant WHERE id = ?", id);
            }
        }
        if (viewerCreated) {
            platform.update(
                    "DELETE ar FROM plt_account_role ar JOIN plt_account a ON a.id = ar.account_id"
                            + " WHERE a.username = 'platform.viewer.quota.it'");
            platform.update("DELETE FROM plt_account WHERE username = 'platform.viewer.quota.it'");
            platform.update(
                    "DELETE rm FROM plt_role_menu rm JOIN plt_role r ON r.id = rm.role_id"
                            + " WHERE r.code = 'PLATFORM_QUOTA_VIEWER_IT'");
            platform.update("DELETE FROM plt_role WHERE code = 'PLATFORM_QUOTA_VIEWER_IT'");
        }
    }

    // ---------------------------------------------------------------- 通用助手（与其他平台 IT 同款）

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

    private Map<String, Object> data(Map<?, ?> response) {
        return (Map<String, Object>) response.get("data");
    }

    /** 建租户（走完整开通编排）并登记清理；返回 tenantId。 */
    private long createTenant(String name, Map<String, Object> extra) {
        Map<String, Object> request = new HashMap<>(extra);
        request.put("name", name);
        request.putIfAbsent("adminUsername", "admin");
        request.putIfAbsent("adminDisplayName", name + "管理员");
        request.putIfAbsent("adminInitialPassword", ADMIN_PASSWORD);
        Map<?, ?> response =
                body(
                        HttpMethod.POST,
                        "/api/platform/tenants",
                        new HttpEntity<>(request, bearer(loginPlatform())));
        assertEquals(0, code(response), () -> String.valueOf(response));
        Map<?, ?> created = data(response);
        String codeOf = created.get("code").toString();
        createdCodes.add(codeOf);
        return ((Number) created.get("tenantId")).longValue();
    }

    private String codeOf(long tenantId) {
        return platform.queryForObject(
                "SELECT code FROM plt_tenant WHERE id = ?", String.class, tenantId);
    }

    /** 医院端登录 + 首登强制改密（走产品真实流程），返回可用令牌。 */
    private String hospitalReadyToken(String tenantCode) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<?, ?> login =
                rest.postForEntity(
                                "/api/hospital/auth/login",
                                new HttpEntity<>(
                                        Map.of(
                                                "tenantCode",
                                                tenantCode,
                                                "username",
                                                "admin",
                                                "password",
                                                ADMIN_PASSWORD),
                                        headers),
                                Map.class)
                        .getBody();
        String token = ((Map<?, ?>) login.get("data")).get("token").toString();
        Map<?, ?> changed =
                body(
                        HttpMethod.POST,
                        "/api/hospital/auth/password",
                        new HttpEntity<>(
                                Map.of("oldPassword", ADMIN_PASSWORD, "newPassword", NEW_PASSWORD),
                                bearer(token)));
        assertEquals(0, code(changed), () -> "首登改密失败：" + changed);
        return token;
    }

    private long tenantIdOf(String tenantCode) {
        return platform.queryForObject(
                "SELECT id FROM plt_tenant WHERE code = ?", Long.class, tenantCode);
    }

    /** 在新租户库里补一个科室（用户管理必填主科室）；jdbc 是应用的路由连接，按租户上下文进租户库。 */
    private void seedDept(String tenantCode, long tenantId) {
        com.crdp.framework.tenant.TenantContext.runAsTenant(
                tenantId,
                tenantCode,
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_dept (org_id, code, name, status)"
                                    + " VALUES (1, 'IT-D-QA', '虚构配额测试科（示例）', 'ENABLED')");
                });
    }

    private long deptIdOf(String tenantCode, long tenantId) {
        return com.crdp.framework.tenant.TenantContext.callAsTenant(
                tenantId,
                tenantCode,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = 'IT-D-QA'", Long.class));
    }

    private long roleIdOf(String tenantCode, long tenantId) {
        return com.crdp.framework.tenant.TenantContext.callAsTenant(
                tenantId,
                tenantCode,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_role WHERE code = 'TENANT_ADMIN'", Long.class));
    }

    /** 租户库当前启用中用户数（新租户含种子演示账号，不能假设是 1）。 */
    private long enabledUserCount(String tenantCode, long tenantId) {
        return com.crdp.framework.tenant.TenantContext.callAsTenant(
                tenantId,
                tenantCode,
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_user WHERE status = 'ENABLED'",
                                Long.class));
    }

    private Map<?, ?> hospitalCreateUser(
            String token, long deptId, long roleId, String username, String phone) {
        return body(
                HttpMethod.POST,
                "/api/hospital/system/users",
                new HttpEntity<>(
                        Map.of(
                                "username",
                                username,
                                "displayName",
                                "示例用户",
                                "phone",
                                phone,
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId)),
                        bearer(token)));
    }

    private long userIdOf(String tenantCode, long tenantId, String username) {
        return com.crdp.framework.tenant.TenantContext.callAsTenant(
                tenantId,
                tenantCode,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    private Map<?, ?> hospitalChangeStatus(String token, long userId, String action) {
        return body(
                HttpMethod.POST,
                "/api/hospital/system/users/" + userId + "/status",
                new HttpEntity<>(Map.of("action", action), bearer(token)));
    }

    /** 上传一个合成 MP3（multipart，与 FileServiceAcceptanceIT 同款样例，不可执行、虚构）。 */
    private Map<?, ?> uploadRaw(String token, String filename, byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add(
                "file",
                new org.springframework.core.io.ByteArrayResource(body) {
                    @Override
                    public String getFilename() {
                        return filename;
                    }
                });
        @SuppressWarnings("unchecked")
        Map<?, ?> response =
                (Map<?, ?>)
                        rest.postForEntity(
                                        "/api/hospital/files",
                                        new HttpEntity<>(parts, headers),
                                        Map.class)
                                .getBody();
        assertNotNull(response, "上传响应体不应为空");
        return response;
    }

    private static byte[] mp3(int padding) {
        byte[] header = new byte[] {'I', 'D', '3', 0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        byte[] payload = new byte[header.length + padding];
        System.arraycopy(header, 0, payload, 0, header.length);
        for (int i = header.length; i < payload.length; i++) {
            payload[i] = 0x55;
        }
        return payload;
    }

    // ---------------------------------------------------------------- 用例

    @Test
    void wizardQuotaPersistsAndPlatformMaintainsWithAuditAndPermissions() {
        connect();
        long tenantId =
                createTenant("虚构示例医院壬", Map.of("userLimit", 2, "storageLimitBytes", 1_000_000L));
        String code = codeOf(tenantId);

        // 向导配额落列（V67）
        Map<String, Object> stored =
                platform.queryForMap(
                        "SELECT user_limit, storage_limit_bytes FROM plt_tenant WHERE id = ?",
                        tenantId);
        assertEquals(2, ((Number) stored.get("user_limit")).intValue());
        assertEquals(1_000_000L, ((Number) stored.get("storage_limit_bytes")).longValue());

        // 视图：上限 + 实时用量（新租户含种子演示账号，启用数不假设具体值）；上限 2 而启用数远超
        // → 已满额且预警（调低上限不回收存量，只挡新增）
        Map<?, ?> view =
                data(
                        body(
                                HttpMethod.GET,
                                "/api/platform/tenants/" + tenantId + "/quota",
                                new HttpEntity<>(bearer(loginPlatform()))));
        assertEquals(2, ((Number) view.get("userLimit")).intValue());
        long enabled = ((Number) view.get("enabledUserCount")).longValue();
        assertTrue(enabled >= 2, "种子演示账号 + 开通管理员：" + enabled);
        assertEquals(0L, ((Number) view.get("storageUsedBytes")).longValue(), "新租户无文件");
        assertEquals(Boolean.TRUE, view.get("userWarning"));
        assertEquals(Boolean.TRUE, view.get("userLimitReached"));

        // 维护：非法上限 40001（不落库）
        assertEquals(
                40001,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + tenantId + "/quota",
                                new HttpEntity<>(
                                        Map.of("userLimit", 0), bearer(loginPlatform())))));

        // 无编辑权限（只授 platform:tenant:view）40300；查看放行
        createQuotaViewer();
        String viewerToken = loginPlatformAs("platform.viewer.quota.it");
        assertEquals(
                40300,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + tenantId + "/quota",
                                new HttpEntity<>(Map.of("userLimit", 5), bearer(viewerToken)))),
                "无 platform:tenant:edit 不能改配额");
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.GET,
                                "/api/platform/tenants/" + tenantId + "/quota",
                                new HttpEntity<>(bearer(viewerToken)))),
                "platform:tenant:view 可查看配额");

        // 合法维护：写列 + TENANT_QUOTA 审计（前后值）；恢复不限后预警与满额都退去
        Map<?, ?> updated =
                data(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + tenantId + "/quota",
                                new HttpEntity<>(Map.of("userLimit", 3), bearer(loginPlatform()))));
        assertEquals(3, ((Number) updated.get("userLimit")).intValue());
        assertNull(updated.get("storageLimitBytes"), "存储恢复不限");
        assertEquals(Boolean.FALSE, updated.get("storageWarning"));
        String auditDetail =
                platform.queryForObject(
                        "SELECT detail FROM plt_audit_log WHERE event_type = 'TENANT_QUOTA'"
                                + " AND target = ? ORDER BY id DESC LIMIT 1",
                        String.class,
                        "plt_tenant:" + code);
        assertTrue(
                auditDetail.contains("用户数上限 2 → 3") && auditDetail.contains("存储上限"),
                "审计记前后值：" + auditDetail);
    }

    @Test
    void userQuotaBlocksHospitalCreateAndEnableUntilRaised() {
        connect();
        long tenantId = createTenant("虚构示例医院癸", Map.of());
        String code = codeOf(tenantId);
        seedDept(code, tenantId);
        long deptId = deptIdOf(code, tenantId);
        long roleId = roleIdOf(code, tenantId);
        String token = hospitalReadyToken(code);

        // 以当前启用数为基准收紧到「只剩 1 个名额」：新建 1 个成功、下一个 40901
        long base = enabledUserCount(code, tenantId);
        String tokenForQuota = loginPlatform();
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + tenantId + "/quota",
                                new HttpEntity<>(
                                        Map.of("userLimit", (int) (base + 1)),
                                        bearer(tokenForQuota)))));

        assertEquals(
                0, code(hospitalCreateUser(token, deptId, roleId, "it.quota.a", "13900000101")));
        Map<?, ?> blocked = hospitalCreateUser(token, deptId, roleId, "it.quota.b", "13900000102");
        assertEquals(40901, code(blocked));
        assertTrue(
                String.valueOf(blocked.get("message")).contains("已达用户数上限（" + (base + 1) + "）"),
                "提示含上限：" + blocked.get("message"));

        // 停用腾出名额后再建成功；启用又把启用数顶回上限（PLT-ORG-010：新建与启用都校验）
        long quotaA = userIdOf(code, tenantId, "it.quota.a");
        assertEquals(0, code(hospitalChangeStatus(token, quotaA, "DISABLE")));
        assertEquals(
                0, code(hospitalCreateUser(token, deptId, roleId, "it.quota.b", "13900000102")));
        assertEquals(
                40901,
                code(hospitalChangeStatus(token, quotaA, "ENABLE")),
                "启用同样受配额拦截（PLT-ORG-010）");

        // 平台上调上限后启用放行；恢复不限（null）后新建也不再拦截
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + tenantId + "/quota",
                                new HttpEntity<>(
                                        Map.of("userLimit", (int) (base + 2)),
                                        bearer(tokenForQuota)))));
        assertEquals(0, code(hospitalChangeStatus(token, quotaA, "ENABLE")));
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + tenantId + "/quota",
                                new HttpEntity<>(Map.of(), bearer(tokenForQuota)))),
                "null = 恢复不限");
        assertEquals(
                0, code(hospitalCreateUser(token, deptId, roleId, "it.quota.c", "13900000103")));
    }

    @Test
    void storageQuotaBlocksUploadBeyondLimit() {
        connect();
        // 开通 followup 模块（上传口要求模块开通）；初始不限：先传一个文件占住用量
        long tenantId = createTenant("虚构示例医院子", Map.of("modules", List.of("followup")));
        String code = codeOf(tenantId);
        String token = hospitalReadyToken(code);
        byte[] first = mp3(90);
        Map<?, ?> uploaded = uploadRaw(token, "通话录音（示例）.mp3", first);
        assertEquals(0, code(uploaded), () -> "不限时应可上传：" + uploaded);

        // 把上限压到当前用量：第二个上传 40001（存量保留，只挡新增）
        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + tenantId + "/quota",
                                new HttpEntity<>(
                                        Map.of("storageLimitBytes", (long) first.length),
                                        bearer(loginPlatform())))));
        Map<?, ?> blocked = uploadRaw(token, "通话录音二（示例）.mp3", mp3(64));
        assertEquals(40001, code(blocked));
        assertTrue(
                String.valueOf(blocked.get("message")).contains("存储空间已达上限"),
                "提示含原因：" + blocked.get("message"));

        // 视图：存储满额预警（用量=上限）；上调后上传放行
        Map<?, ?> view =
                data(
                        body(
                                HttpMethod.GET,
                                "/api/platform/tenants/" + tenantId + "/quota",
                                new HttpEntity<>(bearer(loginPlatform()))));
        assertEquals((long) first.length, ((Number) view.get("storageUsedBytes")).longValue());
        assertEquals(Boolean.TRUE, view.get("storageWarning"));
        assertEquals(Boolean.TRUE, view.get("storageLimitReached"));

        assertEquals(
                0,
                code(
                        body(
                                HttpMethod.PUT,
                                "/api/platform/tenants/" + tenantId + "/quota",
                                new HttpEntity<>(
                                        Map.of("storageLimitBytes", 1_000_000L),
                                        bearer(loginPlatform())))));
        assertEquals(0, code(uploadRaw(token, "通话录音三（示例）.mp3", mp3(64))));
    }

    // ---------------------------------------------------------------- 内部

    /** 只读平台账号（platform:tenant:view，菜单 111；无 113 编辑按钮），口径同 lifecycle IT 的 viewer。 */
    private void createQuotaViewer() {
        connect();
        platform.update(
                "INSERT INTO plt_role (code, name, built_in, status)"
                        + " VALUES ('PLATFORM_QUOTA_VIEWER_IT', '平台配额只读（IT 示例）', 0, 'ENABLED')");
        platform.update(
                "INSERT INTO plt_account (username, display_name, password_hash, status)"
                        + " VALUES ('platform.viewer.quota.it', '平台配额只读（示例）', ?, 'ENABLED')",
                passwordEncoder.encode("Demo@2026"));
        platform.update(
                "INSERT INTO plt_account_role (account_id, role_id)"
                        + " SELECT a.id, r.id FROM plt_account a, plt_role r"
                        + " WHERE a.username = 'platform.viewer.quota.it'"
                        + " AND r.code = 'PLATFORM_QUOTA_VIEWER_IT'");
        platform.update(
                "INSERT INTO plt_role_menu (role_id, menu_id)"
                        + " SELECT r.id, 111 FROM plt_role r WHERE r.code = 'PLATFORM_QUOTA_VIEWER_IT'");
        viewerCreated = true;
    }
}
