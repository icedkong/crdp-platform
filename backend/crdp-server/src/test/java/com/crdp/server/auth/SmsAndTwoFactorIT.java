package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * E04-T04 / PLT-AUTH-002、003 端到端（完整应用 + 真实 MySQL/Redis，HTTP 走起）： 短信验证码登录、验证码租户隔离、双因素按角色配置与平台强制。
 * 短信通道用桩截获验证码（真实通道 E09-T03）；CRDP_IT_MYSQL_PORT 存在时运行（CI 起 docker compose 注入端口）， 与
 * TenantIsolationAcceptanceIT 同一套环境。演示手机号为 1390000xxxx 虚构段。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@TestPropertySource(properties = "crdp.security.platform-two-factor-enabled=true")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class SmsAndTwoFactorIT {

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;

    /** 截获发送通道里的验证码（按手机号）。 */
    @MockitoBean private SmsCodeSender smsCodeSender;

    private final Map<String, String> sentCodes = new ConcurrentHashMap<>();

    @BeforeEach
    void cleanSmsState() {
        // 真实 Redis：清掉冷却/每日/验证码键，避免用例之间互相限流（challenge 与会话键各自唯一，不清）
        Set<String> keys = redis.keys("crdp:sms:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
        stubSender();
    }

    @Test
    void hospitalSmsLoginIssuesAUsableToken() {
        // 1. 发送验证码：无论是否绑定都返回成功
        Map<?, ?> sent =
                post(
                        Map.of("tenantCode", "demoa", "phone", "13900000002"),
                        "/api/hospital/auth/sms",
                        null);
        assertEquals(0, sent.get("code"));

        // 2. 用收到的验证码登录（doctor.demo 绑定 13900000002）
        Map<?, ?> login =
                post(
                        Map.of(
                                "tenantCode",
                                "demoa",
                                "phone",
                                "13900000002",
                                "code",
                                sentCodes.get("13900000002")),
                        "/api/hospital/auth/login/sms",
                        null);
        assertEquals(0, login.get("code"), "短信验证码登录成功：message=" + login.get("message"));
        Map<?, ?> data = (Map<?, ?>) login.get("data");
        String token = (String) data.get("token");
        assertNotNull(token);
        assertNull(data.get("challengeId"), "短信登录不叠加第二因素");

        // 3. 令牌可用
        Map<?, ?> me = get("/api/hospital/auth/me", token);
        assertEquals(0, me.get("code"));
        assertEquals("doctor.demo", ((Map<?, ?>) me.get("data")).get("username"));
        assertEquals("demoa", ((Map<?, ?>) me.get("data")).get("tenantCode"));
    }

    @Test
    void wrongCodeIs40102AndCodeIsTenantScoped() {
        post(Map.of("tenantCode", "demob", "phone", "13900000002"), "/api/hospital/auth/sms", null);

        Map<?, ?> wrong =
                post(
                        Map.of("tenantCode", "demob", "phone", "13900000002", "code", "000000"),
                        "/api/hospital/auth/login/sms",
                        null);
        assertEquals(40102, wrong.get("code"), "验证码错误统一 40102");

        Map<?, ?> crossTenant =
                post(
                        Map.of(
                                "tenantCode",
                                "demoa",
                                "phone",
                                "13900000002",
                                "code",
                                sentCodes.get("13900000002")),
                        "/api/hospital/auth/login/sms",
                        null);
        assertEquals(40102, crossTenant.get("code"), "B 院签发的验证码不能登录 A 院");
    }

    @Test
    void resendWithinCooldownIs42900() {
        post(Map.of("tenantCode", "demob", "phone", "13900000001"), "/api/hospital/auth/sms", null);
        Map<?, ?> second =
                post(
                        Map.of("tenantCode", "demob", "phone", "13900000001"),
                        "/api/hospital/auth/sms",
                        null);
        assertEquals(42900, second.get("code"), "60 秒冷却");
    }

    @Test
    void hospitalRoleConfiguredTwoFactorChallengesThenVerifies() {
        // 租户库按角色配置双因素：nurse.demo 是 TENANT_ADMIN（绑定 13900000001）
        com.crdp.framework.tenant.TenantContext.callAsTenant(
                1L,
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE cfg_param SET param_value = 'TENANT_ADMIN' WHERE param_key = 'security.two-factor.roles'"));
        try {
            Map<?, ?> first =
                    post(
                            Map.of(
                                    "tenantCode",
                                    "demoa",
                                    "username",
                                    "nurse.demo",
                                    "password",
                                    "Demo@2026"),
                            "/api/hospital/auth/login",
                            null);
            assertEquals(0, first.get("code"));
            Map<?, ?> data = (Map<?, ?>) first.get("data");
            assertNull(data.get("token"), "角色命中双因素时密码第一步不发令牌");
            String challengeId = (String) data.get("challengeId");
            assertNotNull(challengeId);
            assertEquals(java.util.List.of("SMS"), data.get("methods"));

            Map<?, ?> verified =
                    post(
                            Map.of(
                                    "challengeId",
                                    challengeId,
                                    "code",
                                    sentCodes.get("13900000001")),
                            "/api/hospital/auth/2fa/verify",
                            null);
            assertEquals(0, verified.get("code"), "message=" + verified.get("message"));
            String token = (String) ((Map<?, ?>) verified.get("data")).get("token");
            Map<?, ?> me = get("/api/hospital/auth/me", token);
            assertEquals("nurse.demo", ((Map<?, ?>) me.get("data")).get("username"));
        } finally {
            com.crdp.framework.tenant.TenantContext.callAsTenant(
                    1L,
                    "demoa",
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_param SET param_value = '' WHERE param_key = 'security.two-factor.roles'"));
        }
    }

    @Test
    void platformForcedTwoFactorIssuesTokenAfterVerify() {
        Map<?, ?> first =
                post(
                        Map.of("username", "platform.admin", "password", "Demo@2026"),
                        "/api/platform/auth/login",
                        null);
        assertEquals(0, first.get("code"));
        Map<?, ?> data = (Map<?, ?>) first.get("data");
        assertNull(data.get("token"), "平台强制双因素：密码第一步只发 challenge");
        String challengeId = (String) data.get("challengeId");
        assertNotNull(challengeId);

        Map<?, ?> verified =
                post(
                        Map.of("challengeId", challengeId, "code", sentCodes.get("13900000003")),
                        "/api/platform/auth/2fa/verify",
                        null);
        assertEquals(0, verified.get("code"), "message=" + verified.get("message"));
        String token = (String) ((Map<?, ?>) verified.get("data")).get("token");
        Map<?, ?> me = get("/api/platform/auth/me", token);
        assertEquals("PLATFORM", ((Map<?, ?>) me.get("data")).get("sessionType"));
    }

    // ---- helpers ----

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
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }
}
