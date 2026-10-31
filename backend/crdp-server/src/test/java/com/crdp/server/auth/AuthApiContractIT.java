package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/**
 * E04-T10 / PLT-AUTH-001、007 认证接口用例矩阵（完整应用 + 真实 MySQL/Redis，HTTP 走起）。
 *
 * <p>按 docs/api/auth.md §1 的契约逐条走接口：登录成功响应（tokenType / expiresInSeconds）、me / codes / menus
 * 字段与口径、参数校验失败 40001（AGENTS.md 第 6 节「校验失败」一列）、 三种凭据失败统一 40101（不外露差异）、成功登录清零失败计数、 无令牌 / 伪造令牌
 * 40100、登出即失效且登出幂等拒绝、 解锁接口的无权限（40300）与越租户 / 不存在（40400，双租户负例）。
 *
 * <p>失败阶梯（3 次验证码墙 → 5 次锁定）与自动解锁在 {@code LoginLockoutAutoUnlockIT}（短锁定参数）； 会话空闲 / 绝对超时在 {@code
 * SessionIdleTimeoutIT} / {@code SessionAbsoluteTimeoutIT}； 通道隔离与子域名一致性在 {@code
 * TenantIsolationAcceptanceIT}。 CRDP_IT_MYSQL_PORT 存在时运行（CI 起 docker compose 注入端口）。
 * 全部为虚构账号（contract.* 为本用例专用）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class AuthApiContractIT {

    private static final String TENANT = "demoa";
    private static final long TENANT_ID = 1L;
    private static final long OTHER_TENANT_ID = 2L;
    private static final String OTHER_TENANT = "demob";
    private static final String PASSWORD = "Demo@2026";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanState() {
        // 真实 Redis：清失败计数与图形验证码，避免用例之间互相锁定；清会话保证 expiresInSeconds 断言稳定
        deleteKeys(redis.keys("crdp:login:fail:*"));
        deleteKeys(redis.keys("crdp:captcha:*"));
        deleteKeys(redis.keys("crdp:sess:*"));
        // 专用虚构账号：停用账号（统一 40101 矩阵用）
        runInTenant(
                TENANT_ID,
                TENANT,
                () -> {
                    jdbc.update("DELETE FROM sys_user WHERE username = 'contract.disabled'");
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                    + " must_change_password) VALUES ('contract.disabled',"
                                    + " '契约演示停用（示例）', ?, 'DISABLED', 0)",
                            passwordEncoder.encode(PASSWORD));
                });
    }

    @Test
    void loginMeCodesAndMenusFollowTheDocumentedContract() {
        // 1. 登录成功：token、tokenType=Bearer、expiresInSeconds=剩余空闲窗口（默认 30 分钟）
        Map<?, ?> login =
                post(
                        Map.of(
                                "tenantCode", TENANT,
                                "username", "doctor.demo",
                                "password", PASSWORD),
                        "/api/hospital/auth/login",
                        null);
        assertEquals(0, codeOf(login), "message=" + login.get("message"));
        String token = (String) dataOf(login).get("token");
        assertNotNull(token);
        assertEquals("Bearer", dataOf(login).get("tokenType"));
        long expiresInSeconds = ((Number) dataOf(login).get("expiresInSeconds")).longValue();
        assertTrue(
                expiresInSeconds > 0 && expiresInSeconds <= 30 * 60,
                "expiresInSeconds 是剩余空闲窗口（约 30 分钟），实际 " + expiresInSeconds);

        // 2. me：会话身份字段齐全（PLT-AUTH-001 登录后前端拉取的资料）
        Map<?, ?> meData = dataOf(get("/api/hospital/auth/me", token));
        assertEquals("doctor.demo", meData.get("username"));
        assertTrue(((Number) meData.get("userId")).longValue() > 0);
        assertNotNull(meData.get("displayName"));
        assertEquals("HOSPITAL", meData.get("sessionType"));
        assertEquals(TENANT, meData.get("tenantCode"));
        assertEquals("虚构示例医院甲", meData.get("tenantName"));
        assertTrue(((List<?>) meData.get("roles")).contains("FOLLOWUP_DOCTOR"));
        assertTrue(((Number) meData.get("permissionVersion")).longValue() >= 1, "会话带权限版本号");
        assertEquals(Boolean.FALSE, meData.get("mustChangePassword"));

        // 3. codes：非空、无重复（有效菜单 perm_code 去重，PLT-ROLE-006）
        List<String> codes = itemsOf(get("/api/hospital/auth/codes", token));
        assertTrue(codes.contains("followup:patient:view"), "医生应有患者查看权限");
        assertEquals(codes.size(), new HashSet<>(codes).size(), "权限码去重");

        // 4. menus：非空树，节点带 id / name / path（component 为 LAYOUT 或 views 相对路径）
        List<Map<String, Object>> menus = itemsOf(get("/api/hospital/menus", token));
        assertFalse(menus.isEmpty());
        for (Map<String, Object> node : menus) {
            assertNotNull(node.get("id"), "菜单节点带 id");
            assertNotNull(node.get("name"), "菜单节点带 name");
            assertNotNull(node.get("path"), "菜单节点带 path");
        }
    }

    @Test
    void malformedRequestsAreRejectedAs40001FieldErrors() {
        // AGENTS.md 第 6 节「校验失败」列：字段级校验在控制器层 40001，message 为「字段 原因」
        Map<?, ?> badUsername =
                post(
                        Map.of(
                                "tenantCode", TENANT,
                                "username", "1doctor",
                                "password", PASSWORD),
                        "/api/hospital/auth/login",
                        null);
        assertEquals(40001, codeOf(badUsername));
        assertTrue(
                String.valueOf(badUsername.get("message")).contains("username"),
                "message 指明字段：" + badUsername.get("message"));

        Map<?, ?> shortPassword =
                post(
                        Map.of(
                                "tenantCode", TENANT,
                                "username", "doctor.demo",
                                "password", "Ab1@x"),
                        "/api/hospital/auth/login",
                        null);
        assertEquals(40001, codeOf(shortPassword));
        assertTrue(
                String.valueOf(shortPassword.get("message")).contains("password"),
                "message 指明字段：" + shortPassword.get("message"));

        Map<?, ?> badPlatformPassword =
                post(
                        Map.of("username", "platform.admin", "password", "Ab1@x"),
                        "/api/platform/auth/login",
                        null);
        assertEquals(40001, codeOf(badPlatformPassword), "平台端同构");

        Map<?, ?> badPhone =
                post(
                        Map.of("tenantCode", TENANT, "phone", "12345"),
                        "/api/hospital/auth/sms",
                        null);
        assertEquals(40001, codeOf(badPhone), "短信验证码手机号 ^1\\d{10}$");
    }

    @Test
    void unknownTenantUnknownAccountDisabledAccountAndWrongPasswordShareUniformRejection() {
        // PLT-AUTH-001 / 007：三种（含未知租户、停用账号）凭据失败同一句 40101，data 恒带 captchaRequired
        // 与 remainingAttempts，不外露租户 / 账号存在性
        Map<?, ?> unknownTenant =
                post(
                        Map.of(
                                "tenantCode", "nosuch1",
                                "username", "doctor.demo",
                                "password", PASSWORD),
                        "/api/hospital/auth/login",
                        null);
        Map<?, ?> unknownAccount =
                post(
                        Map.of(
                                "tenantCode", TENANT,
                                "username", "ghost.demo",
                                "password", PASSWORD),
                        "/api/hospital/auth/login",
                        null);
        Map<?, ?> disabledAccount =
                post(
                        Map.of(
                                "tenantCode", TENANT,
                                "username", "contract.disabled",
                                "password", PASSWORD),
                        "/api/hospital/auth/login",
                        null);
        Map<?, ?> wrongPassword =
                post(
                        Map.of(
                                "tenantCode", TENANT,
                                "username", "doctor.demo",
                                "password", "Wrong@2026"),
                        "/api/hospital/auth/login",
                        null);
        Map<?, ?> wrongPlatformPassword =
                post(
                        Map.of("username", "platform.admin", "password", "Wrong@2026"),
                        "/api/platform/auth/login",
                        null);

        for (Map<?, ?> response :
                List.of(
                        unknownTenant,
                        unknownAccount,
                        disabledAccount,
                        wrongPassword,
                        wrongPlatformPassword)) {
            assertEquals(40101, codeOf(response), "统一 40101：" + response);
            assertEquals("租户、账号或密码不正确", response.get("message"));
            assertEquals(Boolean.FALSE, dataOf(response).get("captchaRequired"), "首败不要求验证码");
            assertEquals(
                    4,
                    ((Number) dataOf(response).get("remainingAttempts")).intValue(),
                    "data 带剩余次数（未知账号同样计数）");
        }
    }

    @Test
    void successfulLoginClearsTheFailureCounter() {
        // 失败两次 → 正确登录成功 → 再失败一次：剩余次数回到 4（计数已被清零，PLT-AUTH-007「成功后清零」）
        post(
                Map.of("tenantCode", TENANT, "username", "nurse.demo", "password", "Wrong@2026"),
                "/api/hospital/auth/login",
                null);
        Map<?, ?> second =
                post(
                        Map.of(
                                "tenantCode", TENANT,
                                "username", "nurse.demo",
                                "password", "Wrong@2026"),
                        "/api/hospital/auth/login",
                        null);
        assertEquals(3, ((Number) dataOf(second).get("remainingAttempts")).intValue());

        Map<?, ?> success =
                post(
                        Map.of(
                                "tenantCode",
                                TENANT,
                                "username",
                                "nurse.demo",
                                "password",
                                PASSWORD),
                        "/api/hospital/auth/login",
                        null);
        assertEquals(0, codeOf(success), "正确口令登录成功");

        Map<?, ?> afterClear =
                post(
                        Map.of(
                                "tenantCode", TENANT,
                                "username", "nurse.demo",
                                "password", "Wrong@2026"),
                        "/api/hospital/auth/login",
                        null);
        assertEquals(
                4, ((Number) dataOf(afterClear).get("remainingAttempts")).intValue(), "成功登录清零失败计数");
        assertFalse(Boolean.TRUE.equals(dataOf(afterClear).get("captchaRequired")));
    }

    @Test
    void requestsWithoutOrWithAForgedTokenAreRejectedAs40100() {
        assertEquals(40100, codeOf(get("/api/hospital/auth/me", null)), "无令牌");
        assertEquals(
                40100, codeOf(get("/api/hospital/auth/me", "forged-token-material")), "伪造令牌查无会话");
        assertEquals(40100, codeOf(get("/api/platform/auth/me", null)), "平台端同构");
    }

    @Test
    void logoutInvalidatesTheTokenImmediatelyAndIsNotRepeatable() {
        String token = hospitalToken("doctor.demo");

        assertEquals(0, codeOf(post(null, "/api/hospital/auth/logout", token)));
        assertEquals(40100, codeOf(get("/api/hospital/auth/me", token)), "登出后旧令牌立即失效");
        assertEquals(40100, codeOf(post(null, "/api/hospital/auth/logout", token)), "重复登出同样按未认证拒绝");
    }

    @Test
    void unlockRequiresPermissionAndStaysInsideTheOwnTenant() {
        String doctor = hospitalToken("doctor.demo");
        String admin = hospitalToken("nurse.demo");
        long doctorId = userIdInTenant(TENANT_ID, TENANT, "doctor.demo");

        // 1. 无 tenant:user:reset 的医生连自己都解不了（40300，前端按钮隐藏不替代后端校验）
        assertEquals(
                40300,
                codeOf(post(null, "/api/hospital/system/users/" + doctorId + "/unlock", doctor)));

        // 2. 管理员只能解本院账号：在 demob 造一个本院不存在的用户 id（两院种子相同、同账号同 id，
        //    得用 demob 独有 id 才真正跨库），解它一律 40400，不暴露他院用户存在性
        long otherTenantOnlyUserId = insertUserInOtherTenant();
        try {
            assertEquals(
                    40400,
                    codeOf(
                            post(
                                    null,
                                    "/api/hospital/system/users/"
                                            + otherTenantOnlyUserId
                                            + "/unlock",
                                    admin)),
                    "他院用户对本院不可见（越租户负例）");
        } finally {
            runInTenant(
                    OTHER_TENANT_ID,
                    OTHER_TENANT,
                    () -> jdbc.update("DELETE FROM sys_user WHERE id = ?", otherTenantOnlyUserId));
        }
        assertEquals(
                40400,
                codeOf(post(null, "/api/hospital/system/users/99999999/unlock", admin)),
                "不存在的用户同样 40400");
    }

    /** 在 demob 插入一个 demoa 没有的用户 id（demoa 最大 id + 100），返回该 id。 */
    private long insertUserInOtherTenant() {
        long otherTenantOnlyId =
                com.crdp.framework.tenant.TenantContext.callAsTenant(
                                TENANT_ID,
                                TENANT,
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT COALESCE(MAX(id), 0) FROM sys_user",
                                                Long.class))
                        + 100;
        runInTenant(
                OTHER_TENANT_ID,
                OTHER_TENANT,
                () -> {
                    jdbc.update("DELETE FROM sys_user WHERE id = ?", otherTenantOnlyId);
                    jdbc.update(
                            "INSERT INTO sys_user (id, username, display_name, password_hash, status,"
                                    + " must_change_password) VALUES (?, 'contract.demob',"
                                    + " '契约演示乙院（示例）', ?, 'ENABLED', 0)",
                            otherTenantOnlyId,
                            passwordEncoder.encode(PASSWORD));
                });
        return otherTenantOnlyId;
    }

    // ---- helpers ----

    private String hospitalToken(String username) {
        Map<?, ?> response =
                post(
                        Map.of("tenantCode", TENANT, "username", username, "password", PASSWORD),
                        "/api/hospital/auth/login",
                        null);
        assertEquals(0, codeOf(response), "message=" + response.get("message"));
        return (String) dataOf(response).get("token");
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> post(Map<String, Object> body, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<?> entity =
                body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return rest.postForEntity(path, entity, Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> dataOf(Map<?, ?> response) {
        return response.get("data") == null ? Map.of() : (Map<?, ?>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> itemsOf(Map<?, ?> response) {
        return (List<T>) response.get("data");
    }

    private long userIdInTenant(long tenantId, String tenantCode, String username) {
        return com.crdp.framework.tenant.TenantContext.callAsTenant(
                tenantId,
                tenantCode,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    private void deleteKeys(Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void runInTenant(long tenantId, String tenantCode, Runnable action) {
        com.crdp.framework.tenant.TenantContext.runAsTenant(tenantId, tenantCode, action);
    }
}
