package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import com.crdp.server.platform.TenantModuleService;
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
 * E15-T07 端到端 / A-FU-014、A-FU-023、C-FU-023（完整应用 + 真实 MySQL，HTTP 直连 + 服务层造数）。
 * 验收原文：时间轴显示子计划进度与下次计划日期；历史补录填写实际随访日期并标记为补录。
 *
 * <p>覆盖：时间轴（任务节点带窗口 / 完成日期 / 完成进度 x/y、末尾「下次计划日期」计划中节点 canOpen=false、 补录记录单独标识并按实际随访日期定位）；历史补录（过去日期
 * + 实际随访日期 → DRAFT fu_record（source=BACKFILL）→ 表单详情带患者摘要与 visitDate → 提交
 * SUBMITTED；补录提交「是否死亡=是」同样终止未开始任务，OQ-79②）； 校验（未来日期 40001、UNPLANNED 40001、缺日期 40001）；无权限 40300；模块关闭
 * 40301； 越范围患者 40400；demoa / demob 双租户隔离。门控 CRDP_IT_MYSQL_PORT。全部虚构数据（legacy IT-E15T07-*）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BackfillTimelineIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E15T07";
    private static final String PLAN_CODE = "PLAN-IT-E15T07";
    private static final String FORM = "A_ANNUAL_FU";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantModuleService moduleService;
    @Autowired private PlanEnrollmentService enrollmentService;

    @BeforeEach
    @AfterEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE (event_type IN"
                                        + " ('RECORD_SUBMIT', 'RECORD_BACKFILL_CREATE',"
                                        + " 'PATIENT_DEATH_TERMINATE')"
                                        + " AND (target IN (SELECT CONCAT('FU_RECORD:', r.id)"
                                        + " FROM fu_record r JOIN pt_patient p ON p.id = r.patient_id"
                                        + " WHERE p.legacy_no LIKE '"
                                        + LEGACY
                                        + "-%')"
                                        + " OR target IN (SELECT CONCAT('PATIENT:', p.id)"
                                        + " FROM pt_patient p WHERE p.legacy_no LIKE '"
                                        + LEGACY
                                        + "-%')))"
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
                                "DELETE n FROM fu_plan_node n JOIN fu_plan p ON p.id = n.plan_id"
                                        + " WHERE p.code LIKE ?",
                                PLAN_CODE + "%");
                        jdbc.update("DELETE FROM fu_plan WHERE code LIKE ?", PLAN_CODE + "%");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                        jdbc.update(
                                "DELETE ur FROM sys_user_role ur JOIN sys_user u"
                                        + " ON u.id = ur.user_id WHERE u.username LIKE 'IT-U-E15T07-%'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u"
                                        + " ON u.id = ph.user_id WHERE u.username LIKE 'IT-U-E15T07-%'");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-E15T07-%'");
                        for (String roleCode : List.of("IT-R-E15T07-VIEW", "IT-R-E15T07-NOEDIT")) {
                            jdbc.update(
                                    "DELETE rm FROM sys_role_menu rm JOIN sys_role r"
                                            + " ON r.id = rm.role_id WHERE r.code = ?",
                                    roleCode);
                            jdbc.update(
                                    "DELETE rs FROM sys_role_scope rs JOIN sys_role r"
                                            + " ON r.id = rs.role_id WHERE r.code = ?",
                                    roleCode);
                            jdbc.update("DELETE FROM sys_role WHERE code = ?", roleCode);
                        }
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 时间轴（A-FU-023 验收位）

    /** 时间轴显示子计划进度（x/y）与下次计划日期（计划中 / 待开始）。 */
    @Test
    void timelineShowsSubplanProgressAndNextPlannedDate() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "T1", today.minusDays(20));
        long taskId = enrollCurrentTask("demoa", patientId, planId);

        // 未完成：进度 0/1，PENDING 节点可进入录入；末尾为下次计划日期（计划中）
        Map<?, ?> timeline = timelineOf(patientId, doctor);
        Map<?, ?> pending = followupNode(timeline);
        assertEquals(String.valueOf(taskId), pending.get("taskId"));
        assertEquals("PENDING", pending.get("status"));
        assertEquals(0, ((Number) pending.get("progressDone")).intValue(), "子计划进度 0/1");
        assertEquals(1, ((Number) pending.get("progressTotal")).intValue());
        assertNotNull(pending.get("windowStart"), "窗口起止随节点返回");
        assertEquals(Boolean.TRUE, pending.get("canOpen"), "未开始任务可进入录入");

        Map<?, ?> planned = plannedNode(timeline);
        assertNotNull(planned, "末尾显示下次计划日期（A-FU-023）");
        assertEquals(
                today.plusDays(365),
                LocalDate.parse(String.valueOf(planned.get("planDate"))),
                "下次计划日期 = 第 2 次循环（实时缓存，A-FU-001）");
        assertEquals("PLANNED", planned.get("status"), "「计划中 / 待开始」状态");
        assertEquals(Boolean.FALSE, planned.get("canOpen"), "计划中节点不可打开");
        assertEquals("计划中，任务开启日才创建", planned.get("note"));

        // 提交后：进度 1/1、带完成日期；下次计划日期仍指向再下一次
        submitTaskRecord(doctor, taskId);

        Map<?, ?> after = timelineOf(patientId, doctor);
        Map<?, ?> completed = followupNode(after);
        assertEquals("COMPLETED", completed.get("status"));
        assertEquals(1, ((Number) completed.get("progressDone")).intValue(), "提交后进度 1/1");
        assertEquals(1, ((Number) completed.get("progressTotal")).intValue());
        assertEquals(today, LocalDate.parse(String.valueOf(completed.get("doneDate"))), "完成日期");
        assertEquals("ON_TIME", completed.get("completeKind"));
        assertNotNull(plannedNode(after), "长期计划仍有下次计划日期");
    }

    @Test
    void timelineShowsBackfillRecordWithMarker() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "T2", today.minusDays(20));
        enrollCurrentTask("demoa", patientId, planId);

        String recordId = createBackfill(doctor, patientId, today.minusYears(2).plusDays(3));
        submitRecord(doctor, recordId, today.minusYears(2).plusDays(3), 0);

        Map<?, ?> timeline = timelineOf(patientId, doctor);
        Map<?, ?> backfill = backfillNode(timeline);
        assertNotNull(backfill, "补录记录单独成节点（C-FU-023）");
        assertEquals("BACKFILL", backfill.get("kind"));
        assertEquals("历史补录", backfill.get("label"));
        assertEquals("COMPLETED", backfill.get("status"));
        assertTrue(
                String.valueOf(backfill.get("note")).contains("补录"),
                "补录标识：" + backfill.get("note"));
        assertEquals(
                today.minusYears(2).plusDays(3),
                LocalDate.parse(String.valueOf(backfill.get("date"))),
                "按实际随访日期定位");
        assertEquals(recordId, String.valueOf(backfill.get("recordId")));
    }

    // ---------------------------------------------------------------- 历史补录（A-FU-014 / C-FU-013）

    /** 补录：过去日期 + 实际随访日期 → DRAFT（source=BACKFILL）→ 表单详情 → 提交 SUBMITTED（无任务联动）。 */
    @Test
    void backfillCreatesDraftAndSubmitsWithoutTask() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "B1", today.minusDays(20));
        long taskId = enrollCurrentTask("demoa", patientId, planId);
        LocalDate visitDate = today.minusDays(400);

        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        doctor,
                        Map.of("source", "BACKFILL", "visitDate", visitDate.toString()));
        assertEquals(0, code(created), "补录创建失败：" + created);
        String recordId = String.valueOf(data(created).get("recordId"));
        assertEquals(visitDate.toString(), String.valueOf(data(created).get("visitDate")));
        assertEquals(FORM, data(created).get("formCode"), "缺省表单取在组计划 FORM 节点（A_ANNUAL_FU）");
        assertEquals(
                "BACKFILL",
                jdbcQuery(
                        "demoa",
                        "SELECT source FROM fu_record WHERE id = ?",
                        String.class,
                        Long.parseLong(recordId)),
                "记录来源标记为补录（A-FU-014）");
        assertNull(
                jdbcQuery(
                        "demoa",
                        "SELECT task_id FROM fu_record WHERE id = ?",
                        Long.class,
                        Long.parseLong(recordId)),
                "补录不需要任务（C-FU-013）");

        // 表单详情：无任务上下文，但带患者摘要与 visitDate（表单页头幅）
        Map<?, ?> detail = get("/api/hospital/followup/records/" + recordId, doctor);
        assertEquals(0, code(detail), "详情失败：" + detail);
        assertNull(data(detail).get("context"), "补录记录无任务上下文");
        assertEquals("BACKFILL", data(detail).get("source"));
        assertEquals(visitDate.toString(), String.valueOf(data(detail).get("visitDate")));
        Map<?, ?> brief = (Map<?, ?>) data(detail).get("patient");
        assertTrue(String.valueOf(brief.get("patientName")).contains("*"), "摘要姓名脱敏");
        assertEquals(String.valueOf(patientId), String.valueOf(brief.get("patientId")));

        // 提交：无任务 → 不联动完成；任务保持 PENDING
        Map<?, ?> submitted = submitRecord(doctor, recordId, visitDate, 0);
        assertEquals(0, code(submitted), "提交失败：" + submitted);
        assertNull(data(submitted).get("taskId"), "补录提交无任务联动");
        assertEquals("PENDING", taskStatusOf("demoa", taskId), "原任务不受补录影响");
        assertEquals(
                "SUBMITTED",
                jdbcQuery(
                        "demoa",
                        "SELECT status FROM fu_record WHERE id = ?",
                        String.class,
                        Long.parseLong(recordId)));
    }

    /** 补录提交「是否死亡=是」同样终止未开始任务（OQ-79②，A-FU-014 补录同口径）。 */
    @Test
    void backfillDeathTerminatesUnstartedTasks() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "B2", today.minusDays(20));
        long taskId = enrollCurrentTask("demoa", patientId, planId);
        String recordId = createBackfill(doctor, patientId, today.minusDays(500));

        Map<?, ?> submitted = submitRecord(doctor, recordId, today.minusDays(500), 1);
        assertEquals(0, code(submitted), "提交失败：" + submitted);
        assertEquals("TERMINATED", taskStatusOf("demoa", taskId), "死亡终止未开始任务");
        assertEquals(
                "DEAD",
                jdbcQuery(
                        "demoa",
                        "SELECT followup_status FROM pt_patient WHERE id = ?",
                        String.class,
                        patientId),
                "患者状态改为已死亡");
    }

    @Test
    void backfillValidatesSourceAndVisitDate() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "B3", today.minusDays(20));
        enrollCurrentTask("demoa", patientId, planId);

        assertEquals(
                40001,
                code(backfillCall(doctor, patientId, "REMOTE", today.minusDays(10))),
                "远程监测随 E21");
        assertEquals(
                40001,
                code(backfillCall(doctor, patientId, "BACKFILL", today)),
                "补录随访日期必须是过去的日期（当天走任务 / 工作台）");
        assertEquals(
                40001,
                code(backfillCall(doctor, patientId, "BACKFILL", today.plusDays(1))),
                "未来日期拒绝");
        Map<String, Object> noDate = new HashMap<>();
        noDate.put("source", "BACKFILL");
        assertEquals(
                40001,
                code(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/records",
                                doctor,
                                noDate)),
                "实际随访日期必填（A-FU-014）");
    }

    // ---------------------------------------------------------------- 权限 / 开关 / 范围 / 双租户

    @Test
    void backfillPermissionModuleAndScopeGuards() {
        LocalDate today = today();
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(admin);
        long patientId = registerPatient("demoa", doctor, "B4", today.minusDays(20));
        enrollCurrentTask("demoa", patientId, planId);
        long outWard = ensureOtherWard();
        long outScope = registerPatientInWard("demoa", admin, "B5", today.minusDays(20), outWard);

        // 越范围患者 40400（不暴露存在）
        assertEquals(
                40400,
                code(backfillCall(doctor, outScope, "BACKFILL", today.minusDays(10))),
                "越范围患者 40400");
        // 无 followup:record:edit 40300（只有任务查看）
        String viewer =
                newUserToken("IT-U-E15T07-VIEW", roleWithMenus("IT-R-E15T07-VIEW", List.of(22L)));
        assertEquals(
                40300,
                code(backfillCall(viewer, patientId, "BACKFILL", today.minusDays(10))),
                "无录入权限 40300");
        // 模块关闭 40301
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    code(backfillCall(doctor, patientId, "BACKFILL", today.minusDays(10))),
                    "模块关闭 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
        // 时间轴越范围 40400
        assertEquals(
                40400,
                code(
                        get(
                                "/api/hospital/followup/patients/"
                                        + outScope
                                        + "/followup-timeline",
                                doctor)),
                "越范围患者时间轴 40400");
    }

    @Test
    void timelineInvisibleAcrossTenants() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "B6", today.minusDays(20));
        enrollCurrentTask("demoa", patientId, planId);

        String demobToken = hospitalToken("demob", "doctor.demo");
        assertEquals(
                40400,
                code(
                        get(
                                "/api/hospital/followup/patients/"
                                        + patientId
                                        + "/followup-timeline",
                                demobToken)),
                "demoa 患者在 demob 不可见（每院独立库）");
    }

    // ---------------------------------------------------------------- 造数与工具

    /** 发布「循环 365 天 + 偏移 20 天」计划：起算 T−20 → 第 1 次计划日期 = 今天（窗口 ±3 已开启）。 */
    private long publishedCyclePlan(String token) {
        String code = PLAN_CODE + "-" + System.nanoTime() % 100000;
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 补录时间轴计划（" + code + "）");
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
        return register(tenant, token, LEGACY + "-" + suffix, defaultWardId(tenant), discharge);
    }

    private long registerPatientInWard(
            String tenant, String token, String suffix, LocalDate discharge, long wardId) {
        return register(tenant, token, LEGACY + "-" + suffix, wardId, discharge);
    }

    private long register(
            String tenant, String token, String legacyNo, long wardId, LocalDate discharge) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", legacyNo);
        body.put("name", "周补录（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1956-02-02");
        body.put("wardId", wardId);
        body.put("admissionDate", discharge.minusDays(3).toString());
        body.put("surgeryDate", discharge.minusDays(2).toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", legacyNo);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, code(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    /** 入组（E14-T05 入组即建当期任务：起算 T−20 + 偏移 20 → 计划日期 = 今天、窗口 ±3 已开启）。 */
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

    /** 经任务开始录入并提交（时间轴「提交后进度 1/1」造数）。 */
    private Map<?, ?> submitTaskRecord(String token, long taskId) {
        String recordId =
                String.valueOf(
                        data(post(
                                        "/api/hospital/followup/tasks/" + taskId + "/start",
                                        token,
                                        Map.of()))
                                .get("recordId"));
        return submitRecord(token, recordId, today(), 0);
    }

    /** 建补录草稿（HTTP 正口）。 */
    private String createBackfill(String token, long patientId, LocalDate visitDate) {
        Map<?, ?> created = backfillCall(token, patientId, "BACKFILL", visitDate);
        assertEquals(0, code(created), "补录创建失败：" + created);
        return String.valueOf(data(created).get("recordId"));
    }

    /** 提交记录（A_ANNUAL_FU 必填：f214 随访日期、f215 是否死亡、f213 已加微信；f215=1 时 条件必填 f216 死亡日期、f218 死亡原因）。 */
    private Map<?, ?> submitRecord(String token, String recordId, LocalDate visitDate, int death) {
        Map<String, Object> data = new HashMap<>();
        data.put("f214", visitDate.toString());
        data.put("f215", death);
        data.put("f213", 1);
        if (death == 1) {
            data.put("f216", visitDate.toString());
            data.put("f218", "心梗死亡（示例）");
        }
        return post(
                "/api/hospital/followup/records/" + recordId + "/submit",
                token,
                Map.of("baseVersion", 0, "data", data));
    }

    private Map<?, ?> backfillCall(
            String token, long patientId, String source, LocalDate visitDate) {
        Map<String, Object> body = new HashMap<>();
        body.put("source", source);
        body.put("visitDate", visitDate.toString());
        return post("/api/hospital/followup/patients/" + patientId + "/records", token, body);
    }

    private Map<?, ?> timelineOf(long patientId, String token) {
        Map<?, ?> response =
                get("/api/hospital/followup/patients/" + patientId + "/followup-timeline", token);
        assertEquals(0, code(response), "时间轴失败：" + response);
        return data(response);
    }

    private static Map<?, ?> followupNode(Map<?, ?> timeline) {
        return nodeOf(timeline, "FOLLOWUP");
    }

    private static Map<?, ?> backfillNode(Map<?, ?> timeline) {
        return nodeOf(timeline, "BACKFILL");
    }

    private static Map<?, ?> plannedNode(Map<?, ?> timeline) {
        return nodeOf(timeline, "PLANNED");
    }

    private static Map<?, ?> nodeOf(Map<?, ?> timeline, String kind) {
        List<Map<?, ?>> items = (List<Map<?, ?>>) timeline.get("items");
        return items.stream()
                .filter(item -> kind.equals(item.get("kind")))
                .findFirst()
                .orElse(null);
    }

    private long roleWithMenus(String roleCode, List<Long> menuIds) {
        return runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "INSERT INTO sys_role (code, name, built_in, status) VALUES (?, 'IT 角色（示例）', 0, 'ENABLED')",
                            roleCode);
                    long roleId =
                            jdbc.queryForObject(
                                    "SELECT id FROM sys_role WHERE code = ?", Long.class, roleCode);
                    for (Long menuId : menuIds) {
                        jdbc.update(
                                "INSERT INTO sys_role_menu (role_id, menu_id) VALUES (?, ?)",
                                roleId,
                                menuId);
                    }
                    jdbc.update(
                            "INSERT INTO sys_role_scope (role_id, scope_type) VALUES (?, 'ALL')",
                            roleId);
                    return roleId;
                });
    }

    private String newUserToken(String username, long roleId) {
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
                        hospitalToken("demoa", "nurse.demo", PASSWORD),
                        Map.of(
                                "username",
                                username,
                                "displayName",
                                "IT 只读用户（示例）",
                                "phone",
                                "13900001351",
                                "mainDeptId",
                                deptId,
                                "roleIds",
                                List.of(roleId)));
        assertEquals(0, code(created), "建用户失败：" + created);
        String temporary = String.valueOf(data(created).get("temporaryPassword"));
        String token = hospitalToken("demoa", username, temporary);
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/auth/password",
                                token,
                                Map.of("oldPassword", temporary, "newPassword", PASSWORD))),
                "改密失败");
        return hospitalToken("demoa", username, PASSWORD);
    }

    /** 越范围病区（医生数据范围外；存在即复用，cleanState 删除）。 */
    private long ensureOtherWard() {
        return runInTenant(
                "demoa",
                () -> {
                    Long existing =
                            jdbc.query(
                                    "SELECT id FROM ward WHERE code = 'W-IT-E15T07'",
                                    (org.springframework.jdbc.core.ResultSetExtractor<Long>)
                                            rs -> rs.next() ? rs.getLong(1) : null);
                    if (existing != null) {
                        return existing;
                    }
                    jdbc.update(
                            "INSERT INTO ward (code, name, status)"
                                    + " VALUES ('W-IT-E15T07', 'IT 越范围病区（示例）', 'ENABLED')");
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = 'W-IT-E15T07'", Long.class);
                });
    }

    private String taskStatusOf(String tenant, long taskId) {
        return jdbcQuery(tenant, "SELECT status FROM fu_task WHERE id = ?", String.class, taskId);
    }

    private <T> T jdbcQuery(String tenant, String sql, Class<T> type, Object... args) {
        return runInTenant(tenant, () -> jdbc.queryForObject(sql, type, args));
    }

    private long defaultWardId(String tenant) {
        return runInTenant(
                tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    private static LocalDate today() {
        return LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }

    private String hospitalToken(String tenant, String username) {
        return hospitalToken(tenant, username, PASSWORD);
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
}
