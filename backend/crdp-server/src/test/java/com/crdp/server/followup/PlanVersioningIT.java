package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
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
 * E14-T01 端到端（tasks.csv 验收：发布后版本只读，新入组用新版本）。场景：
 *
 * <ol>
 *   <li>版本锁定（A-FU-019、C-FU-001）：新建（未发布）→ 配节点 → 发布 v1；已发布 v1 的基本信息与节点都 只读（40901）；复制出新版本 v2（草稿可改）→ 发布
 *       v2 时 v1 自动停用（不再允许入组）；患者沿用入组时的版本（fu_enrollment.plan_id 不变）， 新入组只能用新版本；
 *   <li>计划状态（A-FU-018）：发布 → 暂停（入组 40901）→ 恢复 → 停用（终态，恢复 40901）；「随访中」为派生展示态（PUBLISHED 且有在组）；
 *   <li>校验：起算字段枚举 40001、编码重复 40902、无节点发布 40001、乐观锁冲突 40900、同族草稿唯一 40901；入组：未发布 / 暂停 / 停用 40901、死亡患者
 *       40901、同族已在组 40901；
 *   <li>权限与模块（docs/api/followup.md §1 双码任一命中）：医生（followup:plan:view）可看不可改（40300）、无计划菜单
 *       40300、followup 模块关闭 40301；双租户计划配置互不可见。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 PLAN-IT-E14 前缀、病案号 IT-E14T01- 前缀、角色
 * IT-R-E14-NO）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanVersioningIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E14T01";
    private static final String PLAN_CODE = "PLAN-IT-E14";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;
    @Autowired private PlanEnrollmentService enrollmentService;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE event_type LIKE 'PLAN_%'"
                                        + " AND (detail LIKE '%"
                                        + PLAN_CODE
                                        + "%'"
                                        + " OR target IN (SELECT CONCAT('PLAN:', id) FROM fu_plan"
                                        + " WHERE code LIKE 'PLAN-IT-E14%'))");
                        jdbc.update(
                                "DELETE e FROM fu_enrollment e JOIN pt_patient p ON p.id = e.patient_id"
                                        + " WHERE p.legacy_no LIKE 'IT-E14T01-%'");
                        jdbc.update(
                                "DELETE n FROM fu_plan_node n JOIN fu_plan p ON p.id = n.plan_id"
                                        + " WHERE p.code LIKE 'PLAN-IT-E14%'");
                        jdbc.update("DELETE FROM fu_plan WHERE code LIKE 'PLAN-IT-E14%'");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE 'IT-E14T01-%'");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u ON u.id = ur.user_id"
                                        + " WHERE u.username = 'IT-U-E14-NO'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u"
                                        + " ON u.id = ph.user_id WHERE u.username = 'IT-U-E14-NO'");
                        jdbc.update("DELETE FROM sys_user WHERE username = 'IT-U-E14-NO'");
                        jdbc.update(
                                "DELETE rm FROM sys_role_menu rm JOIN sys_role r ON r.id = rm.role_id"
                                        + " WHERE r.code = 'IT-R-E14-NO'");
                        jdbc.update(
                                "DELETE rs FROM sys_role_scope rs JOIN sys_role r ON r.id = rs.role_id"
                                        + " WHERE r.code = 'IT-R-E14-NO'");
                        jdbc.update("DELETE FROM sys_role WHERE code = 'IT-R-E14-NO'");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：发布后版本只读，新入组用新版本

    @Test
    void publishedVersionReadOnlyAndNewEnrollmentUsesNewVersion() {
        String admin = adminToken();
        long v1 = createPublishedPlan(admin, "PLAN-IT-E14-A", 0);

        // 发布后只读：基本信息与节点整体保存都 40901（A-FU-019）
        assertEquals(
                40901,
                codeOf(
                        put(
                                "/api/hospital/followup/plans/" + v1,
                                admin,
                                planUpdateBody(0, "改名（示例）"))),
                "已发布版本基本信息只读");
        assertEquals(
                40901,
                codeOf(put("/api/hospital/followup/plans/" + v1 + "/nodes", admin, nodesBody(0))),
                "已发布版本节点只读");

        // 患者入组固定在 v1（A-FU-019「患者沿用入组时的版本」）
        long patientA = registerPatient(admin, "IT-E14T01-A");
        Long enrollmentA = enrollInTenant(patientA, v1);
        assertNotNull(enrollmentA, "v1 已发布，入组成功");
        assertEquals(v1, planIdOfEnrollment(enrollmentA), "入组行固定在 v1");

        // 列表状态派生「随访中」（PUBLISHED 且有在组，A-FU-018）
        assertEquals("RUNNING", statusOf(admin, v1), "有在组入组 → 随访中");

        // 复制出新版本 v2（草稿可改），发布后 v1 自动停用
        long v2 = idOf(post("/api/hospital/followup/plans/" + v1 + "/copy", admin, Map.of()));
        assertEquals("UNPUBLISHED", statusOf(admin, v2));
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/followup/plans/" + v2,
                                admin,
                                planUpdateBody(0, "示例 IT 年度随访计划 v2"))),
                "草稿可改（新版本未发布）");
        assertEquals(
                0,
                codeOf(post("/api/hospital/followup/plans/" + v2 + "/publish", admin, Map.of())));
        assertEquals("PUBLISHED", statusOf(admin, v2));
        assertEquals("DISABLED", statusOf(admin, v1), "发布新版本后旧版本自动停用（不再允许入组）");
        assertEquals(v1, planIdOfEnrollment(enrollmentA), "在组患者仍固定在 v1（不迁移，A-FU-020 延后）");

        // 新入组用新版本：入组 v1 → 40901；入组 v2 → 固定在 v2
        long patientB = registerPatient(admin, "IT-E14T01-B");
        try {
            enrollInTenant(patientB, v1);
            throw new AssertionError("已停用版本不允许入组");
        } catch (com.crdp.framework.web.ApiException expected) {
            assertEquals(40901, expected.code(), "已停用（不再允许入组）→ 引导新版本");
        }
        Long enrollmentB = enrollInTenant(patientB, v2);
        assertEquals(v2, planIdOfEnrollment(enrollmentB), "新入组固定在新版本 v2");

        // 同族已在组：患者 A 再入 v2 → 40901
        try {
            enrollInTenant(patientA, v2);
            throw new AssertionError("同族重复入组应被拒");
        } catch (com.crdp.framework.web.ApiException expected) {
            assertEquals(40901, expected.code(), "同一计划族至多一条在组入组");
        }
    }

    // ---------------------------------------------------------------- 状态机与校验（A-FU-018 / A-FU-019）

    @Test
    void statusTransitionsAndValidations() {
        String admin = adminToken();
        long planId = createPublishedPlan(admin, "PLAN-IT-E14-B", 0);

        // 暂停 → 入组 40901（不开启新任务、不发通知）
        assertEquals(
                0,
                codeOf(post("/api/hospital/followup/plans/" + planId + "/pause", admin, Map.of())));
        assertEquals("PAUSED", statusOf(admin, planId));
        long patient = registerPatient(admin, "IT-E14T01-C");
        try {
            enrollInTenant(patient, planId);
            throw new AssertionError("已暂停不允许入组");
        } catch (com.crdp.framework.web.ApiException expected) {
            assertEquals(40901, expected.code());
        }
        // 恢复 → 停用（终态）→ 恢复 40901
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/plans/" + planId + "/resume",
                                admin,
                                Map.of())));
        assertEquals("PUBLISHED", statusOf(admin, planId));
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/plans/" + planId + "/disable",
                                admin,
                                Map.of())));
        assertEquals("DISABLED", statusOf(admin, planId));
        assertEquals(
                40901,
                codeOf(post("/api/hospital/followup/plans/" + planId + "/resume", admin, Map.of())),
                "停用是终态，恢复 40901");
        assertEquals(
                40901,
                codeOf(
                        post(
                                "/api/hospital/followup/plans/" + planId + "/disable",
                                admin,
                                Map.of())),
                "重复停用 40901");

        // 校验：起算字段枚举、编码重复、无节点发布、乐观锁冲突、同族草稿唯一
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/plans",
                                admin,
                                planBody("PLAN-IT-E14-V", "someday"))),
                "起算字段枚举 40001");
        assertEquals(
                40902,
                codeOf(
                        post(
                                "/api/hospital/followup/plans",
                                admin,
                                planBody("PLAN-IT-E14-B", "DISCHARGE_DATE"))),
                "同编码同版本唯一键 40902");

        long empty =
                idOf(
                        post(
                                "/api/hospital/followup/plans",
                                admin,
                                planBody("PLAN-IT-E14-E", "DISCHARGE_DATE")));
        assertEquals(
                40001,
                codeOf(post("/api/hospital/followup/plans/" + empty + "/publish", admin, Map.of())),
                "无节点发布 40001（三层结构）");
        assertEquals(
                40901,
                codeOf(post("/api/hospital/followup/plans/" + empty + "/copy", admin, Map.of())),
                "未发布版本复制 40901（直接编辑）");

        long draft = createPublishedPlan(admin, "PLAN-IT-E14-D", 0);
        long draftV2 =
                idOf(post("/api/hospital/followup/plans/" + draft + "/copy", admin, Map.of()));
        assertEquals(
                40901,
                codeOf(post("/api/hospital/followup/plans/" + draft + "/copy", admin, Map.of())),
                "同族已有草稿再复制 40901");
        assertEquals(
                40900,
                codeOf(
                        put(
                                "/api/hospital/followup/plans/" + draftV2,
                                admin,
                                planUpdateBody(7, "过期版本（示例）"))),
                "乐观锁冲突 40900");

        // 死亡患者不可入组（A-PAT-016 批量跳过「已死亡」同口径）
        long deadPatient = registerPatient(admin, "IT-E14T01-DEAD");
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE pt_patient SET followup_status = 'DEAD' WHERE id = ?",
                                deadPatient));
        try {
            enrollInTenant(deadPatient, draft);
            throw new AssertionError("死亡患者不允许入组");
        } catch (com.crdp.framework.web.ApiException expected) {
            assertEquals(40901, expected.code());
        }
    }

    // ---------------------------------------------------------------- 权限与模块（双码任一命中）

    @Test
    void permissionsAndModuleGate() {
        String admin = adminToken();
        String doctor = doctorToken(); // FOLLOWUP_DOCTOR：有计划查看（菜单 24），无编辑 / 发布
        long planId = createPublishedPlan(admin, "PLAN-IT-E14-P", 0);

        assertEquals(0, codeOf(get("/api/hospital/followup/plans", doctor)), "医生有 plan:view 可看列表");
        assertEquals(0, codeOf(get("/api/hospital/followup/plans/" + planId, doctor)), "医生可看详情");
        assertEquals(
                40300,
                codeOf(
                        post(
                                "/api/hospital/followup/plans",
                                doctor,
                                planBody("PLAN-IT-E14-PX", "DISCHARGE_DATE"))),
                "无 followup:plan:edit 40300");
        assertEquals(
                40300,
                codeOf(post("/api/hospital/followup/plans/" + planId + "/pause", doctor, Map.of())),
                "无 followup:plan:publish 40300");

        // 无计划菜单的角色（只有患者列表 21）：双码都不命中 40300
        String noPermission = tokenOfUserWithoutPlanMenus(admin);
        assertEquals(
                40300, codeOf(get("/api/hospital/followup/plans", noPermission)), "双候选码全缺失 40300");

        // followup 模块关闭 40301（登录态在、模块未开通）
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(40301, codeOf(get("/api/hospital/followup/plans", admin)), "模块关闭 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
    }

    // ---------------------------------------------------------------- 租户隔离

    @Test
    void planConfigurationIsolatedPerTenant() {
        String admin = adminToken();
        createPublishedPlan(admin, "PLAN-IT-E14-T", 0);
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        Map<?, ?> demobList = get("/api/hospital/followup/plans", demobAdmin);
        assertEquals(0, codeOf(demobList));
        assertTrue(
                ((List<Map<?, ?>>) data(demobList).get("items"))
                        .stream()
                                .noneMatch(
                                        item ->
                                                ((String) item.get("code"))
                                                        .startsWith("PLAN-IT-E14")),
                "demoa 的计划不出现在 demob（每院独立库）");
    }

    // ---------------------------------------------------------------- 辅助

    /** 新建 → 存节点 → 发布，返回计划行 id。 */
    private long createPublishedPlan(String token, String code, int lockVersion) {
        long planId =
                idOf(post("/api/hospital/followup/plans", token, planBody(code, "DISCHARGE_DATE")));
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/followup/plans/" + planId + "/nodes",
                                token,
                                nodesBody(lockVersion))),
                "保存节点失败");
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/plans/" + planId + "/publish",
                                token,
                                Map.of())),
                "发布失败");
        return planId;
    }

    private static Map<String, Object> planBody(String code, String startDateField) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 年度随访计划（" + code + "）");
        body.put("startDateField", startDateField);
        body.put("cycleType", "ONGOING");
        return body;
    }

    private static Map<String, Object> planUpdateBody(int baseVersion, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("baseVersion", baseVersion);
        body.put("name", name);
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        return body;
    }

    /** A 医院预置形态（A-FU-017）：一个持续循环子计划 + 一个表单节点（+N 年、提前 7 天、前后 7 天）。 */
    private static Map<String, Object> nodesBody(int baseVersion) {
        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "年度随访（示例）");
        subplan.put("repeatMode", "CYCLE");
        subplan.put("cycleValue", 1);
        subplan.put("cycleUnit", "YEAR");
        subplan.put("sortNo", 10);
        Map<String, Object> node = new HashMap<>();
        node.put("key", "n1");
        node.put("parentKey", "s1");
        node.put("name", "电话随访（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", 1);
        node.put("offsetUnit", "YEAR");
        node.put("remindValue", 7);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", 7);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 7);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", "fu_annual_v1");
        node.put("sortNo", 20);
        Map<String, Object> body = new HashMap<>();
        body.put("baseVersion", baseVersion);
        body.put("nodes", List.of(subplan, node));
        return body;
    }

    /** 登记患者（A 出院登记口径，E13-T01；legacy_no 前缀便于自清）。 */
    private long registerPatient(String token, String mrn) {
        long wardId =
                runInTenant(
                        "demoa", () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients",
                        token,
                        new HashMap<>(
                                Map.of(
                                        "medicalRecordNo", mrn,
                                        "name", "赵小同（示例）",
                                        "gender", "M",
                                        "birthDate", "1951-02-02",
                                        "wardId", wardId,
                                        "surgeryDate", "2026-03-01",
                                        "admissionDate", "2026-02-25",
                                        "dischargeDate", "2026-03-08",
                                        "dischargeStatus", 1,
                                        "legacyNo", LEGACY + "-" + mrn)));
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    /** 服务层入组入口（批量入组接口随 E13-T07、自动入组随 E14-T05 复用同一入口）。 */
    private Long enrollInTenant(long patientId, long planId) {
        return runInTenant(
                "demoa",
                () -> Long.valueOf(enrollmentService.enroll(patientId, planId, null).id()));
    }

    private long planIdOfEnrollment(long enrollmentId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT plan_id FROM fu_enrollment WHERE id = ?",
                                Long.class,
                                enrollmentId));
    }

    private String statusOf(String token, long planId) {
        Map<?, ?> plan =
                (Map<?, ?>) data(get("/api/hospital/followup/plans/" + planId, token)).get("plan");
        return (String) plan.get("status");
    }

    /** 建一个只有患者列表权限（菜单 21）的角色与用户，登录后返回令牌（双码全缺失用例）。 */
    private String tokenOfUserWithoutPlanMenus(String admin) {
        long roleId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO sys_role (code, name, built_in, status)"
                                            + " VALUES ('IT-R-E14-NO', 'IT 无计划权限（示例）', 0,"
                                            + " 'ENABLED')");
                            long id =
                                    jdbc.queryForObject(
                                            "SELECT id FROM sys_role WHERE code = 'IT-R-E14-NO'",
                                            Long.class);
                            jdbc.update(
                                    "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, 21)",
                                    id);
                            jdbc.update(
                                    "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                                    id);
                            return id;
                        });
        long deptId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM sys_dept WHERE code = 'DEPT-CARDIO'",
                                        Long.class));
        Map<?, ?> created =
                post(
                        "/api/hospital/system/users",
                        admin,
                        Map.of(
                                "username", "IT-U-E14-NO",
                                "displayName", "IT 无计划权限用户（示例）",
                                "phone", "13900001391",
                                "mainDeptId", deptId,
                                "roleIds", List.of(roleId)));
        assertEquals(0, codeOf(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        String token = hospitalToken("demoa", "IT-U-E14-NO", temporary);
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", PASSWORD))),
                "改密失败");
        // 会话权限随角色变化刷新：重新登录拿最终权限
        return hospitalToken("demoa", "IT-U-E14-NO", PASSWORD);
    }

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo", PASSWORD);
    }

    private String doctorToken() {
        return hospitalToken("demoa", "doctor.demo", PASSWORD);
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

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class)
                .getBody();
    }

    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static long idOf(Map<?, ?> response) {
        return Long.parseLong((String) data(response).get("id"));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
