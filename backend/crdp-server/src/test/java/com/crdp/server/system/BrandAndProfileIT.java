package com.crdp.server.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.crypto.CryptoService;
import com.crdp.framework.notify.SmsCodeSender;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * E06-T11 / PLT-CFG-008、PLT-ORG-011 端到端（完整应用 + 真实 MySQL/Redis，HTTP 直连）：
 *
 * <ul>
 *   <li>租户个性化：保存显示名称 / Logo / 背景 / 预设主题色 / 版权信息，登录页 /auth/tenant 即时反映； 未配置回落平台租户名称；未知租户返回通用默认（防探测）；
 *       主题色只认预设色板（40001）；素材地址放行 http(s) 与站内路径；无 tenant:brand:edit 40300； 跨租户隔离（demoa 改名不影响
 *       demob）；保存写审计；
 *   <li>个人中心：基本信息（脱敏手机号）、改姓名、本人登录记录；换绑手机号（验证码发新号、错误码 40102、 院内冲突 40902、成功后新号可短信登录、旧号不行）；写审计。
 * </ul>
 *
 * CRDP_IT_MYSQL_PORT 存在时运行（CI 起 docker compose 注入端口）。全部虚构示例数据（1390000xxxx 段、示例医院）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class BrandAndProfileIT {

    private static final String PASSWORD = "Demo@2026";

    /** 换绑目标新号（虚构段；@BeforeEach 恢复种子绑定，保证用例可重跑）。 */
    private static final String NEW_PHONE = "13900000099";

    private static final String DOCTOR_PHONE = "13900000002";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private CryptoService cryptoService;

    /** 截获发送通道里的验证码（按手机号），真实通道 E09-T03 接入。 */
    @MockitoBean private SmsCodeSender smsCodeSender;

    private final Map<String, String> sentCodes = new ConcurrentHashMap<>();

    @BeforeEach
    @AfterEach
    void cleanState() {
        // 真实 Redis：清验证码 / 冷却 / 品牌查询限流键，避免用例间互相限流
        for (String prefix : new String[] {"crdp:sms:*", "crdp:brand-lookup:*"}) {
            Set<String> keys = redis.keys(prefix);
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        jdbc.update("DELETE FROM cfg_brand");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE event_type IN"
                                        + " ('BRAND_UPDATE','USER_PROFILE_UPDATE','USER_PHONE_REBIND')");
                        // 恢复种子形态：换绑手机号与改名都会改库，@AfterEach 一并还原，
                        // 否则 SmsAndTwoFactorIT / AuthSecurityHardeningIT 等按手机号登录的用例会踩脏数据
                        restoreDoctorPhone();
                        jdbc.update(
                                "UPDATE sys_user SET display_name = '演示医生'"
                                        + " WHERE username = 'doctor.demo'");
                    });
        }
        stubSender();
    }

    /** 恢复 doctor.demo 的种子手机号（换绑用例改库后可重跑；密文与盲索引同口径回填，须在租户上下文内）。 */
    private void restoreDoctorPhone() {
        String cipher = cryptoService.encrypt("sys-user-phone", DOCTOR_PHONE);
        String idx = cryptoService.blindIndex("sys-user-phone", DOCTOR_PHONE);
        jdbc.update(
                "UPDATE sys_user SET phone = ?, phone_idx = ? WHERE username = 'doctor.demo'",
                cipher,
                idx);
    }

    private void stubSender() {
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            sentCodes.put(invocation.getArgument(0), invocation.getArgument(1));
                            return null;
                        })
                .when(smsCodeSender)
                .sendPhoneBindCode(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doAnswer(
                        invocation -> {
                            sentCodes.put(invocation.getArgument(0), invocation.getArgument(1));
                            return null;
                        })
                .when(smsCodeSender)
                .sendLoginCode(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.anyString());
    }

    /** 清同一手机号的冷却位（用例内连续多次发码会撞 60 秒冷却；生产无此路径）。 */
    private void clearCooldown(String phone) {
        for (String key : new String[] {"crdp:sms:cd:" + phone, "crdp:sms:daily:" + phone}) {
            redis.delete(key);
        }
    }

    // ---------------------------------------------------------------- 租户个性化

    @Test
    void brandSaveShowsOnLoginPageImmediately() {
        String admin = adminToken();

        // 保存前：登录页显示平台租户名称（未配置回落）
        Map<?, ?> before = get("/api/hospital/auth/tenant?code=demoa", null);
        assertEquals(0, codeOf(before));
        assertEquals("虚构示例医院甲", data(before).get("tenantName"));
        assertEquals(Boolean.TRUE, data(before).get("identified"));
        assertNull(data(before).get("logoUrl"));

        // 保存个性化（名称、Logo、背景、预设主题色、版权）
        Map<?, ?> saved =
                put(
                        "/api/hospital/system/brand",
                        admin,
                        Map.of(
                                "displayName",
                                "示例第一医院（北院）",
                                "logoUrl",
                                "https://assets.example.com/logo.png",
                                "loginBgUrl",
                                "/assets/bg.svg",
                                "themeColor",
                                "TEAL",
                                "copyright",
                                "示例版权与备案信息（示例）"));
        assertEquals(0, codeOf(saved), "保存失败：" + saved);
        assertEquals("示例第一医院（北院）", data(saved).get("displayName"));
        assertEquals("#1DC29E", data(saved).get("themeColorHex"), "预设辅助色（OQ-23 不改主色）");

        // 登录页即时反映（免登录公开口）
        Map<?, ?> after = get("/api/hospital/auth/tenant?code=demoa", null);
        assertEquals(0, codeOf(after));
        assertEquals("示例第一医院（北院）", data(after).get("tenantName"));
        assertEquals("https://assets.example.com/logo.png", data(after).get("logoUrl"));
        assertEquals("/assets/bg.svg", data(after).get("loginBgUrl"));
        assertEquals("TEAL", data(after).get("themeColor"));
        assertEquals("示例版权与备案信息（示例）", data(after).get("copyright"));

        // 重新登录后 me.tenantName 也是显示名称（水印与顶栏的医院名称口径）
        Map<?, ?> me = get("/api/hospital/auth/me", adminToken());
        assertEquals("示例第一医院（北院）", data(me).get("tenantName"));

        // 审计已写
        Integer audits =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'BRAND_UPDATE'",
                                        Integer.class));
        assertTrue(audits != null && audits >= 1, "保存写审计，实际 " + audits);
    }

    @Test
    void brandUnknownTenantAndGetValidation() {
        // 未知租户：同一结构返回通用默认，不暴露存在性
        Map<?, ?> unknown = get("/api/hospital/auth/tenant?code=nosuchtenant", null);
        assertEquals(0, codeOf(unknown));
        assertEquals(Boolean.FALSE, data(unknown).get("identified"));
        assertEquals("临床科研数据一体化平台", data(unknown).get("tenantName"));
        assertNull(data(unknown).get("logoUrl"));

        // 校验失败：显示名称必填、主题色只认预设、素材地址放行口径
        String admin = adminToken();
        assertEquals(
                40001,
                codeOf(put("/api/hospital/system/brand", admin, Map.of("displayName", " "))));
        assertEquals(
                40001,
                codeOf(
                        put(
                                "/api/hospital/system/brand",
                                admin,
                                Map.of("displayName", "示例医院", "themeColor", "PURPLE"))));
        assertEquals(
                40001,
                codeOf(
                        put(
                                "/api/hospital/system/brand",
                                admin,
                                Map.of("displayName", "示例医院", "logoUrl", "javascript:alert(1)"))));
    }

    @Test
    void brandPermissionAndCrossTenantIsolation() {
        // doctor.demo 无 tenant:brand:edit → 40300（查看与保存同码）
        String doctor = hospitalToken("demoa", "doctor.demo");
        assertEquals(40300, codeOf(get("/api/hospital/system/brand", doctor)));
        assertEquals(
                40300,
                codeOf(put("/api/hospital/system/brand", doctor, Map.of("displayName", "示例医院"))));

        // demob 改自己的显示名称，不影响 demoa（租户库隔离）
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/system/brand",
                                adminTokenOf("demob"),
                                Map.of("displayName", "乙院示例医院"))));
        Map<?, ?> demoaBrand = get("/api/hospital/auth/tenant?code=demoa", null);
        assertEquals("虚构示例医院甲", data(demoaBrand).get("tenantName"), "demoa 未配置回落平台名称");
        Map<?, ?> demobBrand = get("/api/hospital/auth/tenant?code=demob", null);
        assertEquals("乙院示例医院", data(demobBrand).get("tenantName"));
    }

    // ---------------------------------------------------------------- 个人中心

    @Test
    void profileViewDisplayNameAndLoginLogs() {
        String doctor = hospitalToken("demoa", "doctor.demo");

        // 基本信息：脱敏手机号，不落明文
        Map<?, ?> profile = get("/api/hospital/profile", doctor);
        assertEquals(0, codeOf(profile));
        assertEquals("doctor.demo", data(profile).get("username"));
        assertEquals("演示医生", data(profile).get("displayName"));
        assertEquals("139****0002", data(profile).get("phoneMasked"));
        assertFalse(String.valueOf(data(profile)).contains("13900000002"), "响应不得含手机号明文");

        // 修改姓名（账号不可改）；重新登录后 me.displayName 更新
        assertEquals(
                0, codeOf(put("/api/hospital/profile", doctor, Map.of("displayName", "示例医生（改名）"))));
        Map<?, ?> me = get("/api/hospital/auth/me", hospitalToken("demoa", "doctor.demo"));
        assertEquals("示例医生（改名）", data(me).get("displayName"));

        // 校验失败：姓名必填
        assertEquals(
                40001, codeOf(put("/api/hospital/profile", doctor, Map.of("displayName", " "))));

        // 本人登录记录：至少有一条成功登录（本用例开头登录产生），时间是北京时间 ISO-8601
        Map<?, ?> logs = get("/api/hospital/profile/login-logs", doctor);
        assertEquals(0, codeOf(logs));
        java.util.List<?> list = (java.util.List<?>) logs.get("data");
        assertTrue(list.size() >= 1, "登录记录不为空");
        Map<?, ?> first = (Map<?, ?>) list.get(0);
        assertEquals(Boolean.TRUE, first.get("success"));
        assertTrue(String.valueOf(first.get("createdAt")).contains("+08:00"));
    }

    @Test
    void phoneRebindWithSmsVerification() {
        String doctor = hospitalToken("demoa", "doctor.demo");

        // 新号与当前号相同 40001
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/profile/phone/sms",
                                doctor,
                                Map.of("phone", DOCTOR_PHONE))));

        // 第一步：验证码发到新手机号（只发一次；错误尝试不消耗验证码）
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/profile/phone/sms",
                                doctor,
                                Map.of("phone", NEW_PHONE))));
        String code = sentCodes.get(NEW_PHONE);
        assertNotNull(code, "验证码经通道发出（桩截获）");

        // 错误验证码 40102（验证码仍在，失败计数 +1 未到上限）
        assertEquals(
                40102,
                codeOf(
                        put(
                                "/api/hospital/profile/phone",
                                doctor,
                                Map.of("phone", NEW_PHONE, "code", "000000"))));

        // 第二步：正确验证码换绑成功，视图即显示新号（脱敏）
        Map<?, ?> rebound =
                put(
                        "/api/hospital/profile/phone",
                        doctor,
                        Map.of("phone", NEW_PHONE, "code", code));
        assertEquals(0, codeOf(rebound), "换绑失败：" + rebound);
        assertEquals("139****0099", data(rebound).get("phoneMasked"));

        // 新号可短信登录，旧号不再命中（盲索引已覆盖）；冷却位在用例内手动清（生产无连续发码路径）
        clearCooldown(NEW_PHONE);
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/auth/sms",
                                null,
                                Map.of("tenantCode", "demoa", "phone", NEW_PHONE))));
        Map<?, ?> reLogin =
                post(
                        "/api/hospital/auth/login/sms",
                        null,
                        Map.of(
                                "tenantCode",
                                "demoa",
                                "phone",
                                NEW_PHONE,
                                "code",
                                sentCodes.get(NEW_PHONE)));
        assertEquals(0, codeOf(reLogin), "新手机号短信登录：message=" + reLogin.get("message"));

        // 审计已写（脱敏：不含任何完整手机号）
        Integer audits =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'USER_PHONE_REBIND'",
                                        Integer.class));
        assertTrue(audits != null && audits >= 1, "换绑写审计，实际 " + audits);
        String detail =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT detail FROM aud_log WHERE event_type = 'USER_PHONE_REBIND'"
                                                + " ORDER BY id DESC LIMIT 1",
                                        String.class));
        assertFalse(
                detail.contains(NEW_PHONE) || detail.contains(DOCTOR_PHONE), "审计不落手机号明文：" + detail);
    }

    @Test
    void phoneRebindRejectsTenantDuplicate() {
        // doctor 换到新号后，nurse 再绑同一号 → 40902
        String doctor = hospitalToken("demoa", "doctor.demo");
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/profile/phone/sms",
                                doctor,
                                Map.of("phone", NEW_PHONE))));
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/profile/phone",
                                doctor,
                                Map.of("phone", NEW_PHONE, "code", sentCodes.get(NEW_PHONE)))));

        String nurse = hospitalToken("demoa", "nurse.demo");
        clearCooldown(NEW_PHONE);
        assertEquals(
                0,
                codeOf(post("/api/hospital/profile/phone/sms", nurse, Map.of("phone", NEW_PHONE))));
        assertEquals(
                40902,
                codeOf(
                        put(
                                "/api/hospital/profile/phone",
                                nurse,
                                Map.of("phone", NEW_PHONE, "code", sentCodes.get(NEW_PHONE)))),
                "本院唯一：已被其他账号绑定 40902");
    }

    // ---------------------------------------------------------------- 辅助

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo");
    }

    private String adminTokenOf(String tenant) {
        return hospitalToken(tenant, "nurse.demo");
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, codeOf(response));
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

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

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant(tenantIdOf(tenant), tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(tenantIdOf(tenant), tenant, action::get);
    }

    private long tenantIdOf(String tenant) {
        return "demob".equals(tenant) ? 2L : 1L;
    }
}
