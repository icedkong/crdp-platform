package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import java.util.Map;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * E04-T08 / PLT-AUTH-012 端到端（完整应用 + 真实 MySQL/Redis，HTTP 直连）。
 * 验收原文：明文查看、导出前需再次验证——本任务先落地框架并接入现有敏感操作（审计导出提交 / 下载、重置密码），患者明文查看接口随 E07-T05 接入同一注解。
 *
 * <ol>
 *   <li>POST /auth/reauth：密码错误 42200（带剩余次数）且计入登录失败计数，正确密码签发 5 分钟 reauthToken 并清零；
 *   <li>敏感接口缺 X-Reauth-Token → 40304，并写 result=DENIED 操作日志（audit.md §1）；
 *   <li>携带有效 reauthToken → 放行（导出提交成功）；同窗口内可重复使用；
 *   <li>reauthToken 绑定会话：换个会话携带（如令牌被搬去另一登录）→ 40304；
 *   <li>重置密码同口径被守卫；平台端同构。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT。只读现有演示账号，无自建数据需要清理。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class ReauthFlowIT {

    private static final String TENANT = "demoa";
    private static final String PASSWORD = "Demo@2026";
    private static final String ADMIN = "nurse.demo";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    // ---------------------------------------------------------------- 二次认证口

    @Test
    void wrongPasswordIs42200WithRemainingAttemptsThenCorrectOneSucceeds() {
        String token = hospitalLogin(ADMIN);

        Map<?, ?> wrong =
                post("/api/hospital/auth/reauth", token, Map.of("password", "Wrong@2026"));
        assertEquals(42200, codeOf(wrong), "密码不正确（不区分账号消失）");
        assertNotNull(data(wrong).get("remainingAttempts"), "带剩余次数（计入登录失败计数）");

        Map<?, ?> right = post("/api/hospital/auth/reauth", token, Map.of("password", PASSWORD));
        assertEquals(0, codeOf(right), "正确密码应通过：" + right);
        assertEquals(300, ((Number) data(right).get("expiresInSeconds")).intValue(), "5 分钟有效");
        String reauthToken = (String) data(right).get("reauthToken");
        assertNotNull(reauthToken);
        assertEquals(43, reauthToken.length(), "256 位随机不透明值（base64url 43 字符）");
    }

    @Test
    void reauthRequiresLoginAndValidation() {
        assertEquals(
                40100,
                codeOf(post("/api/hospital/auth/reauth", null, Map.of("password", PASSWORD))),
                "未登录不能二次认证");
        assertEquals(
                40001,
                codeOf(post("/api/hospital/auth/reauth", hospitalLogin(ADMIN), Map.of())),
                "缺 password 按参数校验 40001");
    }

    // ---------------------------------------------------------------- 敏感接口闸门（医院端）

    @Test
    void exportWithoutReauthTokenIs40304AndWritesDeniedAudit() {
        String token = hospitalLogin(ADMIN);
        Map<String, Object> body = exportBody();

        assertEquals(
                40304,
                codeOf(post("/api/hospital/system/audit/exports", token, body)),
                "缺 X-Reauth-Token 一律 40304");

        // audit.md §1：被拒请求写 result=DENIED 操作日志（error_code=40304；用例间顺序不定，只断言存在）
        runInTenant(
                () ->
                        assertEquals(
                                1,
                                Math.min(
                                        1,
                                        jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM aud_log WHERE result ="
                                                        + " 'DENIED' AND error_code = 40304 AND"
                                                        + " module = 'system'",
                                                Integer.class)),
                                "40304 应写 DENIED 操作日志"));
    }

    @Test
    void exportWithValidReauthTokenProceedsAndTokenIsReusable() throws InterruptedException {
        String token = hospitalLogin(ADMIN);
        String reauthToken = reauth(token);
        Map<String, Object> body = exportBody();

        Map<?, ?> first = post("/api/hospital/system/audit/exports", token, body, reauthToken);
        assertEquals(0, codeOf(first), "携带有效 reauthToken 应放行：" + first);
        long exportId = ((Number) data(first).get("exportId")).longValue();

        // 窗口内可重复使用：同 token 再提交一次（5 分钟有效，导出提交后可继续下载）
        Map<?, ?> second = post("/api/hospital/system/audit/exports", token, body, reauthToken);
        assertEquals(0, codeOf(second), "同窗口内重复使用应放行");

        // 下载同样被守卫：无头 40304（统一信封 JSON），带头要等文件 READY 后真正拿到 CSV
        assertEquals(
                40304,
                ((Number)
                                rest.exchange(
                                                "/api/hospital/system/audit/exports/"
                                                        + exportId
                                                        + "/download",
                                                HttpMethod.GET,
                                                new HttpEntity<>(jsonHeaders(token, null)),
                                                Map.class)
                                        .getBody()
                                        .get("code"))
                        .intValue(),
                "下载缺 X-Reauth-Token 同样 40304");

        awaitReady(token, exportId);
        byte[] csv =
                rest.exchange(
                                "/api/hospital/system/audit/exports/" + exportId + "/download",
                                HttpMethod.GET,
                                new HttpEntity<>(jsonHeaders(token, reauthToken)),
                                byte[].class)
                        .getBody();
        assertNotNull(csv, "下载应得到文件体");
        assertTrue(csv.length > 0, "带有效 reauthToken 的下载应真正拿到 CSV 文件");
    }

    /** 轮询「我的导出」到 READY（租户管理员免审批，直接生成）。 */
    private void awaitReady(String token, long exportId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Map<?, ?> mine =
                    rest.exchange(
                                    "/api/hospital/system/audit/exports",
                                    HttpMethod.GET,
                                    new HttpEntity<>(jsonHeaders(token, null)),
                                    Map.class)
                            .getBody();
            for (Object item : (java.util.List<?>) data(mine).get("items")) {
                Map<?, ?> job = (Map<?, ?>) item;
                if (((Number) job.get("id")).longValue() == exportId) {
                    if ("READY".equals(job.get("status"))) {
                        return;
                    }
                    assertEquals("GENERATING", job.get("status"), "生成失败：" + job.get("failReason"));
                }
            }
            Thread.sleep(200);
        }
        throw new AssertionError("导出未在 15 秒内生成完成");
    }

    @Test
    void reauthTokenIsBoundToItsSession() {
        String issuingSession = hospitalLogin(ADMIN);
        String otherSession = hospitalLogin(ADMIN);
        String reauthToken = reauth(issuingSession);

        // 令牌搬到另一个会话（同一账号另一次登录）无效：绑定的是签发会话的会话号
        assertEquals(
                40304,
                codeOf(
                        post(
                                "/api/hospital/system/audit/exports",
                                otherSession,
                                exportBody(),
                                reauthToken)));
    }

    @Test
    void resetPasswordIsGuardedByReauth() {
        String token = hospitalLogin(ADMIN);

        // 拦截器在业务逻辑之前：用户不存在也无妨，先验证闸门本身
        assertEquals(
                40304,
                codeOf(post("/api/hospital/system/users/999999/reset-password", token, Map.of())));
    }

    @Test
    void permissionStillPrecedesReauth() {
        // doctor.demo 无 tenant:audit:export：缺权限返回 40300（权限码在前，二次认证在后）
        String token = hospitalLogin("doctor.demo");
        assertEquals(
                40300, codeOf(post("/api/hospital/system/audit/exports", token, exportBody())));
    }

    // ---------------------------------------------------------------- 平台端（同构）

    @Test
    void platformEndMirrorsHospital() {
        String token = platformLogin("platform.admin");
        Map<String, Object> body = exportBody();

        assertEquals(
                40304,
                codeOf(post("/api/platform/audit/exports", token, body)),
                "平台敏感接口缺 X-Reauth-Token 同样 40304");

        Map<?, ?> reauthResponse =
                post("/api/platform/auth/reauth", token, Map.of("password", PASSWORD));
        assertEquals(0, codeOf(reauthResponse), "平台端二次认证：" + reauthResponse);
        String reauthToken = (String) data(reauthResponse).get("reauthToken");

        Map<?, ?> submitted = post("/api/platform/audit/exports", token, body, reauthToken);
        assertEquals(0, codeOf(submitted), "携带 reauthToken 平台导出应放行：" + submitted);
        assertEquals("PENDING_APPROVAL", data(submitted).get("status"), "平台账号导出一律待审批");
    }

    // ---------------------------------------------------------------- 辅助

    private Map<String, Object> exportBody() {
        return Map.of(
                "type", "operation",
                "condition",
                        Map.of(
                                "startTime",
                                "2026-01-01T00:00:00+08:00",
                                "endTime",
                                "2026-03-01T00:00:00+08:00"),
                "reason", "E04-T08 二次认证用例（示例）");
    }

    private String hospitalLogin(String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", TENANT, "username", username, "password", PASSWORD));
        assertEquals(0, codeOf(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    private String platformLogin(String username) {
        Map<?, ?> response =
                post(
                        "/api/platform/auth/login",
                        null,
                        Map.of("username", username, "password", PASSWORD));
        assertEquals(0, codeOf(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    private String reauth(String token) {
        Map<?, ?> response = post("/api/hospital/auth/reauth", token, Map.of("password", PASSWORD));
        assertEquals(0, codeOf(response), "二次认证失败：" + response);
        return (String) data(response).get("reauthToken");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return post(path, token, body, null);
    }

    private Map<?, ?> post(
            String path, String token, Map<String, Object> body, String reauthToken) {
        return rest.postForEntity(
                        path, new HttpEntity<>(body, jsonHeaders(token, reauthToken)), Map.class)
                .getBody();
    }

    private HttpHeaders jsonHeaders(String token, String reauthToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (reauthToken != null) {
            headers.set("X-Reauth-Token", reauthToken);
        }
        headers.set("User-Agent", "IT-E04T08-UA/1.0");
        return headers;
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }

    private void runInTenant(Runnable action) {
        TenantContext.runAsTenant(1L, TENANT, action);
    }
}
