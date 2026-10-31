package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.security.PermissionVersionStore;
import com.crdp.framework.tenant.TenantContext;
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
import org.springframework.test.context.ActiveProfiles;

/**
 * E04-T06 / PLT-AUTH-008、009、PLT-PERM-010 端到端（完整应用 + 真实 MySQL/Redis，HTTP 走起）： 在线用户列表与强制下线（被踢令牌下次请求
 * 40100）、本人在线会话查看与下线、无权限 40300、 权限版本号递增后旧令牌下一次请求即按新权限执行（tasks.csv 验收：改权限后旧令牌下次请求即失效）、
 * 单端登录（新登录顶掉旧会话）。CRDP_IT_MYSQL_PORT 存在时运行（CI 起 docker compose 注入端口）。 全部使用虚构演示账号。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class SessionAdminAndPermissionVersionIT {

    private static final String TENANT = "demoa";
    private static final long TENANT_ID = 1L;
    private static final String PASSWORD = "Demo@2026";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StringRedisTemplate redis;
    @Autowired private PermissionVersionStore permissionVersions;

    @BeforeEach
    void cleanState() {
        // 真实 Redis：清会话与权限版本，避免用例间互相影响
        deleteKeys(redis.keys("crdp:sess:*"));
        deleteKeys(redis.keys("crdp:permver:*"));
        runInTenant(
                () -> {
                    // 还原 FOLLOWUP_DOCTOR 的菜单授权（权限变更用例会撤销 22）
                    jdbc.update(
                            "INSERT INTO sys_role_menu (role_id, menu_id)"
                                    + " SELECT r.id, 22 FROM sys_role r"
                                    + " WHERE r.code = 'FOLLOWUP_DOCTOR'"
                                    + " AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm"
                                    + " WHERE rm.role_id = r.id AND rm.menu_id = 22)");
                    jdbc.update(
                            "UPDATE cfg_param SET param_value = 'false'"
                                    + " WHERE param_key = 'security.session.single-login'");
                });
    }

    @Test
    void onlineSessionsKickAndOwnSessionsEndToEnd() {
        String nurse = hospitalToken("nurse.demo");
        String doctorA = hospitalToken("doctor.demo");
        String doctorB = hospitalToken("doctor.demo");

        // 1. 管理员看在线用户：能看到本院两个账号的三个会话（含 IP 与登录时间）
        List<Map<String, Object>> online = listOnline(nurse);
        assertTrue(online.size() >= 3, " nurse 与 doctor 的多端会话都应出现在在线列表里，实际 " + online.size());
        Map<String, Object> doctorFirst =
                online.stream()
                        .filter(s -> "doctor.demo".equals(s.get("username")))
                        .findFirst()
                        .orElseThrow();
        assertNotNull(doctorFirst.get("ip"), "在线会话带登录 IP");
        assertNotNull(doctorFirst.get("issuedAt"), "在线会话带登录时间");
        assertFalse(online.toString().contains(doctorA), "在线列表只暴露会话号，不得出现令牌本身");

        // 2. 无 tenant:session:view 的账号被拒（40300）
        assertEquals(40300, codeOf(get("/api/hospital/system/sessions", doctorA)));

        // 3. 本人会话：两个会话、当前会话有标记，可下线另一台设备
        List<Map<String, Object>> mine = mySessions(doctorB);
        assertEquals(2, mine.size());
        assertEquals(1, mine.stream().filter(s -> Boolean.TRUE.equals(s.get("current"))).count());
        String otherSessionId =
                mine.stream()
                        .filter(s -> !Boolean.TRUE.equals(s.get("current")))
                        .map(s -> (String) s.get("sessionId"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(0, codeOf(delete("/api/hospital/auth/sessions/" + otherSessionId, doctorB)));
        assertEquals(40100, codeOf(get("/api/hospital/auth/me", doctorA)), "被下线的旧令牌立即失效");
        assertEquals(0, codeOf(get("/api/hospital/auth/me", doctorB)), "当前会话不受影响");

        // 4. 管理员强制下线：doctor 当前会话被踢，nurse 不受影响
        String doctorSessionId =
                listOnline(nurse).stream()
                        .filter(s -> "doctor.demo".equals(s.get("username")))
                        .map(s -> (String) s.get("sessionId"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                0,
                codeOf(post("/api/hospital/system/sessions/" + doctorSessionId + "/kick", nurse)));
        assertEquals(40100, codeOf(get("/api/hospital/auth/me", doctorB)), "被强制下线的令牌下次请求即 40100");
        assertEquals(0, codeOf(get("/api/hospital/auth/me", nurse)), "管理员会话不受影响");
        assertTrue(
                listOnline(nurse).stream().noneMatch(s -> "doctor.demo".equals(s.get("username"))),
                "下线后不再出现在在线列表");
    }

    @Test
    void permissionVersionBumpInvalidatesOldGrantsOnNextRequest() {
        String doctor = hospitalToken("doctor.demo");
        long doctorId = userId("doctor.demo");

        // 登录会话带初始版本；codes 里包含撤销目标
        assertEquals(1L, permissionVersionOf(doctor));
        List<String> codes = codesOf(doctor);
        assertTrue(codes.contains("followup:task:view"), "撤销前医生有随访任务权限");

        // 改权限：从 FOLLOWUP_DOCTOR 撤掉「随访任务」菜单并递增权限版本号（E06 角色管理将调用同一入口）
        runInTenant(
                () ->
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm"
                                        + " JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code = 'FOLLOWUP_DOCTOR' AND rm.menu_id = 22"));
        permissionVersions.bump("HOSPITAL", TENANT_ID, doctorId);

        // 旧令牌下一次请求即失效：不再带旧权限快照执行（重算为「角色菜单 ∩ 已开通模块」的新结果）
        List<String> refreshed = codesOf(doctor);
        assertFalse(refreshed.contains("followup:task:view"), "权限变更后旧令牌的下一次请求即失去被撤权限");
        assertTrue(refreshed.contains("followup:patient:view"), "未受影响的权限保留");
        assertEquals(2L, permissionVersionOf(doctor), "me 上报新权限版本号，前端据此重新拉取菜单与权限码");

        // 重新登录直接带新版本（无需重算）
        String relogin = hospitalToken("doctor.demo");
        assertEquals(2L, permissionVersionOf(relogin));
    }

    @Test
    void singleLoginKicksOlderSessionsWhenEnabled() {
        try {
            runInTenant(
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_param SET param_value = 'true'"
                                            + " WHERE param_key = 'security.session.single-login'"));
            String first = hospitalToken("doctor.demo");
            String second = hospitalToken("doctor.demo");

            assertEquals(40100, codeOf(get("/api/hospital/auth/me", first)), "单端登录：旧会话被新登录顶下线");
            assertEquals(0, codeOf(get("/api/hospital/auth/me", second)), "新会话可用");
        } finally {
            runInTenant(
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_param SET param_value = 'false'"
                                            + " WHERE param_key = 'security.session.single-login'"));
        }
    }

    @Test
    void platformOwnSessionsMirrorTheHospitalSide() {
        String platform = platformToken();

        List<Map<String, Object>> mine = mySessionsPlatform(platform);
        assertEquals(1, mine.size());
        assertEquals(Boolean.TRUE, mine.get(0).get("current"));

        // 平台会话没有租户上下文，不能调用医院端在线会话接口（端别隔离）
        assertEquals(40300, codeOf(get("/api/hospital/system/sessions", platform)));
    }

    // ---- 辅助 ----

    private String hospitalToken(String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        Map.of("tenantCode", TENANT, "username", username, "password", PASSWORD));
        assertEquals(0, codeOf(response));
        return token(response);
    }

    private String platformToken() {
        Map<?, ?> response =
                post(
                        "/api/platform/auth/login",
                        Map.of("username", "platform.admin", "password", PASSWORD));
        return token(response);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOnline(String token) {
        Map<?, ?> response = get("/api/hospital/system/sessions", token);
        assertEquals(0, codeOf(response));
        return (List<Map<String, Object>>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mySessions(String token) {
        Map<?, ?> response = get("/api/hospital/auth/sessions", token);
        assertEquals(0, codeOf(response));
        return (List<Map<String, Object>>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mySessionsPlatform(String token) {
        Map<?, ?> response = get("/api/platform/auth/sessions", token);
        assertEquals(0, codeOf(response));
        return (List<Map<String, Object>>) response.get("data");
    }

    @SuppressWarnings("unchecked")
    private List<String> codesOf(String token) {
        return (List<String>) get("/api/hospital/auth/codes", token).get("data");
    }

    private long permissionVersionOf(String token) {
        Map<?, ?> data = (Map<?, ?>) get("/api/hospital/auth/me", token).get("data");
        return ((Number) data.get("permissionVersion")).longValue();
    }

    private long userId(String username) {
        return runInTenant(
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    private Map<?, ?> post(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), Map.class).getBody();
    }

    private Map<?, ?> post(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.postForEntity(path, new HttpEntity<>(headers), Map.class).getBody();
    }

    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private Map<?, ?> delete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class)
                .getBody();
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static String token(Map<?, ?> response) {
        Map<?, ?> data = (Map<?, ?>) response.get("data");
        return (String) data.get("token");
    }

    private void deleteKeys(Set<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private void runInTenant(Runnable action) {
        TenantContext.runAsTenant(TENANT_ID, TENANT, action);
    }

    private <T> T runInTenant(java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, TENANT, action::get);
    }
}
