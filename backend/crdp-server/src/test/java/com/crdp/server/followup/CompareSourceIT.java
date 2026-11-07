package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * E17-T02 端到端 / A-FU-027：上一次记录解析（{@code GET /records/{id}} 的 previous）。验收原文：第 1 年与基线对比。
 *
 * <p>链路：表单页详情 → {@code previous}（「由后端按规则参数选取，前端不自己找」）——此前无已提交随访时回落基线分节（出院前超声 f102 EF / f103 LVDd，迁移
 * V67 种子规则的回落列）；有已提交随访时取对比时点早于本次、最近的一条（对比时点 = 实际随访日期， TASK 记录 = 任务计划日期）；计划外随访与草稿不作对比对象；功能开关
 * compare_highlight 关闭 → previous 缺省（记录页正常返回）。
 *
 * <p>租户隔离：解析全部走租户库既有表（fu_record / fu_task / pt_baseline_section），路由未改，双租户口径由 CompareRulesIT 与
 * BackfillTimelineIT 锁定。全部虚构数据，门控 CRDP_IT_MYSQL_PORT。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CompareSourceIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E17T02";
    private static final String PLAN_CODE = "PLAN-IT-E17T02";
    private static final String FORM = "A_ANNUAL_FU";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanEnrollmentService enrollmentService;

    @BeforeEach
    @AfterEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        // 与 BackfillTimelineIT 同口径：关预置计划自动入组，保持「单计划世界」
                        jdbc.update(
                                "UPDATE fu_plan SET auto_enroll = 0 WHERE code = 'A_ANNUAL_FU_PLAN'");
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 1 WHERE feature_code ="
                                        + " 'compare_highlight'");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE (event_type IN"
                                        + " ('RECORD_SUBMIT', 'RECORD_BACKFILL_CREATE',"
                                        + " 'RECORD_UNPLANNED_CREATE')"
                                        + " AND target IN (SELECT CONCAT('FU_RECORD:', r.id)"
                                        + " FROM fu_record r JOIN pt_patient p"
                                        + " ON p.id = r.patient_id"
                                        + " WHERE p.legacy_no LIKE '"
                                        + LEGACY
                                        + "-%'))"
                                        + " OR (event_type LIKE 'PLAN_%'"
                                        + " AND target IN (SELECT CONCAT('PLAN:', id) FROM fu_plan"
                                        + " WHERE code LIKE '"
                                        + PLAN_CODE
                                        + "%'))");
                        jdbc.update(
                                "DELETE fh FROM fe_field_history fh"
                                        + " JOIN fu_record r ON r.id = fh.record_id"
                                        + " JOIN pt_patient p ON p.id = r.patient_id"
                                        + " WHERE fh.record_type = 'FU_RECORD'"
                                        + " AND p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE r FROM fu_record r JOIN pt_patient p"
                                        + " ON p.id = r.patient_id WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE e FROM fu_enrollment e JOIN pt_patient p"
                                        + " ON p.id = e.patient_id WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE s FROM pt_baseline_section s JOIN pt_patient p"
                                        + " ON p.id = s.patient_id WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE n FROM fu_plan_node n JOIN fu_plan p ON p.id = n.plan_id"
                                        + " WHERE p.code LIKE ?",
                                PLAN_CODE + "%");
                        jdbc.update("DELETE FROM fu_plan WHERE code LIKE ?", PLAN_CODE + "%");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 用例

    /**
     * 验收位（tasks.csv E17-T02「第 1 年与基线对比」）：第 1 年任务记录（此前无已提交随访）的 previous 回落 基线出院前超声（f102 EF 55、f103
     * LVDd 52，分节当前值）；无基线分节的患者 previous 为空。
     */
    @Test
    void firstYearComparesAgainstBaselineEcho() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "P1", today.minusDays(20));
        seedBaselineEcho(patientId, "{\"f102\":55,\"f103\":52,\"f113\":0}");
        long taskId = enrollCurrentTask("demoa", patientId, planId);
        String recordId = startTask(doctor, taskId);

        Map<?, ?> previous = previousOf(doctor, recordId);
        assertEquals("BASELINE", previous.get("kind"), "第 1 年与基线对比（A-FU-027）");
        assertNull(previous.get("recordId"), "基线回落不带记录定位");
        assertEquals(55, ((Number) valuesOf(previous).get("f102")).intValue(), "出院前 EF");
        assertEquals(52, ((Number) valuesOf(previous).get("f103")).intValue(), "出院前 LVDd");
        assertNull(valuesOf(previous).get("f113"), "值只取启用规则的回落字段（V67 种子两条）");

        // 无基线分节：previous 为空（规则求值侧逐条跳过）
        long noBaseline = registerPatient("demoa", doctor, "P1B", today.minusDays(20));
        long task2 = enrollCurrentTask("demoa", noBaseline, planId);
        assertNull(previousOf(doctor, startTask(doctor, task2)), "无上一次随访且无基线 → null");
    }

    /** 有已提交随访：previous = 对比时点早于本次、最近的一条（BACKFILL 也作对比对象），值只取规则引用键。 */
    @Test
    void previousFollowupIsNearestEarlierSubmittedRecord() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "P2", today.minusDays(20));
        seedBaselineEcho(patientId, "{\"f102\":55,\"f103\":52}");
        long taskId = enrollCurrentTask("demoa", patientId, planId);

        // 更早一条已提交补录（f248=41 / f249=58）：任务记录（对比时点 = 计划日期 = 今天）的上一次
        String earlier = createBackfill(doctor, patientId, today.minusDays(3));
        assertEquals(
                0,
                code(submit(doctor, earlier, today.minusDays(3), Map.of("f248", 41, "f249", 58))),
                "补录提交失败");

        Map<?, ?> previous = previousOf(doctor, startTask(doctor, taskId));
        assertEquals("FOLLOWUP", previous.get("kind"));
        assertEquals(earlier, String.valueOf(previous.get("recordId")), "取最近一条已提交随访");
        assertEquals("BACKFILL", previous.get("source"), "补录在对比对象内（含历史导入口径）");
        assertEquals(
                today.minusDays(3).toString(),
                String.valueOf(previous.get("visitDate")),
                "对比时点 = 实际随访日期");
        assertEquals(41, ((Number) valuesOf(previous).get("f248")).intValue(), "EF 上次值");
        assertEquals(58, ((Number) valuesOf(previous).get("f249")).intValue(), "LVDd 上次值");
        assertNull(valuesOf(previous).get("f214"), "值只取启用规则引用键（f214 随访日期不在白名单）");
    }

    /** 计划外随访与草稿不作对比对象：两者都早于本次也不被取作上一次，仍回落基线。 */
    @Test
    void unplannedAndDraftAreNotCompareObjects() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "P3", today.minusDays(20));
        seedBaselineEcho(patientId, "{\"f102\":55,\"f103\":52}");
        enrollCurrentTask("demoa", patientId, planId);

        // 已提交计划外（today-3）与未提交补录草稿（today-4）都早于本次补录（today-2）
        String unplanned = createRecord(doctor, patientId, "UNPLANNED", today.minusDays(3));
        assertEquals(
                0,
                code(submit(doctor, unplanned, today.minusDays(3), Map.of("f248", 10))),
                "计划外提交失败");
        createBackfill(doctor, patientId, today.minusDays(4)); // 草稿，不提交

        Map<?, ?> previous =
                previousOf(doctor, createBackfill(doctor, patientId, today.minusDays(2)));
        assertEquals("BASELINE", previous.get("kind"), "计划外与草稿不作对比对象 → 回落基线（A-FU-027）");
        assertEquals(55, ((Number) valuesOf(previous).get("f102")).intValue());
    }

    /**
     * TASK 记录的对比时点 = 任务计划日期（visit_date 空、年度时点即计划日期）： 已提交任务记录（计划日期 today-1、id 较小）与已提交补录 today-3
     * 之间，同日补录（today-1、id 较大）的上一次应取任务记录—— 计划日期解析失效时上一次会退成 today-3 的补录。
     */
    @Test
    void taskRecordUsesPlanDateAsEffectiveDate() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        // 起算 T−21 + 偏移 20 → 计划日期 = today-1，窗口 [today-4, today+2] 含今天（入组建当期任务）
        long patientId = registerPatient("demoa", doctor, "P4", today.minusDays(21));
        seedBaselineEcho(patientId, "{\"f102\":55,\"f103\":52}");
        long taskId = enrollCurrentTask("demoa", patientId, planId);

        // 1) 任务记录：提交后 visit_date 列仍为空，对比时点 = 计划日期 today-1（fu_task.plan_date）
        String taskRecord = startTask(doctor, taskId);
        assertEquals(0, code(submit(doctor, taskRecord, today, Map.of("f248", 44))), "任务记录提交失败");
        assertNull(
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT visit_date FROM fu_record WHERE id = ?",
                                        LocalDate.class,
                                        Long.parseLong(taskRecord))),
                "TASK 记录不落 visit_date 列");

        // 2) 更早一条补录 today-3；3) 本次补录 today-1（与任务计划日期同日、id 更大）
        String older = createBackfill(doctor, patientId, today.minusDays(3));
        assertEquals(0, code(submit(doctor, older, today.minusDays(3), Map.of("f248", 50))));
        Map<?, ?> previous =
                previousOf(doctor, createBackfill(doctor, patientId, today.minusDays(1)));

        assertEquals("FOLLOWUP", previous.get("kind"));
        assertEquals(
                taskRecord, String.valueOf(previous.get("recordId")), "上一次 = 任务记录（非 today-3 补录）");
        assertEquals(
                today.minusDays(1).toString(),
                String.valueOf(previous.get("visitDate")),
                "对比时点 = 任务计划日期（visit_date 空）");
        assertEquals(44, ((Number) valuesOf(previous).get("f248")).intValue());
    }

    /** 功能开关 compare_highlight 关闭 → previous 缺省（记录详情本身正常返回）；恢复后回落基线重现。 */
    @Test
    void featureOffHidesPreviousButRecordDetailStillWorks() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "P5", today.minusDays(20));
        seedBaselineEcho(patientId, "{\"f102\":55,\"f103\":52}");
        String recordId = startTask(doctor, enrollCurrentTask("demoa", patientId, planId));
        assertEquals("BASELINE", previousOf(doctor, recordId).get("kind"), "开关开启先确认有值");

        try {
            runInTenant(
                    "demoa",
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_feature SET enabled = 0 WHERE feature_code ="
                                            + " 'compare_highlight'"));
            Map<?, ?> detailResponse = get("/api/hospital/followup/records/" + recordId, doctor);
            assertEquals(0, code(detailResponse), "开关关闭不影响记录详情本身");
            assertNull(data(detailResponse).get("previous"), "开关关闭不解析上一次（AGENTS §5.7）");
        } finally {
            runInTenant(
                    "demoa",
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_feature SET enabled = 1 WHERE feature_code ="
                                            + " 'compare_highlight'"));
        }
        assertEquals("BASELINE", previousOf(doctor, recordId).get("kind"), "恢复后回落基线重现");
    }

    // ---------------------------------------------------------------- 造数与工具

    /** 基线出院前超声分节（A_BASELINE / echo；分节当前值即回落值，基线无提交语义）。 */
    private void seedBaselineEcho(long patientId, String dataJson) {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "INSERT INTO pt_baseline_section"
                                        + " (patient_id, form_code, category_key, template_version,"
                                        + " data, has_draft)"
                                        + " SELECT ?, 'A_BASELINE', 'echo', f.current_version,"
                                        + " CAST(? AS JSON), 0 FROM fe_form f"
                                        + " WHERE f.code = 'A_BASELINE'",
                                patientId,
                                dataJson));
    }

    /** 发布「循环 365 天 + 偏移 20 天」计划：起算 T−20 → 第 1 次计划日期 = 今天（窗口 ±3 已开启）。 */
    private long publishedCyclePlan(String token) {
        String code = PLAN_CODE + "-" + System.nanoTime() % 100000;
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 对比解析计划（" + code + "）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        Map<?, ?> created = post("/api/hospital/followup/plans", token, body);
        assertEquals(0, code(created), "新建计划失败：" + created);
        long planId = Long.parseLong((String) data(created).get("id"));

        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "年度随访（示例）");
        subplan.put("repeatMode", "CYCLE");
        subplan.put("cycleValue", 365);
        subplan.put("cycleUnit", "DAY");
        subplan.put("sortNo", 10);
        Map<String, Object> node = new HashMap<>();
        node.put("key", "n1");
        node.put("parentKey", "s1");
        node.put("name", "电话随访（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", 20);
        node.put("offsetUnit", "DAY");
        node.put("remindValue", 3);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", 3);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 3);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", FORM);
        node.put("sortNo", 20);
        assertEquals(
                0,
                code(
                        put(
                                "/api/hospital/followup/plans/" + planId + "/nodes",
                                token,
                                Map.of("baseVersion", 0, "nodes", List.of(subplan, node)))),
                "保存节点失败");
        assertEquals(
                0,
                code(post("/api/hospital/followup/plans/" + planId + "/publish", token, Map.of())),
                "发布失败");
        return planId;
    }

    private long registerPatient(String tenant, String token, String suffix, LocalDate discharge) {
        long wardId =
                runInTenant(
                        tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", "周对比（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1956-02-02");
        body.put("wardId", wardId);
        body.put("admissionDate", discharge.minusDays(3).toString());
        body.put("surgeryDate", discharge.minusDays(2).toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + suffix);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    private long enrollCurrentTask(String tenant, long patientId, long planId) {
        return runInTenant(
                tenant,
                () -> {
                    long enrollmentId =
                            Long.parseLong(enrollmentService.enroll(patientId, planId, null).id());
                    return jdbc.queryForObject(
                            "SELECT id FROM fu_task WHERE enrollment_id = ? ORDER BY id DESC LIMIT 1",
                            Long.class,
                            enrollmentId);
                });
    }

    /** 经任务开始录入（A-FU-005）：返回 recordId（表单页路由口径）。 */
    private String startTask(String token, long taskId) {
        Map<?, ?> started =
                post("/api/hospital/followup/tasks/" + taskId + "/start", token, Map.of());
        assertEquals(0, code(started), "开始录入失败：" + started);
        return String.valueOf(data(started).get("recordId"));
    }

    /** 建补录草稿（HTTP 正口，A-FU-014）。 */
    private String createBackfill(String token, long patientId, LocalDate visitDate) {
        return createRecord(token, patientId, "BACKFILL", visitDate);
    }

    /** 建补录 / 计划外草稿（E15-T07 / E15-T06 正口）。 */
    private String createRecord(String token, long patientId, String source, LocalDate visitDate) {
        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        token,
                        Map.of("source", source, "visitDate", visitDate.toString()));
        assertEquals(0, code(created), "创建记录失败：" + created);
        return String.valueOf(data(created).get("recordId"));
    }

    /**
     * 提交记录（A_ANNUAL_FU 必填：f214 随访日期、f215 是否死亡、f213 已加微信；f236 复查超声 = 是——fu_echo
     * 字段条件显示，否则提交时按隐藏字段清空；extras 叠加对比字段）。
     */
    private Map<?, ?> submit(
            String token, String recordId, LocalDate visitDate, Map<String, Object> extras) {
        Map<String, Object> data = new HashMap<>();
        data.put("f214", visitDate.toString());
        data.put("f215", 0);
        data.put("f213", 1);
        data.put("f236", 1); // 复查超声 = 是：fu_echo 字段（f248 EF / f249 LVDd）条件显示，否则提交时按隐藏字段清空
        data.put("f237", visitDate.toString()); // 超声检查时间（f236=1 时条件必填）
        data.putAll(extras);
        return post(
                "/api/hospital/followup/records/" + recordId + "/submit",
                token,
                Map.of("baseVersion", 0, "data", data));
    }

    /** 记录详情里的 previous（code != 0 fail）。 */
    private Map<?, ?> previousOf(String token, String recordId) {
        Map<?, ?> detail = get("/api/hospital/followup/records/" + recordId, token);
        assertEquals(0, code(detail), "记录详情失败：" + detail);
        return (Map<?, ?>) data(detail).get("previous");
    }

    private static Map<?, ?> valuesOf(Map<?, ?> previous) {
        return (Map<?, ?>) previous.get("values");
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }

    private String hospitalToken(String tenant, String username, String password) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", password));
        assertEquals(0, code(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    private Map<?, ?> post(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.POST, path, token, body);
    }

    private Map<?, ?> put(String path, String token, Map<String, Object> body) {
        return exchange(HttpMethod.PUT, path, token, body);
    }

    private Map<?, ?> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null);
    }

    private Map<?, ?> exchange(
            HttpMethod method, String path, String token, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        Map<?, ?> response = rest.exchange(path, method, entity, Map.class).getBody();
        return response == null ? Map.of() : response;
    }

    private static int code(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
    }
}
