package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.crdp.followup.plan.app.FollowupTerminationService;
import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.Instant;
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
 * E14-T06 端到端（A-PAT-011、A-FU-007、A-FU-008、C-FU-021；tasks.csv 验收「告知已故后未开始任务全部终止」）。 场景：
 *
 * <ol>
 *   <li>暂停与恢复补偿（A-FU-008 / A 的 Q32）：暂停期间调度不建任务；恢复时暂停区间内本应开启且窗口已结束的次数补建为 「暂停期间未执行」（PAUSED_SKIPPED +
 *       SKIPPED_SUSPENDED，不发任务开启事件），窗口未结束的当期任务正常创建； 患者级状态随访中 → 已暂停 → 随访中同步流转（pt_status_history
 *       只追加）；
 *   <li>停用补偿（PLT-TEN-017 / tech-plan §8）：作业未执行期间（模拟租户停用后恢复补跑）本应开启且窗口已结束的次数
 *       补建为「停用期间未执行」（DISABLED_SKIPPED）；
 *   <li>移出（A-PAT-011 须填原因）：未完成任务自动终止（终止原因 = 移除原因）、患者级「已终止」、之后不再创建；
 *   <li>死亡自动终止（A-FU-007，验收）：告知已故后未开始任务全部终止（原因「死亡」）、患者已死亡、状态时间线 DEAD 行、 之后调度不再创建任务。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 PLAN-IT-E14T06 前缀、病案号 IT-E14T06-
 * 前缀）。全部虚构数据。节点形态：零宽窗口（窗口前 0 后 0、当天提醒、周期 10 天、偏移 20 天），使「窗口已结束」可精确 控制；暂停起始时间通过回拨
 * fu_enrollment.pause_from 模拟「已暂停数日」（与回拨 created_at 同口径的时间旅行）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EnrollmentLifecycleIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String MRN = "IT-E14T06";
    private static final String PLAN_CODE = "PLAN-IT-E14T06";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private FollowupTaskCreationService creationService;
    @Autowired private FollowupTerminationService terminationService;

    @BeforeEach
    void cleanState() {
        runInTenant(
                "demoa",
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE (event_type LIKE 'PLAN_%'"
                                    + " AND (detail LIKE '%"
                                    + PLAN_CODE
                                    + "%'"
                                    + " OR target IN (SELECT CONCAT('PLAN:', id) FROM fu_plan"
                                    + " WHERE code LIKE '"
                                    + PLAN_CODE
                                    + "%')))"
                                    + " OR (action IN ('TASK_CREATE', 'ENROLLMENT_PAUSE',"
                                    + " 'ENROLLMENT_RESUME', 'ENROLLMENT_REMOVE')"
                                    + " AND detail LIKE '%每日调度%')"
                                    + " OR (action = 'ENROLLMENT_PAUSE'"
                                    + " AND target IN (SELECT CONCAT('ENROLLMENT:', e.id)"
                                    + " FROM fu_enrollment e JOIN pt_patient p"
                                    + " ON p.id = e.patient_id WHERE p.legacy_no LIKE '"
                                    + MRN
                                    + "-%'))"
                                    + " OR (action IN ('ENROLLMENT_RESUME', 'ENROLLMENT_REMOVE')"
                                    + " AND target IN (SELECT CONCAT('ENROLLMENT:', e.id)"
                                    + " FROM fu_enrollment e JOIN pt_patient p"
                                    + " ON p.id = e.patient_id WHERE p.legacy_no LIKE '"
                                    + MRN
                                    + "-%'))"
                                    + " OR (action = 'PATIENT_DEATH_TERMINATE'"
                                    + " AND target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                    + " WHERE legacy_no LIKE '"
                                    + MRN
                                    + "-%'))");
                    List<Long> patientIds =
                            jdbc.queryForList(
                                    "SELECT id FROM pt_patient WHERE legacy_no LIKE ?",
                                    Long.class,
                                    MRN + "-%");
                    for (Long patientId : patientIds) {
                        jdbc.update(
                                "DELETE FROM sys_outbox WHERE event_type = 'TaskOpened'"
                                        + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                                patientId);
                        jdbc.update(
                                "DELETE FROM pt_status_history WHERE patient_id = ?", patientId);
                    }
                    jdbc.update(
                            "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            MRN + "-%");
                    jdbc.update("DELETE FROM job_tenant_run WHERE job_code = 'fu-task-create'");
                    jdbc.update(
                            "DELETE e FROM fu_enrollment e JOIN pt_patient p"
                                    + " ON p.id = e.patient_id WHERE p.legacy_no LIKE ?",
                            MRN + "-%");
                    jdbc.update(
                            "DELETE n FROM fu_plan_node n JOIN fu_plan p ON p.id = n.plan_id"
                                    + " WHERE p.code LIKE ?",
                            PLAN_CODE + "%");
                    jdbc.update("DELETE FROM fu_plan WHERE code LIKE ?", PLAN_CODE + "%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", MRN + "-%");
                    jdbc.update("DELETE FROM ward WHERE code = 'W-IT-E14T06'");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 暂停 / 恢复补偿（A-FU-008、Q32）

    @Test
    void pauseBlocksSchedulingAndResumeBuildsPauseCompensation() {
        LocalDate today = today();
        String admin = adminToken();
        long planId = createPublishedZeroWindowPlan(admin, PLAN_CODE + "-P");
        long patientId = registerPatient(admin, "P1", today.minusDays(30));
        long enrollmentId = enrollBackdated(patientId, planId, today.minusDays(40));

        // 暂停前一天跑调度：未到开启日（第 1 次开启 = 基准 + 20 天 = T−10 …回拨入组后从缓存推进），不建任务
        creationService.runForDate(today.minusDays(12));
        assertEquals(0, taskCount(patientId), "开启日未到不创建");

        // HTTP 暂停（须填原因，A-PAT-011）；暂停起始回拨 12 天前，模拟「已暂停数日」
        Map<?, ?> paused =
                post(
                        "/api/hospital/followup/plans/"
                                + planId
                                + "/enrollments/"
                                + enrollmentId
                                + "/pause",
                        admin,
                        Map.of("reason", "外地随访（示例）"));
        assertEquals(0, codeOf(paused), "暂停失败：" + paused);
        assertEquals("PAUSED", data(paused).get("status"));
        backdatePauseFrom(enrollmentId, today.minusDays(12));
        assertEquals("PAUSED", patientFollowupStatus(patientId), "无其他在组入组时患者级状态同步「已暂停」");

        // 暂停期间调度不建任务（第 1 次开启日 T−10 已过、第 2 次 T 当天）
        creationService.runForDate(today);
        assertEquals(0, taskCount(patientId), "暂停期间不创建任务、不发通知（A-FU-008）");

        // HTTP 恢复：暂停区间 [T−12, T] 内第 1 次窗口 [T−10, T−10] 已结束 → 补建「暂停期间未执行」；
        // 第 2 次窗口 [T, T] 未结束 → 当期正常创建并写任务开启事件
        Map<?, ?> resumed =
                post(
                        "/api/hospital/followup/plans/"
                                + planId
                                + "/enrollments/"
                                + enrollmentId
                                + "/resume",
                        admin,
                        Map.of());
        assertEquals(0, codeOf(resumed), "恢复失败：" + resumed);
        assertEquals("ACTIVE", data(resumed).get("status"));
        assertEquals(1, ((Number) data(resumed).get("pausedSkipped")).intValue(), "补建暂停期间未执行 1 条");
        assertEquals(1, ((Number) data(resumed).get("normal")).intValue(), "当期任务 1 条");

        List<Map<String, Object>> tasks = tasksOf(patientId);
        assertEquals(2, tasks.size());
        Map<String, Object> first = tasks.get(0);
        assertEquals(1, ((Number) first.get("occurrence")).intValue());
        assertEquals("PAUSED_SKIPPED", first.get("create_type"), "暂停期间到期 → 暂停期间未执行（Q32）");
        assertEquals("SKIPPED_SUSPENDED", first.get("status"), "由医生选择补做或关闭");
        assertEquals(today.minusDays(10), date(first.get("window_end")));
        Map<String, Object> second = tasks.get(1);
        assertEquals(2, ((Number) second.get("occurrence")).intValue());
        assertEquals("NORMAL", second.get("create_type"), "窗口未结束 → 恢复后当期正常创建");
        assertEquals("PENDING", second.get("status"));
        assertEquals(1, taskOpenedEvents(patientId), "只有当期任务发任务开启事件（暂停期间未执行不进通知）");
        assertEquals("ACTIVE", patientFollowupStatus(patientId), "恢复后患者级回「随访中」");

        // 状态时间线只追加：登记 ACTIVE → 暂停 PAUSED → 恢复 ACTIVE（source=PLAN）
        List<String> timeline =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForList(
                                        "SELECT CONCAT(status, ':', source) FROM pt_status_history"
                                                + " WHERE patient_id = ? ORDER BY id",
                                        String.class,
                                        patientId));
        assertEquals(List.of("ACTIVE:REGISTRATION", "PAUSED:PLAN", "ACTIVE:PLAN"), timeline);

        // 幂等：恢复后调度重放不重复创建（uk 入组 + 节点 + 第 N 次）
        creationService.runForDate(today);
        assertEquals(2, taskCount(patientId));
    }

    // ---------------------------------------------------------------- 停用补偿（PLT-TEN-017）

    @Test
    void catchUpMarksEndedWindowAsDisabledSkipped() {
        LocalDate today = today();
        String admin = adminToken();
        long planId = createPublishedZeroWindowPlan(admin, PLAN_CODE + "-D");
        long patientId = registerPatient(admin, "D1", today.minusDays(30));
        enrollBackdated(patientId, planId, today.minusDays(40));

        // 从未暂停：第 1 次开启日 T−10、窗口 [T−10, T−10] 已在本次运行目标逻辑日前结束
        // （模拟租户停用 / 作业未执行期间本应执行）→ 补建「停用期间未执行」；第 2 次 [T, T] 未结束 → NORMAL
        creationService.runForDate(today);

        List<Map<String, Object>> tasks = tasksOf(patientId);
        assertEquals(2, tasks.size());
        assertEquals(
                "DISABLED_SKIPPED",
                tasks.get(0).get("create_type"),
                "窗口已结束 → 停用期间未执行（PLT-TEN-017）");
        assertEquals("SKIPPED_SUSPENDED", tasks.get(0).get("status"));
        assertEquals("NORMAL", tasks.get(1).get("create_type"));
        assertEquals(1, taskOpenedEvents(patientId), "停用期间未执行不发任务开启事件；恢复后由人工选择补做或关闭");
        assertEquals("ACTIVE", patientFollowupStatus(patientId), "停用补偿不动患者级状态");
    }

    // ---------------------------------------------------------------- 移出（A-FU-008 / A-PAT-011）

    @Test
    void removeTerminatesUnfinishedTasksAndStopsCreation() {
        LocalDate today = today();
        String admin = adminToken();
        long planId = createPublishedZeroWindowPlan(admin, PLAN_CODE + "-R");
        long patientId = registerPatient(admin, "R1", today.minusDays(30));
        long enrollmentId = enrollBackdated(patientId, planId, today.minusDays(40));
        creationService.runForDate(today);
        assertEquals(2, taskCount(patientId));

        Map<?, ?> removed =
                post(
                        "/api/hospital/followup/plans/"
                                + planId
                                + "/enrollments/"
                                + enrollmentId
                                + "/remove",
                        admin,
                        Map.of("reason", "拒绝随访（示例）"));
        assertEquals(0, codeOf(removed), "移出失败：" + removed);
        assertEquals("REMOVED", data(removed).get("status"));
        assertEquals(
                2, ((Number) data(removed).get("terminated")).intValue(), "未开始任务全部终止（待随访 + 期间未执行）");

        List<Map<String, Object>> tasks = tasksOf(patientId);
        for (Map<String, Object> task : tasks) {
            assertEquals("TERMINATED", task.get("status"), "未完成任务自动终止（A-FU-008）");
            assertEquals("拒绝随访（示例）", task.get("terminate_reason"), "终止原因 = 移除原因");
            assertNotNull(task.get("terminated_at"));
        }
        assertEquals("TERMINATED", patientFollowupStatus(patientId), "无其他在组入组 → 患者级「已终止」");
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM pt_status_history"
                                                + " WHERE patient_id = ? AND status = 'TERMINATED'",
                                        Integer.class,
                                        patientId)),
                "状态时间线落「已终止」行");

        // 之后不再创建：第 3 次开启日 T+10，跑到 T+15 也不建
        creationService.runForDate(today.plusDays(15));
        assertEquals(2, taskCount(patientId), "移出后不再创建任务");

        // 越范围患者 40400（不暴露存在）：病区受限医生（W-DEMO-1）操作其他病区患者的入组
        long otherWardPatient = registerPatientInOtherWard(admin, "R2", today.minusDays(30));
        long otherEnrollment = enrollBackdated(otherWardPatient, planId, today.minusDays(40));
        String doctor = doctorToken();
        assertEquals(
                40400,
                codeOf(
                        post(
                                "/api/hospital/followup/plans/"
                                        + planId
                                        + "/enrollments/"
                                        + otherEnrollment
                                        + "/pause",
                                doctor,
                                Map.of("reason", "其他（示例）"))),
                "越范围患者按不存在处理（40400）");
        // 原因必填（A-PAT-011）
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/plans/"
                                        + planId
                                        + "/enrollments/"
                                        + otherEnrollment
                                        + "/remove",
                                admin,
                                Map.of("reason", " "))),
                "移出须填原因");
    }

    // ---------------------------------------------------------------- 死亡自动终止（验收）

    @Test
    void informedDeceasedTerminatesAllUnstartedTasksAndBlocksFutureOnes() {
        LocalDate today = today();
        String admin = adminToken();
        long planId = createPublishedZeroWindowPlan(admin, PLAN_CODE + "-X");
        long patientId = registerPatient(admin, "X1", today.minusDays(30));
        enrollBackdated(patientId, planId, today.minusDays(40));
        creationService.runForDate(today);
        assertEquals(2, taskCount(patientId), "停用补偿 1 条 + 当期 1 条");
        assertEquals("PENDING", tasksOf(patientId).get(1).get("status"), "当期任务未开始（待随访）");

        // 告知已故（A-FU-006 联系结果；HTTP 触发随 E15-T04，本任务落领域入口）
        FollowupTerminationService.DeathTerminationView view =
                runInTenant(
                        "demoa",
                        () -> terminationService.terminateOnDeath(patientId, "告知已故（示例）", null));

        assertEquals("DEAD", view.status());
        assertEquals(2, view.terminatedTasks(), "告知已故后未开始任务全部终止（验收）");
        for (Map<String, Object> task : tasksOf(patientId)) {
            assertEquals("TERMINATED", task.get("status"));
            assertEquals("死亡", task.get("terminate_reason"), "终止原因「死亡」（A 规格任务关键字段）");
        }
        assertEquals("DEAD", patientFollowupStatus(patientId), "患者状态改为已死亡（A-FU-007）");
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM pt_status_history"
                                                + " WHERE patient_id = ? AND status = 'DEAD'",
                                        Integer.class,
                                        patientId)),
                "状态时间线落 DEAD 行（source=SYSTEM）");
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log"
                                                + " WHERE action = 'PATIENT_DEATH_TERMINATE'"
                                                + " AND target = CONCAT('PATIENT:', ?)",
                                        Integer.class,
                                        patientId)),
                "死亡终止写一条审计");

        // 之后不再创建任务（A-FU-007）：第 3 次开启日 T+10，跑到 T+15 也不建
        creationService.runForDate(today.plusDays(15));
        assertEquals(2, taskCount(patientId), "死亡后调度不再创建任务");

        // 已死亡幂等：再次触发不重复写时间线 / 审计
        runInTenant(
                "demoa", () -> terminationService.terminateOnDeath(patientId, "告知已故（示例）", null));
        assertEquals(
                1,
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM pt_status_history"
                                                + " WHERE patient_id = ? AND status = 'DEAD'",
                                        Integer.class,
                                        patientId)),
                "已死亡幂等，不重复写时间线");

        // 已死亡患者不能恢复暂停的入组（A-FU-007 之后不再随访）
        long pausedPatient = registerPatient(admin, "X2", today.minusDays(30));
        long pausedEnrollment = enrollBackdated(pausedPatient, planId, today.minusDays(40));
        post(
                "/api/hospital/followup/plans/"
                        + planId
                        + "/enrollments/"
                        + pausedEnrollment
                        + "/pause",
                admin,
                Map.of("reason", "其他（示例）"));
        runInTenant(
                "demoa",
                () -> terminationService.terminateOnDeath(pausedPatient, "告知已故（示例）", null));
        assertEquals(
                40901,
                codeOf(
                        post(
                                "/api/hospital/followup/plans/"
                                        + planId
                                        + "/enrollments/"
                                        + pausedEnrollment
                                        + "/resume",
                                admin,
                                Map.of())),
                "已死亡患者的入组不能恢复");
    }

    // ---------------------------------------------------------------- 辅助

    /** 零宽窗口循环节点：偏移 20 天、周期 10 天、当天提醒、窗口前 0 后 0（窗口 = [计划日, 计划日]）。 */
    private long createPublishedZeroWindowPlan(String token, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 随访计划（" + code + "）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        long planId = idOf(post("/api/hospital/followup/plans", token, body));

        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "循环随访（示例）");
        subplan.put("repeatMode", "CYCLE");
        subplan.put("cycleValue", 10);
        subplan.put("cycleUnit", "DAY");
        subplan.put("sortNo", 10);
        Map<String, Object> node = new HashMap<>();
        node.put("key", "n1");
        node.put("parentKey", "s1");
        node.put("name", "电话随访（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", 20);
        node.put("offsetUnit", "DAY");
        node.put("remindValue", 0);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", 0);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 0);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", "fu_annual_v1");
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
        return planId;
    }

    /** 登记患者到默认病区（doctor.demo 可见），出院日期 = discharge。 */
    private long registerPatient(String token, String suffix, LocalDate discharge) {
        return registerPatient(token, suffix, discharge, defaultWardId());
    }

    /** 登记患者到新建病区（doctor.demo 不可见，越范围用例）。 */
    private long registerPatientInOtherWard(String token, String suffix, LocalDate discharge) {
        Long wardId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO ward (code, name, status)"
                                            + " VALUES ('W-IT-E14T06', 'IT 虚构病区二（示例）',"
                                            + " 'ENABLED')");
                            return jdbc.queryForObject(
                                    "SELECT id FROM ward WHERE code = 'W-IT-E14T06'", Long.class);
                        });
        return registerPatient(token, suffix, discharge, wardId);
    }

    private long registerPatient(String token, String suffix, LocalDate discharge, long wardId) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", MRN + "-" + suffix);
        body.put("name", "孙随访（示例）");
        body.put("gender", "M");
        body.put("birthDate", "1950-06-01");
        body.put("wardId", wardId);
        body.put("admissionDate", discharge.minusDays(14).toString());
        body.put("surgeryDate", discharge.minusDays(7).toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", MRN + "-" + suffix);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    /**
     * 服务层入组并把 created_at 回拨到入组早期（入组即建的任务与事件一并清掉）， 保持「入组已久、调度逐日推进」的时序语义（与 FollowupTaskSchedulerIT
     * 同口径）。
     */
    private long enrollBackdated(long patientId, long planId, LocalDate backdateTo) {
        return runInTenant(
                "demoa",
                () -> {
                    long enrollmentId =
                            Long.parseLong(enrollmentService.enroll(patientId, planId, null).id());
                    jdbc.update(
                            "UPDATE fu_enrollment SET created_at = ? WHERE id = ?",
                            backdateTo.atStartOfDay(),
                            enrollmentId);
                    jdbc.update("DELETE FROM fu_task WHERE enrollment_id = ?", enrollmentId);
                    jdbc.update(
                            "DELETE FROM sys_outbox WHERE event_type = 'TaskOpened'"
                                    + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                            patientId);
                    return enrollmentId;
                });
    }

    /** 回拨暂停起始日（时间旅行：模拟「已暂停数日」后恢复，服务写入的是真实当前时间）。 */
    private void backdatePauseFrom(long enrollmentId, LocalDate pausedFrom) {
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_enrollment SET pause_from = ? WHERE id = ?",
                                pausedFrom.atStartOfDay(),
                                enrollmentId));
    }

    private int taskCount(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM fu_task WHERE patient_id = ?",
                                Integer.class,
                                patientId));
    }

    private List<Map<String, Object>> tasksOf(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForList(
                                "SELECT * FROM fu_task WHERE patient_id = ? ORDER BY occurrence",
                                patientId));
    }

    private int taskOpenedEvents(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_outbox WHERE event_type = 'TaskOpened'"
                                        + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                                Integer.class,
                                patientId));
    }

    private String patientFollowupStatus(long patientId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT followup_status FROM pt_patient WHERE id = ?",
                                String.class,
                                patientId));
    }

    private long defaultWardId() {
        return runInTenant(
                "demoa", () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    private static LocalDate today() {
        return PlanDateCalculator.businessToday(Instant.now());
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

    /** JDBC 取回的 DATE 列统一换算 LocalDate 再断言。 */
    private static LocalDate date(Object value) {
        if (value instanceof LocalDate local) {
            return local;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        throw new IllegalArgumentException("不是日期列：" + value);
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
