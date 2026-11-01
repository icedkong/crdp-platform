package com.crdp.server.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.framework.tenant.TenantContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * E06-T09 端到端（完整应用 + 真实 MySQL/Redis；验收原文：预览显示菜单、权限码与数据范围）。场景：
 *
 * <ol>
 *   <li>权限预览（PLT-ORG-009、A-WARD-009、C-WARD-008）：doctor.demo（随访医生，WARD + 仅本人负责的患者）
 *       的预览返回有效菜单树（含按钮与来源角色）、权限码、数据范围类型与<b>可见病区及其来源角色</b>； nurse.demo（租户管理员）scopeAll=true、不列病区；
 *   <li>多角色合并与来源角色（PLT-ROLE-006）：加一个授予「报告单」与 WARD 范围的自定义角色后， 预览菜单 /
 *       范围立即按并集取值且逐项标注来源角色；停用该角色后立即收缩（预览与登录草稿同口径）；
 *   <li>权限与隔离：doctor.demo 无 tenant:user:view 40300、未知用户 40400、demob 看不到 demoa 的用户；
 *   <li>用户选择器（PLT-ORG-012）：/system/users/options 登录即可（随访医生可调），只含本院启用用户， keyword 命中账号 / 姓名 /
 *       工号，deptId 命中主科室或负责科室；原 /orgs/user-options（暂行）已移除。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（账号前缀 IT-P-、角色前缀 IT-R-P）。 全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
class UserPermissionPreviewIT {

    private static final String PASSWORD = "Demo@2026";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username LIKE 'IT-P-%'");
                        jdbc.update(
                                "DELETE ds FROM sys_user_dept_scope ds JOIN sys_user u ON u.id = ds.user_id"
                                        + " WHERE u.username LIKE 'IT-P-%'");
                        jdbc.update(
                                "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                                        + " WHERE u.username LIKE 'IT-P-%'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u ON u.id = ph.user_id"
                                        + " WHERE u.username LIKE 'IT-P-%'");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-P-%'");
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code LIKE 'IT-R-P%'");
                        jdbc.update(
                                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                        + " WHERE r.code LIKE 'IT-R-P%'");
                        jdbc.update("DELETE FROM sys_role WHERE code LIKE 'IT-R-P%'");
                        jdbc.update("DELETE FROM aud_log WHERE target LIKE 'sys_user:IT-P-%'");
                    });
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void cleanUp(@Autowired JdbcTemplate jdbc) {
        for (String tenant : new String[] {"demoa", "demob"}) {
            TenantContext.runAsTenant(
                    "demob".equals(tenant) ? 2L : 1L,
                    tenant,
                    () -> {
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username LIKE 'IT-P-%'");
                        jdbc.update(
                                "DELETE ds FROM sys_user_dept_scope ds JOIN sys_user u ON u.id = ds.user_id"
                                        + " WHERE u.username LIKE 'IT-P-%'");
                        jdbc.update(
                                "DELETE uw FROM sys_user_ward uw JOIN sys_user u ON u.id = uw.user_id"
                                        + " WHERE u.username LIKE 'IT-P-%'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u ON u.id = ph.user_id"
                                        + " WHERE u.username LIKE 'IT-P-%'");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-P-%'");
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code LIKE 'IT-R-P%'");
                        jdbc.update(
                                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                        + " WHERE r.code LIKE 'IT-R-P%'");
                        jdbc.update("DELETE FROM sys_role WHERE code LIKE 'IT-R-P%'");
                        jdbc.update("DELETE FROM aud_log WHERE target LIKE 'sys_user:IT-P-%'");
                    });
        }
    }

    // ---------------------------------------------------------------- 权限预览（验收：菜单、权限码与数据范围）

    @Test
    void previewShowsMenusCodesAndWardScopeForFollowupDoctor() {
        String admin = adminToken();
        long doctorId = userIdOf("demoa", "doctor.demo");

        Map<?, ?> view =
                data(
                        get(
                                "/api/hospital/system/users/" + doctorId + "/effective-permissions",
                                admin));
        assertEquals("doctor.demo", view.get("username"));
        assertEquals("ENABLED", view.get("status"));

        // 角色：随访医生（自定义、启用）
        List<?> roles = (List<?>) view.get("roles");
        assertEquals(1, roles.size(), "doctor.demo 种子只挂一个角色：" + roles);
        Map<?, ?> role = (Map<?, ?>) roles.get(0);
        assertEquals("FOLLOWUP_DOCTOR", role.get("code"));
        assertEquals("ENABLED", role.get("status"));

        // 菜单树：随访目录下有患者档案 / 随访任务（种子 V100 授权 20、21、22），逐项带来源角色
        List<String> menuTitles = new java.util.ArrayList<>();
        collectTitles((List<?>) view.get("menus"), menuTitles);
        assertTrue(menuTitles.contains("随访"), "预览应包含「随访」目录：" + menuTitles);
        assertTrue(menuTitles.contains("患者档案"), "预览应包含「患者档案」菜单：" + menuTitles);
        assertTrue(menuTitles.contains("随访任务"), "预览应包含「随访任务」菜单：" + menuTitles);
        assertFalse(menuTitles.contains("用户管理"), "随访医生不应看到「用户管理」：" + menuTitles);
        Map<?, ?> patientMenu = findNodeByTitle((List<?>) view.get("menus"), "患者档案");
        assertNotNull(patientMenu, "患者档案节点存在");
        assertEquals("followup:patient:view", patientMenu.get("permCode"));
        assertEquals(List.of("随访医生"), (List<?>) patientMenu.get("sourceRoles"), "来源角色为随访医生");

        // 权限码：与菜单树同源（种子随访医生无按钮授权）
        List<?> codes = (List<?>) view.get("permissionCodes");
        assertTrue(codes.contains("followup:patient:view"), "权限码含 followup:patient:view：" + codes);
        assertTrue(codes.contains("followup:task:view"), "权限码含 followup:task:view：" + codes);
        assertFalse(codes.contains("tenant:user:view"), "不应含 tenant:user:view：" + codes);

        // 数据范围：WARD + RESPONSIBLE_PATIENT，各带来源角色；不含 ALL
        assertFalse((Boolean) view.get("scopeAll"), "随访医生不是全院范围");
        Map<String, List<String>> scopeSources = scopeTypeSources(view);
        assertEquals(List.of("随访医生"), scopeSources.get("WARD"), "WARD 范围来源角色：" + scopeSources);
        assertEquals(
                List.of("随访医生"),
                scopeSources.get("RESPONSIBLE_PATIENT"),
                "RESPONSIBLE_PATIENT 来源角色：" + scopeSources);

        // 可见病区（A-WARD-009）：本人负责病区 W-DEMO-1，附来源角色
        List<?> wards = (List<?>) view.get("wards");
        assertEquals(1, wards.size(), "doctor.demo 负责一个病区：" + wards);
        Map<?, ?> ward = (Map<?, ?>) wards.get(0);
        assertEquals("W-DEMO-1", ward.get("code"));
        assertEquals("虚构病区一", ward.get("name"));
        assertEquals(List.of("随访医生"), (List<?>) ward.get("sourceRoles"));
    }

    @Test
    void tenantAdminPreviewIsScopeAllWithoutWards() {
        String admin = adminToken();
        long adminUserId = userIdOf("demoa", "nurse.demo");

        Map<?, ?> view =
                data(
                        get(
                                "/api/hospital/system/users/"
                                        + adminUserId
                                        + "/effective-permissions",
                                admin));
        assertTrue((Boolean) view.get("scopeAll"), "租户管理员全院可见（A-WARD-007）");
        assertEquals(0, ((List<?>) view.get("wards")).size(), "全院范围不列病区");
        List<String> menuTitles = new java.util.ArrayList<>();
        collectTitles((List<?>) view.get("menus"), menuTitles);
        assertTrue(menuTitles.contains("用户管理"), "租户管理员能看到用户管理：" + menuTitles);
        List<?> codes = (List<?>) view.get("permissionCodes");
        assertTrue(codes.contains("tenant:user:view"), "权限码含 tenant:user:view：" + codes);
        Map<String, List<String>> scopeSources = scopeTypeSources(view);
        assertEquals(List.of("租户管理员"), scopeSources.get("ALL"), "ALL 来源角色");
    }

    @Test
    void multiRoleUnionMergesMenusAndScopeWithSourceRoles() {
        String admin = adminToken();
        long doctorId = userIdOf("demoa", "doctor.demo");
        long roleId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_role (code, name, built_in, status, role_type)"
                                            + " VALUES ('IT-R-P-REPORT', 'IT 示例报告角色', 0, 'ENABLED', 'CUSTOM')");
                            long id =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_role WHERE code = 'IT-R-P-REPORT'",
                                            Long.class);
                            jdbc.update(
                                    "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 23)",
                                    id);
                            jdbc.update(
                                    "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 20)",
                                    id);
                            jdbc.update(
                                    "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'WARD')",
                                    id);
                            jdbc.update(
                                    "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                                    doctorId,
                                    id);
                            return id;
                        });

        Map<?, ?> view =
                data(
                        get(
                                "/api/hospital/system/users/" + doctorId + "/effective-permissions",
                                admin));

        // 并集：报告单出现，且「患者档案」来源只有随访医生、「报告单」来源只有新角色
        Map<?, ?> reportMenu = findNodeByTitle((List<?>) view.get("menus"), "报告单");
        assertNotNull(reportMenu, "多角色并集应包含报告单");
        assertEquals("followup:report:view", reportMenu.get("permCode"));
        assertEquals(List.of("IT 示例报告角色"), (List<?>) reportMenu.get("sourceRoles"));
        Map<?, ?> patientMenu = findNodeByTitle((List<?>) view.get("menus"), "患者档案");
        assertEquals(List.of("随访医生"), (List<?>) patientMenu.get("sourceRoles"));

        // 范围并集：WARD 的来源角色变成两个
        Map<String, List<String>> scopeSources = scopeTypeSources(view);
        assertEquals(List.of("随访医生", "IT 示例报告角色"), scopeSources.get("WARD"), "WARD 并集来源角色");
        List<?> wards = (List<?>) view.get("wards");
        assertEquals(
                List.of("随访医生", "IT 示例报告角色"),
                (List<?>) ((Map<?, ?>) wards.get(0)).get("sourceRoles"),
                "病区来源角色随并集");

        // 停用角色立即收缩（预览与登录草稿同一入口，变更即生效）
        runInTenant(
                "demoa",
                () -> jdbc.update("UPDATE sys_role SET status = 'DISABLED' WHERE id = ?", roleId));
        Map<?, ?> shrunk =
                data(
                        get(
                                "/api/hospital/system/users/" + doctorId + "/effective-permissions",
                                admin));
        List<String> titles = new java.util.ArrayList<>();
        collectTitles((List<?>) shrunk.get("menus"), titles);
        assertFalse(titles.contains("报告单"), "停用角色的授权立即失效：" + titles);
        assertEquals(List.of("随访医生"), scopeTypeSources(shrunk).get("WARD"), "范围来源同步收缩");
        List<String> roleStatuses =
                ((List<?>) shrunk.get("roles"))
                        .stream()
                                .map(item -> (Map<?, ?>) item)
                                .map(item -> String.valueOf(item.get("status")))
                                .toList();
        assertTrue(roleStatuses.contains("DISABLED"), "预览保留停用角色行便于排查：" + roleStatuses);
    }

    @Test
    void responsibleDeptScopeListsDeptsForCustomDeptTypes() {
        String admin = adminToken();
        long deptId = deptIdByCode("demoa", "DEPT-CARDIO");
        // 新建独立用户（不动种子账号的负责范围，避免影响其他用例的快照断言）
        long userId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                            + " must_change_password) VALUES ('IT-P-DEPT', 'IT 示例科室用户',"
                                            + " 'x', 'ENABLED', 0)");
                            long id =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_user WHERE username = 'IT-P-DEPT'",
                                            Long.class);
                            jdbc.update(
                                    "INSERT INTO sys_role (code, name, built_in, status, role_type)"
                                            + " VALUES ('IT-R-P-DEPT', 'IT 示例科室角色', 0, 'ENABLED', 'CUSTOM')");
                            long roleId =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_role WHERE code = 'IT-R-P-DEPT'",
                                            Long.class);
                            jdbc.update(
                                    "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'RESPONSIBLE_DEPT')",
                                    roleId);
                            jdbc.update(
                                    "INSERT INTO sys_user_role (user_id, role_id) VALUES (?, ?)",
                                    id,
                                    roleId);
                            jdbc.update(
                                    "INSERT INTO sys_user_dept_scope (user_id, dept_id) VALUES (?, ?)",
                                    id,
                                    deptId);
                            return id;
                        });

        Map<?, ?> view =
                data(get("/api/hospital/system/users/" + userId + "/effective-permissions", admin));
        Map<String, List<String>> scopeSources = scopeTypeSources(view);
        assertEquals(List.of("IT 示例科室角色"), scopeSources.get("RESPONSIBLE_DEPT"), "负责科室范围来源角色");
        List<?> depts = (List<?>) view.get("depts");
        assertEquals(1, depts.size(), "负责科室清单：" + depts);
        Map<?, ?> dept = (Map<?, ?>) depts.get(0);
        assertEquals("DEPT-CARDIO", dept.get("code"));
        assertEquals("虚构心内科", dept.get("name"));
        assertEquals(List.of("IT 示例科室角色"), (List<?>) dept.get("sourceRoles"));
    }

    @Test
    void previewRejectsMissingPermissionAndUnknownUser() {
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long doctorId = userIdOf("demoa", "doctor.demo");
        assertEquals(
                40300,
                codeOf(
                        get(
                                "/api/hospital/system/users/" + doctorId + "/effective-permissions",
                                doctor)),
                "随访医生无 tenant:user:view");

        String admin = adminToken();
        assertEquals(
                40400,
                codeOf(get("/api/hospital/system/users/999999/effective-permissions", admin)),
                "未知用户不暴露存在性");
    }

    @Test
    void previewIsTenantIsolated() {
        String demobAdmin = adminTokenOf("demob");
        // demoa 新建的用户在 demob 库不存在（两个租户的种子账号 id 相同，需用新用户区分）
        long demoaUserId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                            + " must_change_password) VALUES ('IT-P-ISO', 'IT 示例隔离', 'x',"
                                            + " 'ENABLED', 0)");
                            return jdbc.queryForObject(
                                    "SELECT id FROM sys_user WHERE username = 'IT-P-ISO'",
                                    Long.class);
                        });
        assertEquals(
                40400,
                codeOf(
                        get(
                                "/api/hospital/system/users/"
                                        + demoaUserId
                                        + "/effective-permissions",
                                demobAdmin)),
                "demob 管理员不能预览 demoa 用户");
    }

    // ---------------------------------------------------------------- 用户选择器（PLT-ORG-012）

    @Test
    void userOptionsIsLoginOnlyAndScopedToTenant() {
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        // 登录即可：随访医生没有 tenant:org:view / tenant:user:view 也能调（业务模块选责任人）
        Map<?, ?> page = get("/api/hospital/system/users/options?keyword=演示", doctor);
        assertEquals(0, codeOf(page), "登录即可调用：" + page);
        List<? extends Map<?, ?>> options = dataList(page);
        assertFalse(options.isEmpty(), "关键字「演示」能命中种子演示账号");
        Map<?, ?> option = (Map<?, ?>) options.get(0);
        assertNotNull(option.get("id"));
        assertNotNull(option.get("displayName"), "只返回 id、显示名、账号与科室");

        // keyword 命中工号；主科室落在心内科（deptId 过滤用）
        long cardioDept = deptIdByCode("demoa", "DEPT-CARDIO");
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                    + " must_change_password, staff_no, main_dept_id)"
                                    + " VALUES ('IT-P-SEL1', 'IT 示例选择器', 'x', 'ENABLED', 0, 'IT-STAFF-9',"
                                    + " ?)",
                            cardioDept);
                });
        List<? extends Map<?, ?>> byStaffNo =
                dataList(get("/api/hospital/system/users/options?keyword=IT-STAFF-9", doctor));
        assertEquals(1, byStaffNo.size(), "工号命中：" + byStaffNo);
        assertEquals("IT 示例选择器", byStaffNo.get(0).get("displayName"));
        assertEquals("虚构心内科", byStaffNo.get(0).get("deptName"));

        // 停用用户不出现在选择器
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT INTO sys_user (username, display_name, password_hash, status,"
                                        + " must_change_password, staff_no)"
                                        + " VALUES ('IT-P-SEL2', 'IT 示例停用', 'x', 'DISABLED', 0, 'IT-STAFF-8')"));
        List<? extends Map<?, ?>> byDisabled =
                dataList(get("/api/hospital/system/users/options?keyword=IT-STAFF-8", doctor));
        assertEquals(0, byDisabled.size(), "停用用户不进选择器");

        // deptId 命中主科室
        List<? extends Map<?, ?>> byDept =
                dataList(get("/api/hospital/system/users/options?deptId=" + cardioDept, doctor));
        assertTrue(
                byDept.stream()
                        .anyMatch(item -> "IT-P-SEL1".equals(((Map<?, ?>) item).get("username"))),
                "主科室命中 IT-P-SEL1：" + byDept);

        // 租户隔离：demoa 的 IT-P 用户不会出现在 demob 的选择器里
        String demobDoctor = hospitalToken("demob", "doctor.demo", PASSWORD);
        List<? extends Map<?, ?>> demobOptions =
                dataList(get("/api/hospital/system/users/options?keyword=IT-P-", demobDoctor));
        assertEquals(0, demobOptions.size(), "跨租户用户不进选择器：" + demobOptions);

        // 未登录 40100
        assertEquals(40100, codeOf(get("/api/hospital/system/users/options", null)), "选择器仍要求登录");
    }

    @Test
    void legacyOrgUserOptionsEndpointIsRemoved() {
        String admin = adminToken();
        // E06-T09 起由 /system/users/options（登录即可）替换暂行接口；未登记接口默认拒绝 40307
        assertEquals(
                40307,
                codeOf(get("/api/hospital/system/orgs/user-options", admin)),
                "暂行负责人候选接口已移除（PLT-ORG-012 共用选择器）");
    }

    // ---------------------------------------------------------------- 辅助

    private static void collectTitles(List<?> nodes, List<String> into) {
        for (Object node : nodes) {
            Map<?, ?> item = (Map<?, ?>) node;
            into.add(String.valueOf(item.get("title")));
            collectTitles((List<?>) item.get("children"), into);
        }
    }

    private static Map<?, ?> findNodeByTitle(List<?> nodes, String title) {
        for (Object node : nodes) {
            Map<?, ?> item = (Map<?, ?>) node;
            if (title.equals(item.get("title"))) {
                return item;
            }
            Map<?, ?> hit = findNodeByTitle((List<?>) item.get("children"), title);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    private static Map<String, List<String>> scopeTypeSources(Map<?, ?> view) {
        Map<String, List<String>> sources = new java.util.LinkedHashMap<>();
        for (Object item : (List<?>) view.get("scopeTypes")) {
            Map<?, ?> type = (Map<?, ?>) item;
            sources.put(
                    String.valueOf(type.get("type")),
                    ((List<?>) type.get("sourceRoles")).stream().map(String::valueOf).toList());
        }
        return sources;
    }

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo", PASSWORD);
    }

    private String adminTokenOf(String tenant) {
        return hospitalToken(tenant, "nurse.demo", PASSWORD);
    }

    private String hospitalToken(String tenant, String username, String password) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", password));
        assertEquals(0, codeOf(response), "登录失败：" + response);
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

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> dataList(Map<?, ?> response) {
        return (List<Map<?, ?>>) response.get("data");
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

    private long userIdOf(String tenant, String username) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    private long deptIdByCode(String tenant, String code) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_dept WHERE code = ?", Long.class, code));
    }
}
