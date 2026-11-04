package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
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
 * E14-T08 端到端（tasks.csv 验收：计划改动全记审计；A-FU-021 计划变更审计、A-FU-022 计划患者列表）。场景：
 *
 * <ol>
 *   <li>变更记录（A-FU-021）：新建 → 保存节点 → 发布 → 复制 v2 → 编辑草稿 → 发布 v2（v1 自动停用）→ 暂停 v2 的每一步都落
 *       fu_plan_log（与审计双写）：GET /plans/{id}/logs 倒序含全部动作、前后内容快照、操作人姓名；
 *       发布引起的旧版本自动停用也在旧版本自己的变更记录里（PLAN_DISABLE）；计划不存在 40400；
 *   <li>计划患者列表（A-FU-022）：入组患者的加入时间、随访完成次数、状态（已启动 / 已暂停 / 已移除）；
 *       姓名与病案号只输出脱敏值；数据范围出行——病区受限的医生只见本病患者；分页 { items, total, pageNo, pageSize }；
 *   <li>权限与隔离：无计划菜单角色 40300（双码全缺失）；另一租户互不可见。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 PLAN-IT-E14A8 前缀、病案号 IT-E14T08-
 * 前缀、病区 W-IT-E14T08、角色 IT-R-E14-NO）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlanAuditPatientsIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String PLAN_CODE = "PLAN-IT-E14A8";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanEnrollmentService enrollmentService;

    @BeforeEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE event_type LIKE 'PLAN_%'"
                                    + " AND (detail LIKE '%"
                                    + PLAN_CODE
                                    + "%'"
                                    + " OR target IN (SELECT CONCAT('PLAN:', id) FROM fu_plan"
                                    + " WHERE code LIKE '"
                                    + PLAN_CODE
                                    + "%'))");
                    jdbc.update(
                            "DELETE t FROM fu_task t JOIN fu_enrollment e ON e.id = t.enrollment_id"
                                    + " JOIN pt_patient p ON p.id = e.patient_id"
                                    + " WHERE p.legacy_no LIKE 'IT-E14T08-%'");
                    jdbc.update(
                            "DELETE e FROM fu_enrollment e JOIN pt_patient p ON p.id = e.patient_id"
                                    + " WHERE p.legacy_no LIKE 'IT-E14T08-%'");
                    jdbc.update(
                            "DELETE l FROM fu_plan_log l JOIN fu_plan f ON f.id = l.plan_id"
                                    + " WHERE f.code LIKE '"
                                    + PLAN_CODE
                                    + "%'");
                    jdbc.update(
                            "DELETE n FROM fu_plan_node n JOIN fu_plan p ON p.id = n.plan_id"
                                    + " WHERE p.code LIKE '"
                                    + PLAN_CODE
                                    + "%'");
                    jdbc.update("DELETE FROM fu_plan WHERE code LIKE '" + PLAN_CODE + "%'");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE 'IT-E14T08-%'");
                    jdbc.update("DELETE FROM ward WHERE code = 'W-IT-E14T08'");
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

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：计划改动全记审计（A-FU-021）

    @Test
    void everyPlanChangeIsRecordedInLogsWithBeforeAfterContent() {
        String admin = adminToken();
        long v1 = createPublishedPlan(admin, PLAN_CODE + "-A");

        // v1：新建 → 保存节点 → 发布；发布无旧版本可顶替
        assertActionsOf(admin, v1, List.of("PLAN_PUBLISH", "PLAN_NODE_SAVE", "PLAN_CREATE"));

        // 复制出 v2 → 编辑草稿 → 发布 v2（v1 自动停用，在 v1 自己的记录里留 PLAN_DISABLE）
        long v2 = idOf(post("/api/hospital/followup/plans/" + v1 + "/copy", admin, Map.of()));
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/followup/plans/" + v2,
                                admin,
                                planUpdateBody(0, "示例 IT 年度随访计划 v2"))),
                "草稿可编辑");
        assertEquals(
                0,
                codeOf(post("/api/hospital/followup/plans/" + v2 + "/publish", admin, Map.of())),
                "发布 v2");
        // 暂停 / 恢复也全记
        assertEquals(
                0,
                codeOf(post("/api/hospital/followup/plans/" + v2 + "/pause", admin, Map.of())),
                "暂停 v2");
        assertEquals(
                0,
                codeOf(post("/api/hospital/followup/plans/" + v2 + "/resume", admin, Map.of())),
                "恢复 v2");

        // v2 记录（倒序）：恢复 → 暂停 → 发布 → 编辑 → 复制
        List<Map<?, ?>> v2Logs = logItemsOf(admin, v2);
        assertEquals(
                List.of("PLAN_RESUME", "PLAN_PAUSE", "PLAN_PUBLISH", "PLAN_UPDATE", "PLAN_COPY"),
                v2Logs.stream().map(item -> item.get("action")).toList(),
                "每种改动一条，倒序");

        // 前后内容（A-FU-021「前后内容」）：发布 UNPUBLISHED → PUBLISHED；操作人与时间齐全
        Map<?, ?> publishLog = v2Logs.get(2);
        assertTrue(
                String.valueOf(publishLog.get("beforeJson")).contains("\"UNPUBLISHED\""), "发布前内容");
        assertTrue(String.valueOf(publishLog.get("afterJson")).contains("\"PUBLISHED\""), "发布后内容");
        assertEquals("演示护士", publishLog.get("operatorName"), "操作人姓名联查 sys_user");
        assertNotNull(publishLog.get("createdAt"));
        assertTrue(String.valueOf(publishLog.get("createdAt")).contains("+08:00"), "北京时间");

        // 暂停记录同样带前后状态
        Map<?, ?> pauseLog = v2Logs.get(1);
        assertTrue(String.valueOf(pauseLog.get("beforeJson")).contains("\"PUBLISHED\""));
        assertTrue(String.valueOf(pauseLog.get("afterJson")).contains("\"PAUSED\""));

        // 复制记录落在生成的新版本上，含来源版本
        Map<?, ?> copyLog = v2Logs.get(4);
        assertTrue(
                String.valueOf(copyLog.get("afterJson")).contains("\"copiedFromVersion\":1"),
                "复制记录含来源版本：" + copyLog.get("afterJson"));

        // v1 被新版本顶替自动停用：v1 记录 = 顶替停用 + 发布 + 节点 + 新建
        assertActionsOf(
                admin,
                v1,
                List.of("PLAN_DISABLE", "PLAN_PUBLISH", "PLAN_NODE_SAVE", "PLAN_CREATE"));
        Map<?, ?> supersededLog = logItemsOf(admin, v1).get(0);
        assertTrue(
                String.valueOf(supersededLog.get("description")).contains("自动停用"),
                "发布新版本引起的停用说明来源：" + supersededLog.get("description"));
        assertTrue(String.valueOf(supersededLog.get("beforeJson")).contains("\"PUBLISHED\""));

        // 计划不存在 40400
        assertEquals(40400, codeOf(get("/api/hospital/followup/plans/99999999/logs", admin)));

        // 医生（followup:plan:view）可看变更记录（查看类双码）
        String doctor = doctorToken();
        assertEquals(
                0,
                codeOf(get("/api/hospital/followup/plans/" + v1 + "/logs", doctor)),
                "医生有 plan:view 可看变更记录");
    }

    // ---------------------------------------------------------------- 验收：计划患者列表（A-FU-022）

    @Test
    void planPatientListShowsEnrolledPatientsMaskedAndScoped() {
        String admin = adminToken();
        long planId = createPublishedPlan(admin, PLAN_CODE + "-B");
        long doctorWardId = wardIdOf("W-DEMO-1");
        long otherWardId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO ward (code, name, status)"
                                            + " VALUES ('W-IT-E14T08', 'IT 虚构病区二（示例）',"
                                            + " 'ENABLED')");
                            return jdbc.queryForObject(
                                    "SELECT id FROM ward WHERE code = 'W-IT-E14T08'", Long.class);
                        });

        long patientA = registerPatient(admin, "IT-E14T08-A", doctorWardId, "赵小同（示例）");
        long patientB = registerPatient(admin, "IT-E14T08-B", otherWardId, "钱大二（示例）");
        runInTenant("demoa", () -> enrollmentService.enroll(patientA, planId, null));
        runInTenant("demoa", () -> enrollmentService.enroll(patientB, planId, null));

        // 管理员（ALL 范围）：两位患者都在列表，姓名 / 病案号脱敏
        Map<?, ?> adminPage = pageOf(admin, planId);
        assertEquals(2L, ((Number) adminPage.get("total")).longValue());
        List<Map<?, ?>> items = (List<Map<?, ?>>) adminPage.get("items");
        Map<?, ?> rowA = rowOf(items, "IT-E14T08-A");
        assertEquals("ACTIVE", rowA.get("status"), "初始状态已启动");
        assertNotNull(rowA.get("enrolledAt"), "加入时间");
        assertEquals(0L, ((Number) rowA.get("completedCount")).longValue(), "暂无完成任务");
        assertFalse(String.valueOf(rowA.get("name")).contains("小同"), "姓名不透出明文：" + rowA.get("name"));
        assertTrue(
                String.valueOf(rowA.get("name")).startsWith("赵"), "脱敏姓名保留首字：" + rowA.get("name"));
        assertFalse(
                String.valueOf(rowA.get("mrn")).contains("E14T08-A"),
                "病案号不透出明文：" + rowA.get("mrn"));

        // 随访完成次数：补一条已完成任务（E15-T01 流转前直接落库验证聚合口径）
        runInTenant(
                "demoa",
                () -> {
                    Long nodeId =
                            jdbc.queryForObject(
                                    "SELECT id FROM fu_plan_node WHERE plan_id = ? AND node_type ="
                                            + " 'NODE' LIMIT 1",
                                    Long.class,
                                    planId);
                    Long enrollmentId =
                            jdbc.queryForObject(
                                    "SELECT id FROM fu_enrollment WHERE patient_id = ? AND"
                                            + " plan_id = ?",
                                    Long.class,
                                    patientA,
                                    planId);
                    jdbc.update(
                            "INSERT INTO fu_task (enrollment_id, patient_id, plan_id, node_id,"
                                    + " occurrence, plan_date, remind_start, window_start,"
                                    + " window_end, open_date, created_logical_date, status)"
                                    + " VALUES (?, ?, ?, ?, 1, '2026-03-08', '2026-03-01',"
                                    + " '2026-03-01', '2026-03-15', '2026-03-01', '2026-03-01',"
                                    + " 'COMPLETED')",
                            enrollmentId,
                            patientA,
                            planId,
                            nodeId);
                });
        assertEquals(
                1L,
                ((Number) rowOf(pageItemsOf(admin, planId), "IT-E14T08-A").get("completedCount"))
                        .longValue(),
                "随访完成次数 = fu_task COMPLETED 聚合");

        // 数据范围（AGENTS §5.2）：病区受限医生只见本病患者，不报错不越范围
        String doctor = doctorToken();
        Map<?, ?> doctorPage = pageOf(doctor, planId);
        assertEquals(1L, ((Number) doctorPage.get("total")).longValue(), "医生只见本病患者");
        assertNotNull(rowOf((List<Map<?, ?>>) doctorPage.get("items"), "IT-E14T08-A"));

        // 状态三态：已移除患者仍在列表并带原因（暂停 / 移出操作随 E14-T06，这里直接置状态验证展示）
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_enrollment SET status = 'REMOVED', remove_reason ="
                                        + " '拒访（示例）' WHERE patient_id = ? AND plan_id = ?",
                                patientB,
                                planId));
        Map<?, ?> removedPage = pageOf(admin, planId);
        Map<?, ?> rowB = rowOf((List<Map<?, ?>>) removedPage.get("items"), "IT-E14T08-B");
        assertEquals("REMOVED", rowB.get("status"), "已移除三态展示");
        assertEquals("拒访（示例）", rowB.get("removeReason"));

        // 分页：pageNo / pageSize 透传
        Map<?, ?> paged = pageOf(admin, planId, 1, 1);
        assertEquals(1, ((List<?>) paged.get("items")).size());
        assertEquals(2L, ((Number) paged.get("total")).longValue());
        assertEquals(1, paged.get("pageNo"));
        assertEquals(1, paged.get("pageSize"));

        // 计划不存在 40400
        assertEquals(
                40400, codeOf(get("/api/hospital/followup/plans/99999999/enrollments", admin)));
    }

    // ---------------------------------------------------------------- 权限与租户隔离

    @Test
    void patientListAndLogsRequirePlanPermissionAndIsolateTenants() {
        String admin = adminToken();
        long planId = createPublishedPlan(admin, PLAN_CODE + "-P");

        // 无计划菜单的角色（只有患者列表 21）：双码全缺失 40300
        String noPermission = tokenOfUserWithoutPlanMenus(admin);
        assertEquals(
                40300,
                codeOf(
                        get(
                                "/api/hospital/followup/plans/" + planId + "/enrollments",
                                noPermission)),
                "患者列表双候选码全缺失 40300");
        assertEquals(
                40300,
                codeOf(get("/api/hospital/followup/plans/" + planId + "/logs", noPermission)),
                "变更记录双候选码全缺失 40300");

        // 另一租户：计划不存在（每院独立库）
        String demobAdmin = hospitalToken("demob", "nurse.demo", PASSWORD);
        assertEquals(
                40400,
                codeOf(get("/api/hospital/followup/plans/" + planId + "/logs", demobAdmin)),
                "demoa 的计划在 demob 不可见");
    }

    // ---------------------------------------------------------------- 辅助

    /** 新建 → 存节点 → 发布，返回计划行 id。 */
    private long createPublishedPlan(String token, String code) {
        long planId = idOf(post("/api/hospital/followup/plans", token, planBody(code)));
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/followup/plans/" + planId + "/nodes",
                                token,
                                nodesBody(0))),
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

    private static Map<String, Object> planBody(String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 年度随访计划（" + code + "）");
        body.put("startDateField", "DISCHARGE_DATE");
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

    /** 登记患者（A 出院登记口径，E13-T01；legacy_no 前缀便于自清，wardId 指定病区以验证数据范围）。 */
    private long registerPatient(String token, String mrn, long wardId, String name) {
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients",
                        token,
                        new HashMap<>(
                                Map.of(
                                        "medicalRecordNo",
                                        mrn,
                                        "name",
                                        name,
                                        "gender",
                                        "M",
                                        "birthDate",
                                        "1951-02-02",
                                        "wardId",
                                        wardId,
                                        "surgeryDate",
                                        "2026-03-01",
                                        "admissionDate",
                                        "2026-02-25",
                                        "dischargeDate",
                                        "2026-03-08",
                                        "dischargeStatus",
                                        1,
                                        "legacyNo",
                                        mrn)));
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    private long wardIdOf(String code) {
        return runInTenant(
                "demoa",
                () -> jdbc.queryForObject("SELECT id FROM ward WHERE code = ?", Long.class, code));
    }

    private void assertActionsOf(String token, long planId, List<String> expectedDesc) {
        assertEquals(
                expectedDesc,
                logItemsOf(token, planId).stream().map(item -> item.get("action")).toList(),
                "变更动作序列（倒序）");
    }

    private List<Map<?, ?>> logItemsOf(String token, long planId) {
        Map<?, ?> response = get("/api/hospital/followup/plans/" + planId + "/logs", token);
        assertEquals(0, codeOf(response), "读变更记录失败：" + response);
        return (List<Map<?, ?>>) data(response).get("items");
    }

    private Map<?, ?> pageOf(String token, long planId) {
        return pageOf(token, planId, null, null);
    }

    private Map<?, ?> pageOf(String token, long planId, Integer pageNo, Integer pageSize) {
        String query = "";
        if (pageNo != null) {
            query = "?pageNo=" + pageNo + "&pageSize=" + pageSize;
        }
        Map<?, ?> response =
                get("/api/hospital/followup/plans/" + planId + "/enrollments" + query, token);
        assertEquals(0, codeOf(response), "读计划患者列表失败：" + response);
        return data(response);
    }

    private List<Map<?, ?>> pageItemsOf(String token, long planId) {
        return (List<Map<?, ?>>) pageOf(token, planId).get("items");
    }

    /** 按病案号定位行（列表只含脱敏值：前 2 + 后 2 位保留、中段掩码， 用「前两位 + 末两位」匹配，如 IT-E14T08-A → IT…-A）。 */
    private Map<?, ?> rowOf(List<Map<?, ?>> items, String mrn) {
        String head = mrn.substring(0, 2);
        String tail = mrn.substring(mrn.length() - 2);
        return items.stream()
                .filter(
                        item -> {
                            String masked = String.valueOf(item.get("mrn"));
                            return masked.startsWith(head) && masked.endsWith(tail);
                        })
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到病案号 " + mrn + " 的行"));
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
                                    "INSERT INTO sys_role_scope (role_id, scope_type)"
                                            + " VALUES (?, 'ALL')",
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
                                "phone", "13900001392",
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
