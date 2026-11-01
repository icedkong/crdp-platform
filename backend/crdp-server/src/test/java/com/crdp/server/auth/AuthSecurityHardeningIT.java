package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.notify.SmsCodeSender;
import com.crdp.server.AuditTableMaintenance;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.apache.hc.client5.http.impl.classic.HttpClients;
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
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * E04-T09 认证安全加固用例（爆破、令牌泄露、重放）端到端：以攻击者视角打完整 HTTP 链路（真实 MySQL/Redis）。
 *
 * <ul>
 *   <li><b>爆破</b>：口令爆破的「3 次验证码墙 + 5 次锁定」阶梯、未知账号与真实账号响应逐次全等（防枚举）、 短信验证码 / 双因素验证码 / 重置验证码连续输错即作废（6
 *       位码不可被穷举）、验证码图片接口按 IP 限流；
 *   <li><b>令牌泄露</b>：令牌不透明（非 JWT、两次登录不同、不携带账号 / 租户信息）、任何会话接口不回显令牌材料、 被窃会话可由本人按会话号吊销（令牌本尊不出现）；
 *   <li><b>重放</b>：短信验证码、双因素 challenge、重置验证码一次一用，成功后重放一律失败，已签发会话不受影响。
 * </ul>
 *
 * <p>CRDP_IT_MYSQL_PORT 存在时运行（CI 起 docker compose 注入端口），与 ForgotPasswordIT 同一套环境。
 * 图形验证码「一次一密」的组件级用例在 CaptchaStoreTest（图片里的码无法在端到端读出，故不在此重复）； 登出后重放令牌、跨端与跨子域使用令牌在
 * TenantIsolationAcceptanceIT。全部为虚构账号（brute.* 为本用例专用）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class AuthSecurityHardeningIT {

    private static final String TENANT = "demoa";
    private static final long TENANT_ID = 1L;
    private static final String PASSWORD = "Demo@2026";

    /** 本用例专用虚构账号（绑定手机号 13900000015，虚构号段）。 */
    private static final String BRUTE_USER = "brute.demo";

    private static final String BRUTE_PHONE = "13900000015";

    private static final String BRUTE_OLD_PASSWORD = "Demo@2026";
    private static final String BRUTE_NEW_PASSWORD = "Fresh@2027pw";
    private static final String BRUTE_OTHER_PASSWORD = "Other@2028pw";

    /** 256 位不透明令牌的 base64url 形态：43 字符、无填充、无点号（非 JWT）。 */
    private static final Pattern OPAQUE_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    /** 会话号 = 令牌 SHA-256 前 16 个十六进制字符（单向，接口不回显令牌本尊）。 */
    private static final Pattern SESSION_ID = Pattern.compile("[0-9a-f]{16}");

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CryptoService cryptoService;

    /** 截获发送通道里的验证码（按手机号）；真实通道 E09-T03 接入。 */
    @MockitoBean private SmsCodeSender smsCodeSender;

    private final Map<String, String> sentCodes = new ConcurrentHashMap<>();

    @BeforeEach
    void cleanState() {
        // HttpClient 5 收到 429 会「等 1 秒自动重发请求」（HttpRequestRetryExec）：验证码第 5
        // 次输错被作废后，重发的那个请求把 42900 掩盖成 40102。安全用例要看服务端的第一个响应，
        // 关闭测试客户端的自动重试（E02-T09 引入 HC5 是为了 Host 头可达，重试是它的副作用）。
        rest.getRestTemplate()
                .setRequestFactory(
                        new HttpComponentsClientHttpRequestFactory(
                                HttpClients.custom().disableAutomaticRetries().build()));
        // 真实 Redis：清验证码 / 失败计数 / 会话，避免用例间互相限流或串会话
        deleteKeys(redis.keys("crdp:sms:*"));
        deleteKeys(redis.keys("crdp:login:fail:*"));
        deleteKeys(redis.keys("crdp:captcha:*"));
        deleteKeys(redis.keys("crdp:sess:*"));
        stubSender();
        runInTenant(
                () -> {
                    // 双因素角色配置还原（本类的双因素用例改它，失败也要兜底还原）
                    jdbc.update(
                            "UPDATE cfg_param SET param_value = ''"
                                    + " WHERE param_key = 'security.two-factor.roles'");
                    // 专用虚构账号：重建为已知口令 + 绑定手机号（信封加密 + 盲索引，SQL 里算不了 HMAC）
                    jdbc.update(
                            "DELETE FROM sys_password_history WHERE user_id IN"
                                    + " (SELECT id FROM sys_user WHERE username = ?)",
                            BRUTE_USER);
                    jdbc.update("DELETE FROM sys_user WHERE username = ?", BRUTE_USER);
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_login_log WHERE username = ?", BRUTE_USER);
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_log WHERE target = ?", "sys_user:" + BRUTE_USER);
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                    + " must_change_password, phone, phone_idx) VALUES"
                                    + " (?, '爆破演示（示例）', ?, 'ENABLED', 0, ?, ?)",
                            BRUTE_USER,
                            passwordEncoder.encode(BRUTE_OLD_PASSWORD),
                            cryptoService.encrypt("sys-user-phone", BRUTE_PHONE),
                            cryptoService.blindIndex("sys-user-phone", BRUTE_PHONE));
                });
    }

    // ---- 爆破 ----

    @Test
    void passwordBruteForceHitsCaptchaWallThenLockoutAndStaysScopedToTheAccount() {
        // 1. 连续失败 3 次：40101 且逐步提示剩余次数，第 3 次起要求图形验证码
        Map<?, ?> first = loginHospital("doctor.demo", "Wrong@2026");
        assertEquals(40101, codeOf(first));
        assertEquals(4, dataOf(first).get("remainingAttempts"));
        assertEquals(Boolean.FALSE, dataOf(first).get("captchaRequired"));

        loginHospital("doctor.demo", "Wrong@2026");
        Map<?, ?> third = loginHospital("doctor.demo", "Wrong@2026");
        assertEquals(40101, codeOf(third));
        assertEquals(Boolean.TRUE, dataOf(third).get("captchaRequired"), "3 次失败后立验证码墙");

        // 2. 不解验证码继续打：第 4、5 次一律 40102（验证码缺失计失败，不区分口令对错），第 6 次锁定
        assertEquals(40102, codeOf(loginHospital("doctor.demo", "Wrong@2026")));
        assertEquals(40102, codeOf(loginHospital("doctor.demo", PASSWORD)), "口令正确也过不了验证码墙");
        Map<?, ?> locked = loginHospital("doctor.demo", PASSWORD);
        assertEquals(42900, codeOf(locked), "连续失败 5 次锁定");
        assertTrue(((Number) dataOf(locked).get("lockedSeconds")).longValue() > 0, "锁定剩余秒数在 data");

        // 3. 锁定期间口令正确也不放行（爆破者此刻拿到正确口令也进不来）
        assertEquals(42900, codeOf(loginHospital("doctor.demo", PASSWORD)));

        // 4. 桶按账号隔离：只锁定被爆破的账号，同院其他账号不受影响（不能借爆破一人瘫痪全院）
        assertEquals(0, codeOf(loginHospital("nurse.demo", PASSWORD)));
    }

    @Test
    void loginResponsesDoNotRevealWhetherTheAccountExists() {
        // 同样的攻击序列分别打真实账号与不存在账号：每一次的 code / message / data 必须全等，
        // 否则响应差异就是账号存在性探针（PLT-AUTH-004、007 防探测口径）
        String[] victims = {"doctor.demo", "ghost.demo"};
        Map<?, ?>[] lastPayloads = new Map<?, ?>[2];
        for (int attempt = 0; attempt < 6; attempt++) {
            for (int v = 0; v < victims.length; v++) {
                Map<?, ?> response = loginHospital(victims[v], "Wrong@2026");
                lastPayloads[v] = response;
                if (attempt < 2) {
                    // 未知账号同样计数、同样提示（remainingAttempts 同步递减，不外露存在性）
                    assertEquals(40101, codeOf(response));
                    assertEquals(
                            4 - attempt,
                            ((Number) dataOf(response).get("remainingAttempts")).intValue(),
                            "第 " + (attempt + 1) + " 次失败：两个账号的剩余次数一致");
                }
            }
            assertEquals(
                    codeOf(lastPayloads[0]),
                    codeOf(lastPayloads[1]),
                    "第 " + (attempt + 1) + " 次尝试：真实账号与未知账号 code 一致");
            assertEquals(lastPayloads[0].get("message"), lastPayloads[1].get("message"));
            // data 全等；唯一的例外是 42900 里的 lockedSeconds（锁定剩余秒数，随时间流逝递减，
            // 两个账号的锁定时刻天然差一拍，不属于存在性信号）
            if (codeOf(lastPayloads[0]) == 42900) {
                assertEquals(dataOf(lastPayloads[0]).keySet(), dataOf(lastPayloads[1]).keySet());
                assertTrue(((Number) dataOf(lastPayloads[0]).get("lockedSeconds")).longValue() > 0);
            } else {
                assertEquals(dataOf(lastPayloads[0]), dataOf(lastPayloads[1]), "data 也不外露差异");
            }
        }
        // 序列结束在锁定（42900）：探针打未知账号同样被锁，无差异信号
        assertEquals(42900, codeOf(lastPayloads[0]));
        assertEquals(42900, codeOf(lastPayloads[1]));
    }

    @Test
    void smsLoginCodeBruteForceBurnsTheCode() {
        assertEquals(
                0,
                codeOf(
                        post(
                                Map.of("tenantCode", TENANT, "phone", "13900000002"),
                                "/api/hospital/auth/sms",
                                null)));
        String realCode = sentCodes.get("13900000002");
        assertNotNull(realCode, "验证码已发到绑定手机号（doctor.demo）");

        // 6 位码穷举：连续输错 5 次（默认 sms-max-verify-failures）验证码作废
        for (int i = 0; i < 4; i++) {
            Map<?, ?> wrong = smsLogin("13900000002", "000000");
            assertEquals(40102, codeOf(wrong), "第 " + (i + 1) + " 次输错：统一 40102");
            assertEquals("验证码错误或已过期", wrong.get("message"));
        }
        assertEquals(
                42900, codeOf(smsLogin("13900000002", "000000")), "第 5 次输错：验证码作废并 42900（防 6 位码穷举）");

        // 作废后即使拿到真码也登不进来（码已被销毁，只能重新申请）
        assertEquals(40102, codeOf(smsLogin("13900000002", realCode)), "真码已被爆破作废");
    }

    @Test
    void twoFactorCodeBruteForceBurnsTheChallengeCode() {
        enableTwoFactorForTenantAdmin();
        try {
            String challengeId = loginExpectingChallenge("nurse.demo", PASSWORD);
            assertNotNull(challengeId);
            String realCode = sentCodes.get("13900000001");
            assertNotNull(realCode, "双因素验证码已自动发到绑定手机号");

            for (int i = 0; i < 4; i++) {
                Map<?, ?> wrong = verifyTwoFactor(challengeId, "000000");
                assertEquals(40102, codeOf(wrong), "第 " + (i + 1) + " 次输错：40102");
            }
            assertEquals(42900, codeOf(verifyTwoFactor(challengeId, "000000")), "第 5 次输错：验证码作废");

            // challenge 未消耗（允许重试的设计），但码已销毁：真码也过不去，双因素不可被穷举
            Map<?, ?> withRealCode = verifyTwoFactor(challengeId, realCode);
            assertEquals(40102, codeOf(withRealCode), "真码已被爆破作废，challenge 不发令牌");
            assertNull(tokenOf(withRealCode));
        } finally {
            restoreTwoFactorRoles();
        }
    }

    @Test
    void passwordResetBruteForceLocksTheResetEntry() {
        assertEquals(
                0,
                codeOf(
                        post(
                                Map.of("tenantCode", TENANT, "username", BRUTE_USER),
                                "/api/hospital/auth/password/forgot",
                                null)));
        String realCode = sentCodes.get(BRUTE_PHONE);
        assertNotNull(realCode, "重置验证码已发到绑定手机号");

        // 连续输错 4 次：统一 40102（不区分账号存在性与验证码对错）
        for (int i = 0; i < 4; i++) {
            assertEquals(
                    40102,
                    codeOf(resetPassword(BRUTE_USER, "000000", BRUTE_NEW_PASSWORD)),
                    "第 " + (i + 1) + " 次输错");
        }
        // 第 5 次：验证码作废 42900；重置入口按「HR:租户:账号」计数锁定
        assertEquals(42900, codeOf(resetPassword(BRUTE_USER, "000000", BRUTE_NEW_PASSWORD)));
        Map<?, ?> locked = resetPassword(BRUTE_USER, realCode, BRUTE_NEW_PASSWORD);
        assertEquals(42900, codeOf(locked), "锁定期间真码也进不来（重置口令需要重新走全流程）");
        assertTrue(((Number) dataOf(locked).get("lockedSeconds")).longValue() > 0);

        // 原口令未受影响（爆破重置码改不了密码）
        assertEquals(0, codeOf(loginHospital(BRUTE_USER, BRUTE_OLD_PASSWORD)));
    }

    @Test
    void captchaEndpointIsFloodLimitedPerIp() {
        // 公开取图接口按 IP 每小时 60 张：刷图脚本到量即 42900（AGENTS.md 第 6 节公开接口限流）
        for (int i = 0; i < 60; i++) {
            assertEquals(0, codeOf(get("/api/hospital/auth/captcha", null)), "第 " + (i + 1) + " 张");
        }
        assertEquals(42900, codeOf(get("/api/hospital/auth/captcha", null)), "第 61 张被限流");
    }

    // ---- 令牌泄露 ----

    @Test
    void stolenSessionCanBeRevokedByItsOwnerWithoutTokensEverSurfacing() {
        String victimToken = loginToken("doctor.demo", PASSWORD);
        String stolenToken = loginToken("doctor.demo", PASSWORD);
        assertEquals(0, codeOf(get("/api/hospital/auth/me", stolenToken)), "泄露的令牌副本可用");

        // 1. 会话接口只回显单向会话号，任何响应都不出现令牌材料
        Map<?, ?> sessions = get("/api/hospital/auth/sessions", victimToken);
        assertEquals(0, codeOf(sessions));
        List<Map<String, Object>> list = itemsOf(sessions);
        assertEquals(2, list.size(), "两个会话（本人 + 被窃副本）");
        for (Map<String, Object> session : list) {
            assertTrue(
                    SESSION_ID.matcher(String.valueOf(session.get("sessionId"))).matches(),
                    "会话号是 16 位十六进制摘要，不是令牌");
        }
        for (Map<?, ?> response :
                List.of(
                        sessions,
                        get("/api/hospital/auth/me", victimToken),
                        get("/api/hospital/auth/codes", victimToken),
                        get("/api/hospital/menus", victimToken))) {
            String raw = String.valueOf(response);
            assertFalse(raw.contains(victimToken), "响应不得回显令牌");
            assertFalse(raw.contains(stolenToken), "响应不得回显令牌");
        }

        // 2. 本人按会话号吊销被窃副本（不知道令牌本尊也能下线它）
        String stolenSessionId =
                list.stream()
                        .filter(s -> !Boolean.TRUE.equals(s.get("current")))
                        .map(s -> String.valueOf(s.get("sessionId")))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                0,
                codeOf(delete("/api/hospital/auth/sessions/" + stolenSessionId, victimToken)),
                "吊销被窃会话");

        // 3. 被窃令牌下一次请求即 40100，本人会话不受影响
        assertEquals(40100, codeOf(get("/api/hospital/auth/me", stolenToken)));
        assertEquals(0, codeOf(get("/api/hospital/auth/me", victimToken)));
    }

    @Test
    void issuedTokensAreOpaqueAndCarryNoAccountMaterial() {
        String first = loginToken("doctor.demo", PASSWORD);
        String second = loginToken("doctor.demo", PASSWORD);

        assertNotEquals(first, second, "两次登录令牌不同（不可预测、不可复用）");
        for (String token : List.of(first, second)) {
            assertTrue(OPAQUE_TOKEN.matcher(token).matches(), "256 位随机不透明令牌（base64url 43 字符）");
            assertFalse(token.contains("."), "不是 JWT（无分段点号）");
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            assertEquals(32, decoded.length, "解码后 32 字节 = 256 位");
            String material = new String(decoded, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertFalse(material.contains("doctor.demo"), "令牌不携带账号");
            assertFalse(material.contains("demoa"), "令牌不携带租户编码");
        }
    }

    // ---- 重放 ----

    @Test
    void smsLoginCodeCannotBeReplayedAfterASuccessfulLogin() {
        assertEquals(
                0,
                codeOf(
                        post(
                                Map.of("tenantCode", TENANT, "phone", "13900000002"),
                                "/api/hospital/auth/sms",
                                null)));
        String code = sentCodes.get("13900000002");
        String token = tokenOf(smsLogin("13900000002", code));
        assertNotNull(token);
        assertEquals(0, codeOf(get("/api/hospital/auth/me", token)));

        // 同一验证码第二次提交（钓鱼截获后重放）：已被消费，40102
        assertEquals(40102, codeOf(smsLogin("13900000002", code)));
        // 已签发的会话不受重放影响
        assertEquals(0, codeOf(get("/api/hospital/auth/me", token)));
    }

    @Test
    void twoFactorChallengeCannotBeReplayedAfterASuccessfulVerify() {
        enableTwoFactorForTenantAdmin();
        try {
            String challengeId = loginExpectingChallenge("nurse.demo", PASSWORD);
            String code = sentCodes.get("13900000001");
            String token = tokenOf(verifyTwoFactor(challengeId, code));
            assertNotNull(token);
            assertEquals(0, codeOf(get("/api/hospital/auth/me", token)));

            // 重放同一 challenge（连同正确验证码）：challenge 一次性，40100，且不再发令牌
            Map<?, ?> replay = verifyTwoFactor(challengeId, code);
            assertEquals(40100, codeOf(replay), "challenge 已消费");
            assertNull(tokenOf(replay));
            assertEquals(0, codeOf(get("/api/hospital/auth/me", token)), "已签发会话不受影响");
        } finally {
            restoreTwoFactorRoles();
        }
    }

    @Test
    void resetCodeCannotBeReplayedAfterASuccessfulReset() {
        assertEquals(
                0,
                codeOf(
                        post(
                                Map.of("tenantCode", TENANT, "username", BRUTE_USER),
                                "/api/hospital/auth/password/forgot",
                                null)));
        String code = sentCodes.get(BRUTE_PHONE);
        assertEquals(0, codeOf(resetPassword(BRUTE_USER, code, BRUTE_NEW_PASSWORD)));

        // 重放同一验证码改第二个密码：验证码一次一用，40102
        assertEquals(40102, codeOf(resetPassword(BRUTE_USER, code, BRUTE_OTHER_PASSWORD)));
        // 第一次的新密码生效，重放想改的第二个密码无效
        assertEquals(0, codeOf(loginHospital(BRUTE_USER, BRUTE_NEW_PASSWORD)));
        assertEquals(40101, codeOf(loginHospital(BRUTE_USER, BRUTE_OTHER_PASSWORD)));
    }

    // ---- 辅助 ----

    private void stubSender() {
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
    }

    private void enableTwoFactorForTenantAdmin() {
        runInTenant(
                () ->
                        jdbc.update(
                                "UPDATE cfg_param SET param_value = 'TENANT_ADMIN'"
                                        + " WHERE param_key = 'security.two-factor.roles'"));
    }

    private void restoreTwoFactorRoles() {
        runInTenant(
                () ->
                        jdbc.update(
                                "UPDATE cfg_param SET param_value = ''"
                                        + " WHERE param_key = 'security.two-factor.roles'"));
    }

    private Map<?, ?> loginHospital(String username, String password) {
        return post(
                Map.of("tenantCode", TENANT, "username", username, "password", password),
                "/api/hospital/auth/login",
                null);
    }

    private String loginToken(String username, String password) {
        Map<?, ?> response = loginHospital(username, password);
        assertEquals(0, codeOf(response), "message=" + response.get("message"));
        return tokenOf(response);
    }

    /** 密码第一步通过但需要双因素：返回 challengeId（不发令牌）。 */
    private String loginExpectingChallenge(String username, String password) {
        Map<?, ?> response = loginHospital(username, password);
        assertEquals(0, codeOf(response));
        assertNull(tokenOf(response), "双因素第一步不发令牌");
        return (String) dataOf(response).get("challengeId");
    }

    private Map<?, ?> smsLogin(String phone, String code) {
        return post(
                Map.of("tenantCode", TENANT, "phone", phone, "code", code),
                "/api/hospital/auth/login/sms",
                null);
    }

    private Map<?, ?> verifyTwoFactor(String challengeId, String code) {
        return post(
                Map.of("challengeId", challengeId, "code", code),
                "/api/hospital/auth/2fa/verify",
                null);
    }

    private Map<?, ?> resetPassword(String username, String code, String newPassword) {
        return post(
                Map.of(
                        "tenantCode",
                        TENANT,
                        "username",
                        username,
                        "code",
                        code,
                        "newPassword",
                        newPassword),
                "/api/hospital/auth/password/reset",
                null);
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

    private Map<?, ?> delete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class)
                .getBody();
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> dataOf(Map<?, ?> response) {
        return response.get("data") == null ? Map.of() : (Map<?, ?>) response.get("data");
    }

    private static String tokenOf(Map<?, ?> response) {
        Map<?, ?> data = dataOf(response);
        return (String) data.get("token");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> itemsOf(Map<?, ?> response) {
        return (List<Map<String, Object>>) response.get("data");
    }

    private void deleteKeys(Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void runInTenant(Runnable action) {
        com.crdp.framework.tenant.TenantContext.runAsTenant(TENANT_ID, TENANT, action);
    }
}
