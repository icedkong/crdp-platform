package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
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
 * E21-T08 端到端（C-FU-005 / tasks.csv 验收「计划日 2027-01-15、±30 天 → 2026-12-16 建任务」；时钟相对化： 医生日期 = 今天 + 2
 * 个月、窗口 ±30 天，窗口开始日 = 医生日期 − 30 天）。场景：
 *
 * <ol>
 *   <li>补录提交带 f333（今天或未来）→ 在组入组登记 doctor_next_date（E21-T06 / SW-10 登记侧）与 last_visit_date
 *       快照（E21-T07）；
 *   <li>窗口开始日前一天跑每日作业 → 不建任务（doctor_next_date 未消费、next 缓存保持计划默认）；
 *   <li>窗口开始日当天跑 → 创建 PENDING / NORMAL 任务一条：计划日期 = 医生指定日期、窗口围绕该日期 按节点宽度（±30 天）前后展开、开启日 =
 *       窗口开始日；doctor_next_date 清理（OQ-138③ 清理已用值）；
 *   <li>重复执行幂等（uk 入组 + 节点 + 第 N 次）；滚动循环从医生指定日期起继续（下次 = 医生日期 + 12 个月）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 IT-E21T08-ROLL、 病案号 IT-E21T08- 前缀、IT
 * 程控表单 IT_E21T08_FU、job_tenant_run 本作业行）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DoctorNextDateIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E21T08";
    private static final String PLAN_CODE = "IT-E21T08-ROLL";
    private static final String FORM_CODE = "IT_E21T08_FU";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private FollowupTaskCreationService creationService;

    @BeforeEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE"
                                    + " (target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                    + " WHERE legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (event_type LIKE 'PLAN_%'"
                                    + " AND (detail LIKE '%IT-E21T08%'"
                                    + " OR target IN (SELECT CONCAT('PLAN:', id) FROM fu_plan"
                                    + " WHERE code = '"
                                    + PLAN_CODE
                                    + "')))");
                    jdbc.update(
                            "DELETE r FROM fu_record r JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE e FROM fu_enrollment e JOIN pt_patient p"
                                    + " ON p.id = e.patient_id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    jdbc.update(
                            "DELETE n FROM fu_plan_node n JOIN fu_plan p ON p.id = n.plan_id"
                                    + " WHERE p.code = ?",
                            PLAN_CODE);
                    jdbc.update("DELETE FROM fu_plan WHERE code = ?", PLAN_CODE);
                    jdbc.update("DELETE FROM fu_plan_log WHERE plan_code = ?", PLAN_CODE);
                    // IT 程控表单（JDBC 自建：f333 下次随访时间，OQ-130① 键契约）
                    jdbc.update("DELETE FROM fe_form_version_field WHERE form_code = ?", FORM_CODE);
                    jdbc.update("DELETE FROM fe_form_version WHERE form_code = ?", FORM_CODE);
                    jdbc.update("DELETE FROM fe_field WHERE form_code = ?", FORM_CODE);
                    jdbc.update("DELETE FROM fe_form WHERE code = ?", FORM_CODE);
                    // 本 IT 会跑任务创建作业（历史逻辑日）：清执行记录，避免补跑区间影响其他 IT
                    jdbc.update("DELETE FROM job_tenant_run WHERE job_code = 'fu-task-create'");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    @Test
    void doctorSpecifiedDateBecomesPlanDateWithWindowAroundIt() {
        LocalDate today = LocalDate.now();
        LocalDate visit = today.minusDays(5); // 本次随访（补录过去日期）
        LocalDate doctor = today.plusMonths(2); // 医生指定下次随访时间（今天或未来才登记）
        String admin = adminToken();
        publishedRollingPlan(admin);
        long patientId = registerPatient(admin, "01", today.minusMonths(6));
        long enrollmentId = enrollmentIdOf(patientId);

        // 补录提交带 f333 → 登记 doctor_next_date（E21-T06 / SW-10）+ last_visit_date 快照（E21-T07）
        submitBackfillRecord(admin, patientId, visit, Map.of("f333", doctor.toString()));
        Map<String, Object> registered = enrollmentRow(enrollmentId);
        assertEquals(
                java.sql.Date.valueOf(doctor),
                registered.get("doctor_next_date"),
                "提交登记医生指定下次随访日期（每次提交覆盖，只影响下一次）");
        assertEquals(java.sql.Date.valueOf(visit), registered.get("last_visit_date"), "滚动基准快照推进");

        // 窗口开始日（doctor − 30 天）前一天：不建任务、登记未消费、缓存保持计划默认（OQ-138③）
        creationService.runForDate(doctor.minusDays(31));
        assertEquals(0, taskCount(enrollmentId), "医生窗口未开启不建任务");
        assertEquals(
                java.sql.Date.valueOf(doctor),
                enrollmentRow(enrollmentId).get("doctor_next_date"),
                "未消费不清理");
        assertEquals(
                java.sql.Date.valueOf(visit.plusMonths(12)),
                enrollmentRow(enrollmentId).get("next_plan_date"),
                "缓存保持计划默认（lastVisit + 12 个月），读取侧医生日期优先");

        // 窗口开始日当天：创建任务（验收：计划日 = 医生日期、窗口 ±30 天围绕展开）
        creationService.runForDate(doctor.minusDays(30));
        assertEquals(1, taskCount(enrollmentId), "窗口开始日当天创建");
        Map<String, Object> task = taskOf(enrollmentId);
        assertEquals(java.sql.Date.valueOf(doctor), task.get("plan_date"), "计划日期 = 医生指定日期");
        assertEquals(java.sql.Date.valueOf(doctor.minusDays(30)), task.get("window_start"));
        assertEquals(java.sql.Date.valueOf(doctor.plusDays(30)), task.get("window_end"));
        assertEquals(java.sql.Date.valueOf(doctor.minusDays(30)), task.get("open_date"));
        assertEquals("NORMAL", task.get("create_type"));
        assertEquals("PENDING", task.get("status"));
        assertEquals(1, ((Number) task.get("occurrence")).intValue());
        assertNull(enrollmentRow(enrollmentId).get("doctor_next_date"), "消费后清理已用值（OQ-138③，只影响下一次）");

        // 重放幂等；再跑一天滚动循环从医生指定日期起继续（下次 = doctor + 12 个月、开启 − 30 天）
        creationService.runForDate(doctor.minusDays(29));
        assertEquals(1, taskCount(enrollmentId), "重复执行不重复创建（uk 幂等）");
        Map<String, Object> cache = enrollmentRow(enrollmentId);
        assertEquals(
                java.sql.Date.valueOf(doctor.plusMonths(12)),
                cache.get("next_plan_date"),
                "滚动从医生指定日期起继续");
        assertEquals(
                java.sql.Date.valueOf(doctor.plusMonths(12).minusDays(30)),
                cache.get("next_open_date"));
    }

    // ---------------------------------------------------------------- 辅助

    /** C 常规随访形态（V91 同构）：CYCLE 子计划 + 滚动节点（LAST_VISIT + 12 个月、提醒 / 窗口前 / 后各 30 天）。 */
    private void publishedRollingPlan(String token) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", PLAN_CODE);
        body.put("name", "示例 IT 程控随访计划（常规随访滚动）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        body.put("autoEnroll", false);
        long planId = idOf(post("/api/hospital/followup/plans", token, body));

        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "常规随访（示例）");
        subplan.put("repeatMode", "CYCLE");
        subplan.put("cycleValue", 12);
        subplan.put("cycleUnit", "MONTH");
        subplan.put("sortNo", 10);
        Map<String, Object> node = new HashMap<>();
        node.put("key", "n1");
        node.put("parentKey", "s1");
        node.put("name", "常规随访（示例）");
        node.put("basis", "LAST_VISIT");
        node.put("offsetValue", 12);
        node.put("offsetUnit", "MONTH");
        node.put("remindValue", 30);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", 30);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 30);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", FORM_CODE);
        node.put("passedWindowAction", "SKIP");
        node.put("enrollMode", "NORMAL");
        node.put("sortNo", 20);
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/followup/plans/" + planId + "/nodes",
                                token,
                                Map.of("baseVersion", 0, "nodes", List.of(subplan, node)))),
                "保存节点失败");
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/followup/plans/" + planId + "/publish",
                                token,
                                Map.of())),
                "发布失败");
    }

    /** 登记患者（E13-T01 接口，出院日期起算；无植入记录）。 */
    private long registerPatient(String token, String suffix, LocalDate dischargeDate) {
        long wardId =
                runInTenant(
                        "demoa", () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", "澹台示例（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1950-06-01");
        body.put("wardId", wardId);
        body.put("admissionDate", dischargeDate.minusDays(14).toString());
        body.put("surgeryDate", dischargeDate.minusDays(7).toString());
        body.put("dischargeDate", dischargeDate.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + suffix);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        long patientId = Long.parseLong(String.valueOf(data(created).get("id")));
        // 服务层入组（计划 auto_enroll = false，无映射路径）：须在租户上下文内（TenantRoutingDataSource）
        runInTenant("demoa", () -> enrollmentService.enroll(patientId, planIdOf(PLAN_CODE), null));
        return patientId;
    }

    /** 补录提交（POST /records + /submit）：f333 下次随访时间进表单数据（E21-T06 / SW-10 登记路径）。 */
    private void submitBackfillRecord(
            String token, long patientId, LocalDate visitDate, Map<String, Object> data) {
        createProgramForm();
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        token,
                        Map.of(
                                "source",
                                "BACKFILL",
                                "visitDate",
                                visitDate.toString(),
                                "formCode",
                                FORM_CODE));
        assertEquals(0, codeOf(created), "补录创建失败：" + created);
        String recordId = String.valueOf(data(created).get("recordId"));
        Map<?, ?> submitted =
                post(
                        "/api/hospital/followup/records/" + recordId + "/submit",
                        token,
                        Map.of("baseVersion", 0, "data", data));
        assertEquals(0, codeOf(submitted), "补录提交失败：" + submitted);
    }

    /** IT 程控表单（JDBC 自建：f333 下次随访时间，date、非必填）。 */
    private void createProgramForm() {
        runInTenant(
                "demoa",
                () -> {
                    Integer exists =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM fe_form WHERE code = ?",
                                    Integer.class,
                                    FORM_CODE);
                    if (exists != null && exists > 0) {
                        return;
                    }
                    jdbc.update(
                            "INSERT INTO fe_form (code, name, usage_type, current_version,"
                                    + " categories) VALUES (?, 'IT 程控随访（示例）', 'FOLLOWUP', 1,"
                                    + " JSON_ARRAY(JSON_OBJECT('key','fu_overview','label','随访概况','sortNo',10)))",
                            FORM_CODE);
                    jdbc.update(
                            "INSERT INTO fe_field (form_code, field_key, field_code, label,"
                                    + " category_key, type, option_set_code, required, sort_no)"
                                    + " VALUES (?, 'f333', 'next_visit_date', '下次随访时间',"
                                    + " 'fu_overview', 'date', NULL, 0, 30)",
                            FORM_CODE);
                    jdbc.update(
                            "INSERT INTO fe_form_version (form_code, version, categories,"
                                    + " field_count) SELECT code, current_version, categories, 1"
                                    + " FROM fe_form WHERE code = ?",
                            FORM_CODE);
                    jdbc.update(
                            "INSERT INTO fe_form_version_field"
                                    + " (version_id, form_code, version, field_key, field_code,"
                                    + " label, category_key, type, unit, decimals, hard_range,"
                                    + " soft_range, option_set_code, required, visible_when,"
                                    + " required_when, derived_def, sensitive_flag, storage_type,"
                                    + " help_text, sort_no)"
                                    + " SELECT v.id, f.form_code, v.version, f.field_key, f.field_code,"
                                    + " f.label, f.category_key, f.type, f.unit, f.decimals,"
                                    + " f.hard_range, f.soft_range, f.option_set_code, f.required,"
                                    + " f.visible_when, f.required_when, f.derived_def,"
                                    + " f.sensitive_flag, f.storage_type, f.help_text, f.sort_no"
                                    + " FROM fe_field f JOIN fe_form_version v"
                                    + " ON v.form_code = f.form_code AND v.version = 1"
                                    + " WHERE f.form_code = ? AND f.status = 'ENABLED'",
                            FORM_CODE);
                });
    }

    private long planIdOf(String code) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM fu_plan WHERE code = ?", Long.class, code));
    }

    /** 患者在本 IT 计划下的在组入组（demoa 预置 A 年度计划 auto_enroll 会同时入组，须按计划过滤）。 */
    private long enrollmentIdOf(long patientId) {
        Long id =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT e.id FROM fu_enrollment e"
                                                + " JOIN fu_plan p ON p.id = e.plan_id"
                                                + " WHERE e.patient_id = ? AND p.code = ?"
                                                + " AND e.status = 'ACTIVE'",
                                        Long.class,
                                        patientId,
                                        PLAN_CODE));
        assertNotNull(id, "应有本计划在组入组");
        return id;
    }

    private Map<String, Object> enrollmentRow(long enrollmentId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT * FROM fu_enrollment WHERE id = ?",
                                DoctorNextDateIT::resultMap,
                                enrollmentId));
    }

    private int taskCount(long enrollmentId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM fu_task WHERE enrollment_id = ?",
                                Integer.class,
                                enrollmentId));
    }

    private Map<String, Object> taskOf(long enrollmentId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT * FROM fu_task WHERE enrollment_id = ? ORDER BY id LIMIT 1",
                                DoctorNextDateIT::resultMap,
                                enrollmentId));
    }

    private static Map<String, Object> resultMap(java.sql.ResultSet rs, int rowNum)
            throws java.sql.SQLException {
        Map<String, Object> row = new HashMap<>();
        int columns = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= columns; i++) {
            row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
        }
        return row;
    }

    private String adminToken() {
        return hospitalToken("demoa", "nurse.demo", PASSWORD);
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
        TenantContext.runAsTenant(1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant(1L, tenant, action::get);
    }
}
