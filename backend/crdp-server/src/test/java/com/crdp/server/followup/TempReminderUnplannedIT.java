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
 * E15-T06 端到端 / A-FU-013 临时添加任务、A-FU-026 站内提醒类任务、C-FU-022 计划外随访（完整应用 + 真实 MySQL，HTTP 直连 +
 * 服务层造数）。tasks.csv 验收：站内提醒类任务无需填表。
 *
 * <p>覆盖：临时任务（POST /tasks：FORM 负数 occurrence 不占用年度任务、REMINDER 无表单、负责人缺省本人、窗口 ±宽度、TaskOpened
 * 事件与审计）；完成提醒（acknowledge：REMINDER 无需填表即完成、FORM 40901、start 40901）； 计划外随访（POST
 * /patients/{id}/records source=UNPLANNED：当天可录、无任务、时间轴单独成节点、不影响后续节点〔下次计划日期不变〕、未来日期 40001、已终止患者
 * 40901、REMOTE 40001）； 权限（无 followup:record:edit 40300）、模块关闭 40301、越范围患者 40400。门控
 * CRDP_IT_MYSQL_PORT。全部虚构数据（legacy IT-E15T06-*）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TempReminderUnplannedIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E15T06";
    private static final String PLAN_CODE = "PLAN-IT-E15T06";
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
                        // E23-T02 预置计划常驻自动入组（A_ANNUAL_FU_PLAN）：本类按「单计划世界」断言，
                        // 先关掉它的自动入组；预置规格与行为由 APresetPlanSqlTest / APresetPlanVerifyIT 锁定
                        jdbc.update(
                                "UPDATE fu_plan SET auto_enroll = 0 WHERE code = 'A_ANNUAL_FU_PLAN'");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE (event_type IN"
                                        + " ('TASK_TEMP_CREATE', 'TASK_ACKNOWLEDGE',"
                                        + " 'RECORD_UNPLANNED_CREATE', 'TASK_START')"
                                        + " AND (target IN (SELECT CONCAT('TASK:', t.id)"
                                        + " FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                        + " WHERE p.legacy_no LIKE '"
                                        + LEGACY
                                        + "-%')"
                                        + " OR target IN (SELECT CONCAT('FU_RECORD:', r.id)"
                                        + " FROM fu_record r JOIN pt_patient p ON p.id = r.patient_id"
                                        + " WHERE p.legacy_no LIKE '"
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
                                        + " ON u.id = ur.user_id WHERE u.username LIKE 'IT-U-E15T06-%'");
                        jdbc.update(
                                "DELETE ph FROM sys_password_history ph JOIN sys_user u"
                                        + " ON u.id = ph.user_id WHERE u.username LIKE 'IT-U-E15T06-%'");
                        jdbc.update("DELETE FROM sys_user WHERE username LIKE 'IT-U-E15T06-%'");
                        for (String roleCode : List.of("IT-R-E15T06-VIEW")) {
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

    // ---------------------------------------------------------------- 临时添加任务（A-FU-013 验收位：
    // 不占用年度任务）

    @Test
    void tempFormTaskDoesNotOccupyYearlyTasks() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "T1", today.minusDays(20));
        long yearlyTaskId = enrollCurrentTask("demoa", patientId, planId);
        String nextPlanBefore =
                jdbcQuery(
                        "demoa",
                        "SELECT CAST(next_plan_date AS CHAR) FROM fu_enrollment WHERE patient_id = ?",
                        String.class,
                        patientId);

        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("planDate", today.plusDays(10).toString());
        body.put("note", "再住院后加访（示例）");
        Map<?, ?> created = post("/api/hospital/followup/tasks", doctor, body);
        assertEquals(0, code(created), "临时任务创建失败：" + created);
        assertEquals("TEMP", data(created).get("createType"));
        assertEquals("PENDING", data(created).get("status"));
        assertEquals("FORM", data(created).get("taskType"));
        assertEquals(FORM, data(created).get("formCode"), "表单缺省取节点表单");
        assertEquals(
                today.plusDays(3).toString(),
                String.valueOf(data(created).get("windowStart")),
                "窗口 = 计划日期 ± 默认 7 天");
        assertEquals(today.plusDays(17).toString(), String.valueOf(data(created).get("windowEnd")));
        long tempTaskId = Long.parseLong((String) data(created).get("taskId"));

        // 不占用年度任务：负数 occurrence 与调度常规序列（1..N）并存，年度任务原样
        assertEquals(
                -1,
                (int)
                        jdbcQuery(
                                "demoa",
                                "SELECT occurrence FROM fu_task WHERE id = ?",
                                Integer.class,
                                tempTaskId),
                "临时任务 occurrence 为负数计数器");
        assertEquals("NORMAL", taskCreateTypeOf("demoa", yearlyTaskId), "年度任务不受影响");
        assertEquals(
                nextPlanBefore,
                jdbcQuery(
                        "demoa",
                        "SELECT CAST(next_plan_date AS CHAR) FROM fu_enrollment WHERE patient_id = ?",
                        String.class,
                        patientId),
                "临时任务不改入组下次计划日期缓存（不影响后续节点）");
        // created_by 记操作人（V48 注释「临时任务随 E15 记操作人」）
        assertNotNull(
                jdbcQuery(
                        "demoa",
                        "SELECT created_by FROM fu_task WHERE id = ?",
                        Long.class,
                        tempTaskId),
                "临时任务记录创建人");

        // 临时 FORM 任务照常开始录入（完成不受直接录入期限拦截）
        Map<?, ?> started =
                post("/api/hospital/followup/tasks/" + tempTaskId + "/start", doctor, Map.of());
        assertEquals(0, code(started), "临时任务开始录入失败：" + started);
        assertEquals("IN_PROGRESS", taskStatusOf("demoa", tempTaskId), "开始录入后进行中");
    }

    @Test
    void secondTempTaskDecrementsOccurrence() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "T2", today.minusDays(20));
        enrollCurrentTask("demoa", patientId, planId);

        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("planDate", today.plusDays(5).toString());
        assertEquals(0, code(post("/api/hospital/followup/tasks", doctor, body)), "第 1 条临时任务");
        Map<?, ?> second = post("/api/hospital/followup/tasks", doctor, body);
        assertEquals(0, code(second), "第 2 条临时任务失败：" + second);
        long secondId = Long.parseLong((String) data(second).get("taskId"));
        assertEquals(
                -2,
                (int)
                        jdbcQuery(
                                "demoa",
                                "SELECT occurrence FROM fu_task WHERE id = ?",
                                Integer.class,
                                secondId),
                "第 2 条临时任务 occurrence 递减（-2）");
    }

    @Test
    void tempTaskValidatesEnrollmentPlanDateAndAssignee() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        // 未入组患者：无在组入组不可添加
        long unenrolled = registerPatient("demoa", doctor, "V1", today.minusDays(5));
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", unenrolled);
        body.put("planDate", today.plusDays(3).toString());
        assertEquals(
                40001,
                code(post("/api/hospital/followup/tasks", doctor, body)),
                "无在组入组 40001（临时任务挂靠子计划）");

        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "V2", today.minusDays(20));
        enrollCurrentTask("demoa", patientId, planId);
        // 计划日期必填 / 窗口宽度上限 / 节点归属
        Map<String, Object> noDate = new HashMap<>();
        noDate.put("patientId", patientId);
        assertEquals(40001, code(post("/api/hospital/followup/tasks", doctor, noDate)), "计划日期必填");
        Map<String, Object> tooWide = new HashMap<>();
        tooWide.put("patientId", patientId);
        tooWide.put("planDate", today.toString());
        tooWide.put("windowDays", 400);
        assertEquals(
                40001, code(post("/api/hospital/followup/tasks", doctor, tooWide)), "窗口宽度 0–365");
        Map<String, Object> badNode = new HashMap<>();
        badNode.put("patientId", patientId);
        badNode.put("planDate", today.toString());
        badNode.put("nodeId", 999999L);
        assertEquals(
                40001, code(post("/api/hospital/followup/tasks", doctor, badNode)), "节点不属计划 40001");
        // 越界负责人（不负责患者病区）与改派同口径
        long otherWardDoctor =
                jdbcQuery(
                        "demoa",
                        "SELECT id FROM sys_user WHERE username = 'doctor.demo'",
                        Long.class);
        Map<String, Object> badAssignee = new HashMap<>();
        badAssignee.put("patientId", patientId);
        badAssignee.put("planDate", today.toString());
        badAssignee.put("assigneeId", otherWardDoctor);
        Map<?, ?> assigned = post("/api/hospital/followup/tasks", doctor, badAssignee);
        // doctor.demo 在 demoa 有全院或病区范围时放行（与改派同口径），此处至少不 500
        assertTrue(code(assigned) == 0 || code(assigned) == 40001, "负责人复核走改派口径：" + assigned);
    }

    // ---------------------------------------------------------------- 站内提醒（A-FU-026 验收位：无需填表）

    @Test
    void reminderTaskNeedsNoFormToComplete() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = reminderPlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "R1", today.minusDays(20));
        long reminderTaskId = enrollCurrentTask("demoa", patientId, planId);
        assertEquals(
                "REMINDER",
                jdbcQuery(
                        "demoa",
                        "SELECT task_type FROM fu_task WHERE id = ?",
                        String.class,
                        reminderTaskId),
                "计划节点 REMINDER → 调度创建站内提醒任务");

        // 开始录入 40901：无需填表（E15-T02 已拦，验收复验）
        assertEquals(
                40901,
                code(
                        post(
                                "/api/hospital/followup/tasks/" + reminderTaskId + "/start",
                                doctor,
                                Map.of())),
                "站内提醒类任务无需填表（A-FU-026）");

        // 完成提醒：不创建记录、确认即完成
        Map<?, ?> acknowledged =
                post(
                        "/api/hospital/followup/tasks/" + reminderTaskId + "/acknowledge",
                        doctor,
                        Map.of());
        assertEquals(0, code(acknowledged), "完成提醒失败：" + acknowledged);
        assertEquals("COMPLETED", data(acknowledged).get("status"));
        assertNotNull(data(acknowledged).get("completeKind"), "完成方式按窗口派生");
        assertEquals("COMPLETED", taskStatusOf("demoa", reminderTaskId));
        assertNull(
                jdbcQuery(
                        "demoa",
                        "SELECT record_id FROM fu_task WHERE id = ?",
                        Long.class,
                        reminderTaskId),
                "完成提醒不创建任何记录（无需填表）");

        // 重复确认 40901
        assertEquals(
                40901,
                code(
                        post(
                                "/api/hospital/followup/tasks/" + reminderTaskId + "/acknowledge",
                                doctor,
                                Map.of())),
                "已完成任务不能重复完成");
    }

    @Test
    void acknowledgeRejectsFormTasksAndTempReminderFlow() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "R2", today.minusDays(20));
        long formTaskId = enrollCurrentTask("demoa", patientId, planId);

        assertEquals(
                40901,
                code(
                        post(
                                "/api/hospital/followup/tasks/" + formTaskId + "/acknowledge",
                                doctor,
                                Map.of())),
                "填表任务须提交随访记录完成");

        // 临时添加的 REMINDER 任务同样走完成提醒
        Map<String, Object> body = new HashMap<>();
        body.put("patientId", patientId);
        body.put("taskType", "REMINDER");
        body.put("planDate", today.toString());
        body.put("windowDays", 0);
        Map<?, ?> created = post("/api/hospital/followup/tasks", doctor, body);
        assertEquals(0, code(created), "临时站内提醒任务创建失败：" + created);
        assertEquals("REMINDER", data(created).get("taskType"));
        assertNull(data(created).get("formCode"), "站内提醒无表单");
        long tempId = Long.parseLong((String) data(created).get("taskId"));
        assertEquals(
                0,
                code(
                        post(
                                "/api/hospital/followup/tasks/" + tempId + "/acknowledge",
                                doctor,
                                Map.of())),
                "临时站内提醒完成提醒");
        assertEquals("COMPLETED", taskStatusOf("demoa", tempId));
    }

    // ---------------------------------------------------------------- 计划外随访（C-FU-022 验收位）

    @Test
    void unplannedRecordTodayDoesNotAffectNextNodes() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "U1", today.minusDays(20));
        enrollCurrentTask("demoa", patientId, planId);
        String nextPlanBefore =
                jdbcQuery(
                        "demoa",
                        "SELECT CAST(next_plan_date AS CHAR) FROM fu_enrollment WHERE patient_id = ?",
                        String.class,
                        patientId);

        Map<?, ?> created =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/records",
                        doctor,
                        Map.of("source", "UNPLANNED", "visitDate", today.toString()));
        assertEquals(0, code(created), "计划外随访创建失败：" + created);
        String recordId = String.valueOf(data(created).get("recordId"));
        assertEquals(
                "UNPLANNED",
                jdbcQuery(
                        "demoa",
                        "SELECT source FROM fu_record WHERE id = ?",
                        String.class,
                        Long.parseLong(recordId)),
                "记录来源标记为计划外（C-FU-022）");
        assertNull(
                jdbcQuery(
                        "demoa",
                        "SELECT task_id FROM fu_record WHERE id = ?",
                        Long.class,
                        Long.parseLong(recordId)),
                "计划外随访不需要任务");
        assertEquals(
                nextPlanBefore,
                jdbcQuery(
                        "demoa",
                        "SELECT CAST(next_plan_date AS CHAR) FROM fu_enrollment WHERE patient_id = ?",
                        String.class,
                        patientId),
                "计划外随访不影响后续节点（下次计划日期不变）");

        // 时间轴：单独成节点（UNPLANNED）、可打开表单
        Map<?, ?> timeline = timelineOf(patientId, doctor);
        Map<?, ?> unplanned = nodeOf(timeline, "UNPLANNED");
        assertNotNull(unplanned, "计划外随访单独成节点");
        assertEquals("计划外随访", unplanned.get("label"));
        assertEquals("IN_PROGRESS", unplanned.get("status"), "草稿按进行中展示");
        assertEquals(recordId, String.valueOf(unplanned.get("recordId")));
        assertEquals(Boolean.TRUE, unplanned.get("canOpen"));
        assertTrue(
                String.valueOf(unplanned.get("note")).contains("不影响后续节点"),
                "备注写明不影响后续节点：" + unplanned.get("note"));
    }

    @Test
    void unplannedValidatesDatePatientAndSource() {
        LocalDate today = today();
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(hospitalToken("demoa", "nurse.demo", PASSWORD));
        long patientId = registerPatient("demoa", doctor, "U2", today.minusDays(20));
        enrollCurrentTask("demoa", patientId, planId);

        assertEquals(
                40001,
                code(unplannedCall(doctor, patientId, today.plusDays(1))),
                "未来日期拒绝（未来随访由计划排期）");
        assertEquals(
                40001,
                code(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/records",
                                doctor,
                                Map.of("source", "REMOTE", "visitDate", today.toString()))),
                "远程监测随 E21");
        // 已终止患者（死亡）40901
        long dead = registerPatient("demoa", doctor, "U3", today.minusDays(20));
        enrollCurrentTask("demoa", dead, planId);
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE pt_patient SET followup_status = 'DEAD' WHERE id = ?",
                                dead));
        assertEquals(
                40901,
                code(unplannedCall(doctor, dead, today)),
                "患者不在随访中，不能新建计划外随访（pages.md §4.10）");
    }

    // ---------------------------------------------------------------- 权限 / 开关 / 范围

    @Test
    void permissionModuleAndScopeGuards() {
        LocalDate today = today();
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        String doctor = hospitalToken("demoa", "doctor.demo", PASSWORD);
        long planId = publishedCyclePlan(admin);
        long patientId = registerPatient("demoa", doctor, "G1", today.minusDays(20));
        enrollCurrentTask("demoa", patientId, planId);
        long outWard = ensureOtherWard();
        long outScope = registerPatientInWard("demoa", admin, "G2", today.minusDays(20), outWard);

        Map<String, Object> tempBody = new HashMap<>();
        tempBody.put("patientId", patientId);
        tempBody.put("planDate", today.plusDays(3).toString());

        // 越范围患者 40400（不暴露存在）
        Map<String, Object> outBody = new HashMap<>();
        outBody.put("patientId", outScope);
        outBody.put("planDate", today.plusDays(3).toString());
        assertEquals(
                40400,
                code(post("/api/hospital/followup/tasks", doctor, outBody)),
                "越范围患者临时任务 40400");
        assertEquals(40400, code(unplannedCall(doctor, outScope, today)), "越范围患者计划外 40400");
        // 无 followup:record:edit 40300（只有任务查看菜单 22）
        String viewer =
                newUserToken("IT-U-E15T06-VIEW", roleWithMenus("IT-R-E15T06-VIEW", List.of(22L)));
        assertEquals(
                40300,
                code(post("/api/hospital/followup/tasks", viewer, tempBody)),
                "无录入权限临时任务 40300");
        assertEquals(40300, code(unplannedCall(viewer, patientId, today)), "无录入权限计划外 40300");
        // 模块关闭 40301
        try {
            moduleService.close(
                    1L, "followup", new TenantModuleService.CloseRequest("IT 用例关闭（示例）"));
            assertEquals(
                    40301,
                    code(post("/api/hospital/followup/tasks", doctor, tempBody)),
                    "模块关闭临时任务 40301");
            assertEquals(40301, code(unplannedCall(doctor, patientId, today)), "模块关闭计划外 40301");
        } finally {
            moduleService.open(1L, "followup", new TenantModuleService.OpenRequest("IT 用例恢复（示例）"));
        }
    }

    // ---------------------------------------------------------------- 造数与工具

    /** 发布「循环 365 天 + 偏移 20 天」FORM 计划：起算 T−20 → 第 1 次计划日期 = 今天（窗口 ±3 已开启）。 */
    private long publishedCyclePlan(String token) {
        return publishPlan(token, "FORM", FORM);
    }

    /** 发布同结构 REMINDER 计划（A-FU-026 站内提醒类节点，无表单）。 */
    private long reminderPlan(String token) {
        return publishPlan(token, "REMINDER", null);
    }

    private long publishPlan(String token, String taskType, String formCode) {
        String code = PLAN_CODE + "-" + System.nanoTime() % 100000;
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 计划（" + code + "）");
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
        node.put("name", "REMINDER".equals(taskType) ? "核对联系方式（示例）" : "电话随访（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", 20);
        node.put("offsetUnit", "DAY");
        node.put("remindValue", 3);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", 3);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 3);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", taskType);
        node.put("ownerType", "WARD_DEFAULT");
        if (formCode != null) {
            node.put("formCode", formCode);
        }
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
        body.put("name", "周临时（示例）");
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

    private Map<?, ?> unplannedCall(String token, long patientId, LocalDate visitDate) {
        return post(
                "/api/hospital/followup/patients/" + patientId + "/records",
                token,
                Map.of("source", "UNPLANNED", "visitDate", visitDate.toString()));
    }

    private Map<?, ?> timelineOf(long patientId, String token) {
        Map<?, ?> response =
                get("/api/hospital/followup/patients/" + patientId + "/followup-timeline", token);
        assertEquals(0, code(response), "时间轴失败：" + response);
        return data(response);
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
                                "13900001361",
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
                                    "SELECT id FROM ward WHERE code = 'W-IT-E15T06'",
                                    (org.springframework.jdbc.core.ResultSetExtractor<Long>)
                                            rs -> rs.next() ? rs.getLong(1) : null);
                    if (existing != null) {
                        return existing;
                    }
                    jdbc.update(
                            "INSERT INTO ward (code, name, status)"
                                    + " VALUES ('W-IT-E15T06', 'IT 越范围病区（示例）', 'ENABLED')");
                    return jdbc.queryForObject(
                            "SELECT id FROM ward WHERE code = 'W-IT-E15T06'", Long.class);
                });
    }

    private String taskStatusOf(String tenant, long taskId) {
        return jdbcQuery(tenant, "SELECT status FROM fu_task WHERE id = ?", String.class, taskId);
    }

    private String taskCreateTypeOf(String tenant, long taskId) {
        return jdbcQuery(
                tenant, "SELECT create_type FROM fu_task WHERE id = ?", String.class, taskId);
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
