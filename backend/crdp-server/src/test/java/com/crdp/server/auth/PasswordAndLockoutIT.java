package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * E04-T05 / PLT-AUTH-006、007、010 端到端（完整应用 + 真实 MySQL/Redis，HTTP 走起）： 连续失败 3 次触发图形验证码、5 次锁定 30
 * 分钟、管理员解锁；初始密码强制改密、40305 拦截、改密策略 / 历史密码、改密后其他会话失效、密码到期强制改密。 CRDP_IT_MYSQL_PORT 存在时运行（CI 起 docker
 * compose 注入端口），与 SmsAndTwoFactorIT 同一套环境。 全部为虚构账号（pwchange.* 为本用例专用，口令不进入仓库文档）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class PasswordAndLockoutIT {

    private static final String TENANT = "demoa";
    private static final long TENANT_ID = 1L;
    private static final String DEMO_PASSWORD = "Demo@2026";
    private static final String NEW_PASSWORD = "Fresh@2027pw";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PasswordEncoder passwordEncoder;

    @BeforeEach
    void cleanState() {
        // 真实 Redis：清登录失败计数与图形验证码，避免用例之间互相锁定
        Set<String> keys = redis.keys("crdp:login:fail:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        Set<String> captchaKeys = redis.keys("crdp:captcha:*");
        if (captchaKeys != null && !captchaKeys.isEmpty()) {
            redis.delete(captchaKeys);
        }
        // 专用虚构账号：重建为「初始密码待改」状态
        runInTenant(
                () -> {
                    jdbc.update(
                            "DELETE FROM sys_password_history WHERE user_id IN"
                                    + " (SELECT id FROM sys_user WHERE username = 'pwchange.demo')");
                    jdbc.update("DELETE FROM sys_user WHERE username = 'pwchange.demo'");
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                    + " must_change_password) VALUES ('pwchange.demo', '改密演示（示例）', ?, 'ENABLED', 1)",
                            passwordEncoder.encode(DEMO_PASSWORD));
                });
        runInPlatform(
                () -> {
                    jdbc.update(
                            "DELETE FROM plt_password_history WHERE account_id IN"
                                    + " (SELECT id FROM plt_account WHERE username = 'pwchange.platform')");
                    jdbc.update("DELETE FROM plt_account WHERE username = 'pwchange.platform'");
                    jdbc.update(
                            "INSERT INTO plt_account (username, display_name, password_hash, status,"
                                    + " must_change_password) VALUES ('pwchange.platform', '平台改密演示（示例）', ?, 'ENABLED', 1)",
                            passwordEncoder.encode(DEMO_PASSWORD));
                });
    }

    @Test
    void captchaThenLockoutThenAdministratorUnlock() {
        // 1. 连续失败 3 次：前两次提示剩余次数，第 3 次起要求图形验证码（data.captchaRequired）
        LoginOutcome first = loginHospital("doctor.demo", "Wrong@2026", null);
        assertEquals(40101, first.code());
        assertEquals(false, first.data().get("captchaRequired"));
        assertEquals(4, first.data().get("remainingAttempts"));

        loginHospital("doctor.demo", "Wrong@2026", null);
        LoginOutcome third = loginHospital("doctor.demo", "Wrong@2026", null);
        assertEquals(40101, third.code());
        assertEquals(true, third.data().get("captchaRequired"), "连续失败 3 次触发图形验证码");

        // 2. 到阈值后即使口令正确，不带验证码也 40102（不区分口令对错）
        LoginOutcome missing = loginHospital("doctor.demo", DEMO_PASSWORD, null);
        assertEquals(40102, missing.code());

        // 3. 取图：公开接口返回 data URL
        Map<?, ?> captcha = get("/api/hospital/auth/captcha", null);
        assertEquals(0, captcha.get("code"), "message=" + captcha.get("message"));
        assertNotNull(data(captcha).get("captchaId"));
        assertTrue(
                String.valueOf(data(captcha).get("image")).startsWith("data:image/png;base64,"),
                "验证码是 PNG data URL");

        // 4. 验证码错误计入失败：达到 5 次后锁定（42900 带剩余秒数），口令正确也不放行
        LoginOutcome wrongCaptcha =
                loginHospital(
                        "doctor.demo",
                        DEMO_PASSWORD,
                        Map.of("captchaId", "no-such", "captchaCode", "ZZ9Z"));
        assertEquals(40102, wrongCaptcha.code());
        LoginOutcome locked =
                loginHospital(
                        "doctor.demo",
                        DEMO_PASSWORD,
                        Map.of("captchaId", "no-such", "captchaCode", "ZZ9Z"));
        assertEquals(42900, locked.code(), "连续失败 5 次锁定 30 分钟");
        assertTrue(((Number) locked.data().get("lockedSeconds")).longValue() > 0, "锁定剩余秒数放进 data");
        assertEquals(42900, loginHospital("doctor.demo", DEMO_PASSWORD, null).code(), "锁定期间一律拒绝");

        // 5. 管理员解锁：nurse.demo 是 TENANT_ADMIN（V7 已授予 tenant:user:reset 按钮）
        String adminToken = loginHospital("nurse.demo", DEMO_PASSWORD, null).token();
        Long doctorId =
                com.crdp.framework.tenant.TenantContext.callAsTenant(
                        TENANT_ID,
                        TENANT,
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_user WHERE username = 'doctor.demo'",
                                        Long.class));
        Map<?, ?> unlock =
                post(null, "/api/hospital/system/users/" + doctorId + "/unlock", adminToken);
        assertEquals(0, unlock.get("code"), "解锁成功：message=" + unlock.get("message"));
        assertEquals("doctor.demo", data(unlock).get("username"));

        // 6. 解锁后正确口令可登录
        LoginOutcome ok = loginHospital("doctor.demo", DEMO_PASSWORD, null);
        assertEquals(0, ok.code());
        assertNotNull(ok.token());
    }

    @Test
    void firstLoginForcesChangeAndOtherSessionsAreRevoked() {
        // 1. 初始密码登录成功，但 me 标记强制改密
        LoginOutcome login = loginHospital("pwchange.demo", DEMO_PASSWORD, null);
        assertEquals(0, login.code());
        String token = login.token();
        Map<?, ?> me = get("/api/hospital/auth/me", token);
        assertEquals(Boolean.TRUE, data(me).get("mustChangePassword"), "初始密码必须先改");

        // 2. 第二个会话（稍后验证改密后失效）
        String otherToken = loginHospital("pwchange.demo", DEMO_PASSWORD, null).token();

        // 3. 除改密 / 登出 / me 外一律 40305
        assertEquals(40305, get("/api/hospital/menus", token).get("code"));
        assertEquals(40305, get("/api/hospital/auth/codes", token).get("code"));

        // 4. 策略校验：弱口令 / 与最近密码相同 均 42200
        Map<?, ?> weak =
                post(
                        Map.of("oldPassword", DEMO_PASSWORD, "newPassword", "weakpassword"),
                        "/api/hospital/auth/password",
                        token);
        assertEquals(42200, weak.get("code"), "message=" + weak.get("message"));
        Map<?, ?> reuse =
                post(
                        Map.of("oldPassword", DEMO_PASSWORD, "newPassword", DEMO_PASSWORD),
                        "/api/hospital/auth/password",
                        token);
        assertEquals(42200, reuse.get("code"), "新密码不能与最近使用过的密码相同");
        Map<?, ?> wrongOld =
                post(
                        Map.of("oldPassword", "NotThe@2026", "newPassword", NEW_PASSWORD),
                        "/api/hospital/auth/password",
                        token);
        assertEquals(42200, wrongOld.get("code"), "原密码不正确");

        // 5. 合规改密成功：当前会话继续可用，其他会话失效
        Map<?, ?> changed =
                post(
                        Map.of("oldPassword", DEMO_PASSWORD, "newPassword", NEW_PASSWORD),
                        "/api/hospital/auth/password",
                        token);
        assertEquals(0, changed.get("code"), "message=" + changed.get("message"));
        Map<?, ?> meAfter = get("/api/hospital/auth/me", token);
        assertEquals(Boolean.FALSE, data(meAfter).get("mustChangePassword"), "改密后解除强制改密标记");
        assertEquals(0, get("/api/hospital/menus", token).get("code"), "改密后其余接口恢复可用");
        assertEquals(40100, get("/api/hospital/auth/me", otherToken).get("code"), "其他会话已失效");

        // 6. 旧口令不能登录，新口令可以
        assertEquals(40101, loginHospital("pwchange.demo", DEMO_PASSWORD, null).code());
        LoginOutcome relogin = loginHospital("pwchange.demo", NEW_PASSWORD, null);
        assertEquals(0, relogin.code());
    }

    @Test
    void expiredPasswordForcesAChange() {
        runInTenant(
                () ->
                        jdbc.update(
                                "UPDATE sys_user SET must_change_password = 0,"
                                        + " password_updated_at = DATE_SUB(NOW(3), INTERVAL 91 DAY)"
                                        + " WHERE username = 'pwchange.demo'"));

        LoginOutcome login = loginHospital("pwchange.demo", DEMO_PASSWORD, null);
        assertEquals(0, login.code());
        Map<?, ?> me = get("/api/hospital/auth/me", login.token());
        assertEquals(
                Boolean.TRUE,
                data(me).get("mustChangePassword"),
                "密码超过 90 天有效期，登录即强制改密（PLT-AUTH-006）");
    }

    @Test
    void platformFirstLoginChangeMirror() {
        // 平台端（local 关闭强制双因素，密码一步到位）
        LoginOutcome login = loginPlatform("pwchange.platform", DEMO_PASSWORD);
        assertEquals(0, login.code());
        String token = login.token();
        assertEquals(
                Boolean.TRUE, data(get("/api/platform/auth/me", token)).get("mustChangePassword"));
        assertEquals(40305, get("/api/platform/menus", token).get("code"));

        Map<?, ?> changed =
                post(
                        Map.of("oldPassword", DEMO_PASSWORD, "newPassword", NEW_PASSWORD),
                        "/api/platform/auth/password",
                        token);
        assertEquals(0, changed.get("code"), "message=" + changed.get("message"));
        assertEquals(
                Boolean.FALSE,
                data(get("/api/platform/auth/me", token)).get("mustChangePassword"),
                "平台端改密后同样解除标记");
        assertEquals(0, get("/api/platform/menus", token).get("code"));
        assertEquals(40101, loginPlatform("pwchange.platform", DEMO_PASSWORD).code(), "旧口令失效");
    }

    // ---- helpers ----

    private record LoginOutcome(Object code, String token, Map<?, ?> raw) {

        Map<?, ?> data() {
            return raw.get("data") == null ? Map.of() : (Map<?, ?>) raw.get("data");
        }
    }

    private LoginOutcome loginHospital(
            String username, String password, Map<String, String> captcha) {
        var body =
                new java.util.HashMap<String, Object>(
                        Map.of("tenantCode", TENANT, "username", username, "password", password));
        if (captcha != null) {
            body.putAll(captcha);
        }
        Map<?, ?> response = post(body, "/api/hospital/auth/login", null);
        String token = response.get("data") == null ? null : tokenOf(response);
        return new LoginOutcome(response.get("code"), token, response);
    }

    private LoginOutcome loginPlatform(String username, String password) {
        Map<?, ?> response =
                post(
                        Map.of("username", username, "password", password),
                        "/api/platform/auth/login",
                        null);
        String token = response.get("data") == null ? null : tokenOf(response);
        return new LoginOutcome(response.get("code"), token, response);
    }

    private static String tokenOf(Map<?, ?> response) {
        return (String) ((Map<?, ?>) response.get("data")).get("token");
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return response.get("data") == null ? Map.of() : (Map<?, ?>) response.get("data");
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

    private void runInPlatform(Runnable action) {
        com.crdp.framework.tenant.TenantContext.runAsPlatform(action);
    }
}
