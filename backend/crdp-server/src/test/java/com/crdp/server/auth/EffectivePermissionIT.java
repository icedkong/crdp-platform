package com.crdp.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.security.ScopeSnapshot;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.system.user.HospitalAccount;
import com.crdp.system.user.HospitalDirectory;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 * E06-T06 端到端（tasks.csv 验收：多角色取并集，变更即生效）。
 *
 * <ol>
 *   <li>多角色并集（PLT-ROLE-006）：医生持有第二个角色后，同一令牌的下一次请求即拿到两角色的菜单、 按钮权限码与数据范围并集（/codes、/menus、会话范围快照）；
 *   <li>变更即生效（PLT-PERM-010）：停用角色、移除成员后旧令牌下次请求立即收缩；
 *   <li>平台停用 / 删除医院端菜单后，持有该菜单的租户用户权限版本递增、旧令牌下次请求立即失效；
 *   <li>权限版本号随每次授权变化递增（/me.permissionVersion），前端据此重新拉取菜单与权限码。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（ITR- 前缀角色与按钮）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EffectivePermissionIT {

    private static final String TENANT = "demoa";
    private static final long TENANT_ID = 1L;
    private static final String PASSWORD = "Demo@2026";

    /** 平台停用 / 删除场景用的按钮权限码（base 模块固定可分配，不碰真实接口）。 */
    private static final String ITR_BUTTON_PERM = "tenant:itr:effview";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HospitalDirectory hospitalDirectory;

    /** 只清理本 IT 建的数据（ITR- 前缀角色与菜单）与审计，不碰种子演示数据。 */
    @BeforeEach
    void cleanState() {
        runInTenant(
                () -> {
                    jdbc.update(
                            "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                    + " WHERE r.code LIKE 'ITR-%'");
                    jdbc.update(
                            "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                    + " WHERE r.code LIKE 'ITR-%'");
                    jdbc.update(
                            "DELETE ur FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id"
                                    + " WHERE r.code LIKE 'ITR-%'");
                    jdbc.update("DELETE FROM sys_role WHERE code LIKE 'ITR-%'");
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_log WHERE event_type LIKE 'ROLE_%'");
                });
        TenantContext.runAsPlatform(
                () -> {
                    jdbc.update(
                            "DELETE rm FROM plt_role_menu rm JOIN plt_menu m ON m.id = rm.menu_id"
                                    + " WHERE m.title LIKE 'ITR-%'");
                    jdbc.update("DELETE FROM plt_menu WHERE title LIKE 'ITR-%'");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM plt_audit_log WHERE event_type LIKE 'MENU_%'"
                                    + " AND detail LIKE '%ITR%'");
                });
    }

    /**
     * 收尾清理：最后一个用例结束后不再有 @BeforeEach 兜底，ITR- 角色对 doctor.demo 的成员 关系会留给同套件里后续运行的 IT（如 E06-T09
     * 权限预览对种子角色的精确断言）。
     */
    @org.junit.jupiter.api.AfterAll
    static void cleanUp(@Autowired JdbcTemplate jdbc) {
        TenantContext.runAsTenant(
                TENANT_ID,
                TENANT,
                () -> {
                    jdbc.update(
                            "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                    + " WHERE r.code LIKE 'ITR-%'");
                    jdbc.update(
                            "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                    + " WHERE r.code LIKE 'ITR-%'");
                    jdbc.update(
                            "DELETE ur FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id"
                                    + " WHERE r.code LIKE 'ITR-%'");
                    jdbc.update("DELETE FROM sys_role WHERE code LIKE 'ITR-%'");
                    AuditTableMaintenance.delete(
                            jdbc, "DELETE FROM aud_log WHERE event_type LIKE 'ROLE_%'");
                });
        TenantContext.runAsPlatform(
                () -> {
                    jdbc.update(
                            "DELETE rm FROM plt_role_menu rm JOIN plt_menu m ON m.id = rm.menu_id"
                                    + " WHERE m.title LIKE 'ITR-%'");
                    jdbc.update("DELETE FROM plt_menu WHERE title LIKE 'ITR-%'");
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM plt_audit_log WHERE event_type LIKE 'MENU_%'"
                                    + " AND detail LIKE '%ITR%'");
                });
    }

    @Test
    @Order(1)
    void multiRoleUnionTakesEffectOnNextRequest() {
        // 医生先登录（变更前），全程用同一令牌验证「下一次请求即生效」
        String doctor = hospitalToken("doctor.demo");
        List<String> before = codesOf(doctor);
        assertTrue(before.contains("followup:patient:view"), "变更前医生有患者档案权限");
        assertTrue(before.contains("followup:task:view"), "变更前医生有随访任务权限");
        assertFalse(before.contains("followup:report:view"), "变更前没有报告单权限");
        assertFalse(before.contains("tenant:user:view"), "变更前没有用户管理权限");
        long version0 = permissionVersionOf(doctor);
        assertFalse(scopeOfDoctor().all(), "变更前范围是病区 + 本人负责的患者，不是全部");

        // 管理员建第二个角色：菜单 23（报告单）/31（用户管理），数据范围 ALL，成员 = 医生
        String admin = adminToken();
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/system/roles",
                                admin,
                                Map.of("code", "ITR-EFF1", "name", "IT 并集角色（示例）"))));
        long roleId = roleId("ITR-EFF1");
        Map<?, ?> menusResponse =
                put(
                        "/api/hospital/system/roles/" + roleId + "/menus",
                        admin,
                        Map.of("menuIds", List.of(23L, 30L, 31L)));
        assertEquals(0, code(menusResponse), () -> "菜单授权失败：" + menusResponse);
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/scope",
                                admin,
                                Map.of("scopeTypes", List.of("ALL"), "customDeptIds", List.of()))));
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/members",
                                admin,
                                Map.of("userIds", List.of(userId("doctor.demo"))))));

        // 同一令牌的下一次请求：菜单、按钮取并集（PLT-ROLE-006）
        List<String> union = codesOf(doctor);
        assertTrue(union.contains("followup:patient:view"), "原角色权限保留");
        assertTrue(union.contains("followup:task:view"), "原角色权限保留");
        assertTrue(union.contains("followup:report:view"), "新角色的权限码并入");
        assertTrue(union.contains("tenant:user:view"), "新角色的基础模块权限码并入");
        long version1 = permissionVersionOf(doctor);
        assertTrue(version1 > version0, "授权变化递增权限版本号（前端据此重拉菜单与权限码）");
        assertTrue(scopeOfDoctor().all(), "数据范围并集：任一角色为 ALL 即不过滤");
        String menus = menusOf(doctor).toString();
        assertTrue(menus.contains("报告单"), "菜单树下发并集：报告单出现");
        assertTrue(menus.contains("用户管理"), "菜单树下发并集：用户管理出现（父目录一并授权）");

        // 停用角色 → 旧令牌下一次请求立即收缩（platform-spec §1「停用角色」）
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/system/roles/" + roleId + "/status",
                                admin,
                                Map.of("action", "DISABLE", "reason", "IT 验证停用即生效（示例）"))));
        List<String> disabled = codesOf(doctor);
        assertFalse(disabled.contains("followup:report:view"), "停用后立即失去该角色权限");
        assertFalse(disabled.contains("tenant:user:view"), "停用后立即失去该角色权限");
        assertTrue(disabled.contains("followup:patient:view"), "未受影响的权限保留");
        assertTrue(permissionVersionOf(doctor) > version1, "停用再次递增权限版本号");
        assertFalse(scopeOfDoctor().all(), "范围回到病区 + 本人负责的患者");

        // 重新启用恢复；移除成员再次收缩（两条路径都验证）
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/system/roles/" + roleId + "/status",
                                admin,
                                Map.of("action", "ENABLE"))));
        assertTrue(codesOf(doctor).contains("followup:report:view"), "重新启用自动恢复");
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/members",
                                admin,
                                Map.of("userIds", List.of()))));
        List<String> removed = codesOf(doctor);
        assertFalse(removed.contains("followup:report:view"), "移除成员后立即失去该角色权限");
        assertTrue(removed.contains("followup:patient:view"), "原角色权限不受影响");
    }

    @Test
    @Order(2)
    void platformMenuChangesTakeEffectOnTenantUsersNextRequest() {
        // 平台在用户管理（31，base 模块固定可分配）下建测试按钮
        String platform = platformToken();
        Map<?, ?> created =
                post(
                        "/api/platform/menus",
                        platform,
                        Map.of(
                                "side", "HOSPITAL",
                                "parentId", "31",
                                "type", "BUTTON",
                                "moduleCode", "base",
                                "title", "ITR-有效权限按钮（示例）",
                                "permCode", ITR_BUTTON_PERM));
        assertEquals(0, code(created), () -> "按钮创建失败：" + created);
        long buttonId = ((Number) data(created).get("id")).longValue();

        // 租户角色授权该按钮并分配给医生，医生随后登录
        String admin = adminToken();
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/system/roles",
                                admin,
                                Map.of("code", "ITR-EFF2", "name", "IT 菜单停用角色（示例）"))));
        long roleId = roleId("ITR-EFF2");
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/menus",
                                admin,
                                Map.of("menuIds", List.of(buttonId)))));
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/system/roles/" + roleId + "/members",
                                admin,
                                Map.of("userIds", List.of(userId("doctor.demo"))))));
        String doctor = hospitalToken("doctor.demo");
        assertTrue(codesOf(doctor).contains(ITR_BUTTON_PERM), "授权后医生拿到按钮权限码");
        long version0 = permissionVersionOf(doctor);

        // 平台停用医院端按钮 → 持有该按钮的租户用户权限版本递增，旧令牌下次请求失效
        Map<?, ?> disabled =
                put(
                        "/api/platform/menus/" + buttonId,
                        platform,
                        Map.of("title", "ITR-有效权限按钮（示例）", "status", "DISABLED"));
        assertEquals(0, code(disabled), () -> "按钮停用失败：" + disabled);
        assertFalse(codesOf(doctor).contains(ITR_BUTTON_PERM), "平台停用医院端菜单后，旧令牌下一次请求即失去对应权限码");
        assertTrue(permissionVersionOf(doctor) > version0, "停用菜单递增受影响用户权限版本号");

        // 重新启用恢复；软删除（同步清理租户授权）再次失效
        assertEquals(
                0,
                code(
                        put(
                                "/api/platform/menus/" + buttonId,
                                platform,
                                Map.of("title", "ITR-有效权限按钮（示例）", "status", "ENABLED"))));
        assertTrue(codesOf(doctor).contains(ITR_BUTTON_PERM), "重新启用自动恢复");
        assertEquals(0, code(delete("/api/platform/menus/" + buttonId, platform)));
        assertFalse(codesOf(doctor).contains(ITR_BUTTON_PERM), "软删除菜单后，旧令牌下一次请求即失去对应权限码");
        Integer grantsLeft =
                runInTenant(
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM sys_role_menu WHERE menu_id = ?",
                                        Integer.class,
                                        buttonId));
        assertEquals(0, grantsLeft, "删除时逐租户清理角色授权");
    }

    // ---------------------------------------------------------------- 基础设施

    private String adminToken() {
        return hospitalToken("nurse.demo");
    }

    private String hospitalToken(String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", TENANT, "username", username, "password", PASSWORD));
        assertEquals(0, code(response));
        return (String) data(response).get("token");
    }

    private String platformToken() {
        Map<?, ?> response =
                post(
                        "/api/platform/auth/login",
                        null,
                        Map.of("username", "platform.admin", "password", PASSWORD));
        assertEquals(0, code(response));
        return (String) data(response).get("token");
    }

    /** 会话重算读取的数据范围快照（与 SessionPermissionRefresher 同一条取数路径）。 */
    private ScopeSnapshot scopeOfDoctor() {
        long doctorId = userId("doctor.demo");
        return runInTenant(
                () ->
                        hospitalDirectory
                                .findById(doctorId)
                                .map(HospitalAccount::scope)
                                .orElse(null));
    }

    @SuppressWarnings("unchecked")
    private List<String> codesOf(String token) {
        return (List<String>) get("/api/hospital/auth/codes", token).get("data");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> menusOf(String token) {
        return (List<Map<String, Object>>) get("/api/hospital/menus", token).get("data");
    }

    private long permissionVersionOf(String token) {
        return ((Number) data(get("/api/hospital/auth/me", token)).get("permissionVersion"))
                .longValue();
    }

    private long roleId(String code) {
        return runInTenant(
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_role WHERE code = ?", Long.class, code));
    }

    private long userId(String username) {
        return runInTenant(
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    private void runInTenant(Runnable action) {
        TenantContext.runAsTenant(TENANT_ID, TENANT, action);
    }

    private <T> T runInTenant(Supplier<T> action) {
        return TenantContext.callAsTenant(TENANT_ID, TENANT, action::get);
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

    private Map<?, ?> delete(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return rest.exchange(path, HttpMethod.DELETE, new HttpEntity<>(headers), Map.class)
                .getBody();
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        Object data = response.get("data");
        return data == null ? Map.of() : (Map<?, ?>) data;
    }
}
