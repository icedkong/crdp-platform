package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.notify.SmsCodeSender;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * E04-T07 / PLT-AUTH-011 端到端（完整应用 + 真实 MySQL/Redis，HTTP 走起）： 忘记密码两步（发码到绑定手机号 → 验证码 + 新密码重置），
 * 重置后旧密码作废、全部会话失效、写审计； 未知账号与验证码错误同口径 40102、冷却行为与真实账号一致（防探测）； 登录验证码不能重置密码。 CRDP_IT_MYSQL_PORT
 * 存在时运行（CI 起 docker compose 注入端口），与 SmsAndTwoFactorIT 同一套环境。 专用虚构账号 forgot.demo / 13900000012。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class ForgotPasswordIT {

    private static final String TENANT = "demoa";
    private static final long TENANT_ID = 1L;
    private static final String USERNAME = "forgot.demo";
    private static final String PHONE = "13900000012";
    private static final String OLD_PASSWORD = "Demo@2026";
    private static final String NEW_PASSWORD = "Fresh@2027pw";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CryptoService cryptoService;

    /** 截获发送通道里的验证码（按手机号）。 */
    @MockitoBean private SmsCodeSender smsCodeSender;

    private final Map<String, String> sentCodes = new ConcurrentHashMap<>();

    @BeforeEach
    void cleanState() {
        // 真实 Redis：清冷却/每日/验证码键，避免用例之间互相限流
        Set<String> keys = redis.keys("crdp:sms:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        Set<String> loginFails = redis.keys("crdp:login:fail:*");
        if (loginFails != null && !loginFails.isEmpty()) {
            redis.delete(loginFails);
        }
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            sentCodes.put(
                                    inv.getArgument(0, String.class),
                                    inv.getArgument(1, String.class));
                            return null;
                        })
                .when(smsCodeSender)
                .sendPasswordResetCode(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(
                        inv -> {
                            sentCodes.put(
                                    inv.getArgument(0, String.class),
                                    inv.getArgument(1, String.class));
                            return null;
                        })
                .when(smsCodeSender)
                .sendLoginCode(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        runInTenant(
                () -> {
                    jdbc.update(
                            "DELETE FROM sys_password_history WHERE user_id IN"
                                    + " (SELECT id FROM sys_user WHERE username = ?)",
                            USERNAME);
                    jdbc.update("DELETE FROM sys_user WHERE username = ?", USERNAME);
                    jdbc.update("DELETE FROM aud_log WHERE target = ?", "sys_user:" + USERNAME);
                    jdbc.update("DELETE FROM aud_login_log WHERE username = ?", USERNAME);
                    // 手机号信封加密 + 盲索引（SQL 里算不了 HMAC，走 CryptoService，purpose 与 sys_user 一致）
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                    + " must_change_password, phone, phone_idx) VALUES"
                                    + " (?, '忘记密码演示（示例）', ?, 'ENABLED', 0, ?, ?)",
                            USERNAME,
                            passwordEncoder.encode(OLD_PASSWORD),
                            cryptoService.encrypt("sys-user-phone", PHONE),
                            cryptoService.blindIndex("sys-user-phone", PHONE));
                });
    }

    @Test
    void forgotThenResetRevokesSessionsAndWritesAudit() {
        // 1. 旧密码先登录拿一个会话（重置后必须失效）
        LoginOutcome before = loginHospital(USERNAME, OLD_PASSWORD);
        assertEquals(0, before.code(), "message=" + before.raw().get("message"));
        assertNotNull(before.token());

        // 2. 第一步：发送重置验证码（到绑定手机号）
        assertEquals(
                0,
                post(forgotBody(USERNAME), "/api/hospital/auth/password/forgot", null).get("code"));
        String code = sentCodes.get(PHONE);
        assertNotNull(code, "重置验证码已发到绑定手机号");

        // 3. 第二步：验证码 + 新密码
        Map<?, ?> reset =
                post(
                        Map.of(
                                "tenantCode",
                                TENANT,
                                "username",
                                USERNAME,
                                "code",
                                code,
                                "newPassword",
                                NEW_PASSWORD),
                        "/api/hospital/auth/password/reset",
                        null);
        assertEquals(0, reset.get("code"), "message=" + reset.get("message"));

        // 4. 旧令牌立即失效（全部会话吊销，没有「当前会话」）
        assertEquals(40100, get("/api/hospital/auth/me", before.token()).get("code"));

        // 5. 旧密码作废、新密码可登录
        assertEquals(40101, loginHospital(USERNAME, OLD_PASSWORD).code());
        LoginOutcome after = loginHospital(USERNAME, NEW_PASSWORD);
        assertEquals(0, after.code(), "新密码可登录");
        assertEquals(0, get("/api/hospital/auth/me", after.token()).get("code"));

        // 6. 审计：USER_PASSWORD_RESET 落租户库，detail 不含新旧口令
        Map<String, Object> audit =
                runInTenantWithResult(
                        () ->
                                jdbc.queryForMap(
                                        "SELECT event_type, action, target, detail FROM aud_log"
                                                + " WHERE event_type = 'USER_PASSWORD_RESET' AND target = 'sys_user:"
                                                + USERNAME
                                                + "'"));
        assertEquals("UPDATE", audit.get("action"));
        String detail = String.valueOf(audit.get("detail"));
        assertTrue(detail.contains("会话"), "审计说明会话已失效，实际：" + detail);
        assertTrue(!detail.contains(OLD_PASSWORD) && !detail.contains(NEW_PASSWORD), "审计不落新旧口令");
    }

    @Test
    void unknownUserIsSilentOnRequestAndUniformlyThrottled() {
        // 未知账号：第一步同样返回成功
        assertEquals(
                0,
                post(forgotBody("ghost.demo"), "/api/hospital/auth/password/forgot", null)
                        .get("code"),
                "未知账号静默成功（防探测）");
        assertNull(sentCodes.get(PHONE), "不发短信");
        // 60 秒冷却对未知账号同样生效（探针位），否则「第二次是否 42900」就是账号存在性探针
        assertEquals(
                42900,
                post(forgotBody("ghost.demo"), "/api/hospital/auth/password/forgot", null)
                        .get("code"),
                "未知账号也吃 60 秒冷却");

        // 真实账号的第二次快速请求同样 42900（与未知账号不可区分）
        assertEquals(
                0,
                post(forgotBody(USERNAME), "/api/hospital/auth/password/forgot", null).get("code"));
        assertEquals(
                42900,
                post(forgotBody(USERNAME), "/api/hospital/auth/password/forgot", null).get("code"),
                "真实账号也吃 60 秒冷却");
    }

    @Test
    void wrongCodeAndUnknownUserAreBoth40102() {
        assertEquals(
                0,
                post(forgotBody(USERNAME), "/api/hospital/auth/password/forgot", null).get("code"));

        Map<?, ?> wrongCode =
                post(
                        Map.of(
                                "tenantCode",
                                TENANT,
                                "username",
                                USERNAME,
                                "code",
                                "000000",
                                "newPassword",
                                NEW_PASSWORD),
                        "/api/hospital/auth/password/reset",
                        null);
        assertEquals(40102, wrongCode.get("code"), "验证码错误统一 40102");

        Map<?, ?> unknownUser =
                post(
                        Map.of(
                                "tenantCode",
                                TENANT,
                                "username",
                                "ghost.demo",
                                "code",
                                "123456",
                                "newPassword",
                                NEW_PASSWORD),
                        "/api/hospital/auth/password/reset",
                        null);
        assertEquals(40102, unknownUser.get("code"), "未知账号与验证码错误同口径");
        assertEquals("验证码错误或已过期", unknownUser.get("message"));

        // 旧密码未被改动
        assertEquals(0, loginHospital(USERNAME, OLD_PASSWORD).code());
    }

    @Test
    void loginPurposeCodeCannotResetPassword() {
        // 同一手机号先取一个「登录」验证码（login: 命名空间）
        assertEquals(
                0,
                post(Map.of("tenantCode", TENANT, "phone", PHONE), "/api/hospital/auth/sms", null)
                        .get("code"));
        String loginCode = sentCodes.get(PHONE);

        Map<?, ?> reset =
                post(
                        Map.of(
                                "tenantCode",
                                TENANT,
                                "username",
                                USERNAME,
                                "code",
                                loginCode,
                                "newPassword",
                                NEW_PASSWORD),
                        "/api/hospital/auth/password/reset",
                        null);

        assertEquals(40102, reset.get("code"), "登录验证码不能重置密码（命名空间隔离）");
        assertEquals(0, loginHospital(USERNAME, OLD_PASSWORD).code(), "密码未被改动");
    }

    @Test
    void weakNewPasswordIs42200AndBurnsTheCode() {
        assertEquals(
                0,
                post(forgotBody(USERNAME), "/api/hospital/auth/password/forgot", null).get("code"));
        String code = sentCodes.get(PHONE);

        Map<?, ?> weak =
                post(
                        Map.of(
                                "tenantCode",
                                TENANT,
                                "username",
                                USERNAME,
                                "code",
                                code,
                                "newPassword",
                                "abcd1234"),
                        "/api/hospital/auth/password/reset",
                        null);
        assertEquals(42200, weak.get("code"));
        assertTrue(
                String.valueOf(weak.get("message")).contains("至少 3 类"),
                "列明不满足项（长度过 DTO 下限、只两类字符），实际：" + weak.get("message"));

        // 验证码在策略校验前已被消费：同一验证码不能再来一次（需重新获取）
        Map<?, ?> replay =
                post(
                        Map.of(
                                "tenantCode",
                                TENANT,
                                "username",
                                USERNAME,
                                "code",
                                code,
                                "newPassword",
                                NEW_PASSWORD),
                        "/api/hospital/auth/password/reset",
                        null);
        assertEquals(40102, replay.get("code"), "验证码一次一用");
        assertEquals(0, loginHospital(USERNAME, OLD_PASSWORD).code(), "密码未被改动");
    }

    // ---- helpers ----

    private static Map<String, Object> forgotBody(String username) {
        return Map.of("tenantCode", TENANT, "username", username);
    }

    private record LoginOutcome(Object code, String token, Map<?, ?> raw) {}

    private LoginOutcome loginHospital(String username, String password) {
        Map<?, ?> response =
                post(
                        Map.of(
                                "tenantCode", TENANT,
                                "username", username,
                                "password", password),
                        "/api/hospital/auth/login",
                        null);
        String token =
                response.get("data") == null
                        ? null
                        : (String) ((Map<?, ?>) response.get("data")).get("token");
        return new LoginOutcome(response.get("code"), token, response);
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> post(Map<String, Object> body, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private void runInTenant(Runnable action) {
        com.crdp.framework.tenant.TenantContext.runAsTenant(TENANT_ID, TENANT, action);
    }

    private <T> T runInTenantWithResult(java.util.function.Supplier<T> action) {
        return com.crdp.framework.tenant.TenantContext.callAsTenant(TENANT_ID, TENANT, action);
    }
}
