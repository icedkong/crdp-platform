package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.followup.task.app.FollowupTaskStatusService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.framework.web.ApiException;
import com.crdp.server.AuditTableMaintenance;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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
 * E15-T01 端到端（A-FU-003「任务状态流转」、A-FU-010「逾期与逾期完成」；tasks.csv 验收「状态按规格流转，逾期完成单独标记」）。场景：
 *
 * <ol>
 *   <li>逾期标记（A-FU-010「窗口结束仍未完成即逾期」，进入时间 = 窗口结束日次日）：开启日当天创建 PENDING，窗口结束次日调度 → OVERDUE +
 *       TASK_OVERDUE 事件（负载契约 NotifyEventPayload，通知规则引擎消费）+ 汇总审计；重放不重发（状态守卫幂等）；
 *   <li>补录豁免（E14-T05 口径延伸）：create_type = BACKFILL 的任务窗口早已结束，不被标逾期、不发事件；
 *   <li>完成方式（验收「逾期完成单独标记」，followup V53）：窗口内提交 → ON_TIME；逾期后（直接录入期内）提交 → OVERDUE； 第一次暂存 / 联系 →
 *       进行中（PENDING 守卫）；已完成是终态（40901）；
 *   <li>「停用 / 暂停期间未执行」补做 / 关闭（PLT-TEN-017、A 的 Q32）：补做重回逾期（窗口已结束）、关闭置 TERMINATED 落原因；
 *   <li>双租户各自独立刷新（每院独立库）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 PLAN-IT-E15T01 前缀、病案号 IT-E15T01- 前缀、
 * TaskOpened / TASK_OVERDUE 事件、TASK_* 审计、job_tenant_run 本作业行）。全部虚构数据。 节点形态：零宽窗口循环（偏移 20 天、周期 10
 * 天、当天提醒、窗口前 0 后 0），窗口结束日可由起算日期精确控制；完成 / 补做 / 关闭走领域服务（HTTP 随 E15-T02 提交流程接入， 与 E14-T06 / E14-T07
 * 同口径）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskStatusFlowIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E15T01";
    private static final String PLAN_CODE = "PLAN-IT-E15T01";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private FollowupTaskCreationService creationService;
    @Autowired private FollowupTaskStatusService statusService;

    @BeforeEach
    void cleanState() {
        for (String tenant : List.of("demoa", "demob")) {
            runInTenant(
                    tenant,
                    () -> {
                        // 审计先行（target 子查询要用还没删的行）：任务流转 / 逾期刷新 / 计划 / 患者登记四类
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE (event_type IN"
                                        + " ('TASK_PROGRESS', 'TASK_COMPLETE', 'TASK_REDO',"
                                        + " 'TASK_CLOSE')"
                                        + " AND target IN (SELECT CONCAT('TASK:', t.id) FROM fu_task t"
                                        + " JOIN pt_patient p ON p.id = t.patient_id"
                                        + " WHERE p.legacy_no LIKE '"
                                        + LEGACY
                                        + "-%'))"
                                        + " OR (event_type = 'TASK_OVERDUE_MARK'"
                                        + " AND detail LIKE '%每日逾期刷新%')"
                                        + " OR (event_type LIKE 'PLAN_%'"
                                        + " AND (detail LIKE '%"
                                        + PLAN_CODE
                                        + "%'"
                                        + " OR target IN (SELECT CONCAT('PLAN:', id) FROM fu_plan"
                                        + " WHERE code LIKE '"
                                        + PLAN_CODE
                                        + "%')))"
                                        + " OR (event_type = 'PATIENT_CREATE'"
                                        + " AND target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                        + " WHERE legacy_no LIKE '"
                                        + LEGACY
                                        + "-%'))");
                        List<Long> patientIds =
                                jdbc.queryForList(
                                        "SELECT id FROM pt_patient WHERE legacy_no LIKE ?",
                                        Long.class,
                                        LEGACY + "-%");
                        for (Long patientId : patientIds) {
                            for (String eventType : List.of("TaskOpened", "TASK_OVERDUE")) {
                                jdbc.update(
                                        "DELETE FROM sys_outbox WHERE event_type = ?"
                                                + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                                        eventType,
                                        patientId);
                            }
                        }
                        jdbc.update(
                                "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                        + " WHERE p.legacy_no LIKE ?",
                                LEGACY + "-%");
                        jdbc.update("DELETE FROM job_tenant_run WHERE job_code = 'fu-task-create'");
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
                    });
        }
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 逾期标记（A-FU-010）

    @Test
    void windowEndNextDayMarksOverdueWithEventAndAudit() {
        LocalDate today = today();
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedZeroWindowPlan(admin, PLAN_CODE + "-O");
        // 起算 T−21：第 1 次窗口 [T−1, T−1]（次日 T 标逾期）、第 2 次开启日 T+9
        long patientId = registerPatient("demoa", admin, "O1", today.minusDays(21));
        enrollBackdated("demoa", patientId, planId, today.minusDays(40));

        creationService.runForDate(today.minusDays(1));
        long taskId = taskIdOf("demoa", patientId, 1);
        assertEquals("PENDING", statusOf("demoa", taskId), "开启日创建即待随访（A-FU-003）");
        assertEquals(0, taskOverdueEvents("demoa", patientId));

        // 窗口结束日次日（T）：无新次数开启，本次运行只做逾期刷新
        creationService.runForDate(today);
        assertEquals("OVERDUE", statusOf("demoa", taskId), "窗口结束仍未完成 → 逾期（A-FU-010）");
        assertEquals(1, taskOverdueEvents("demoa", patientId), "写一条 TASK_OVERDUE 事件");
        assertEquals(
                String.valueOf(windowEndOf("demoa", taskId)),
                eventWindowEnd("demoa", taskId),
                "事件负载带窗口截止日（文案与规则解析用）");
        assertEquals(
                1,
                count(
                        "demoa",
                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'TASK_OVERDUE_MARK'"
                                + " AND detail LIKE '%每日逾期刷新%'"),
                "批量刷新写一条汇总审计");

        // 重放（补跑 / 重复执行）：不重发事件、不重复写审计（状态守卫幂等）
        creationService.runForDate(today);
        assertEquals("OVERDUE", statusOf("demoa", taskId));
        assertEquals(1, taskOverdueEvents("demoa", patientId));
        assertEquals(
                1,
                count(
                        "demoa",
                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'TASK_OVERDUE_MARK'"
                                + " AND detail LIKE '%每日逾期刷新%'"));
    }

    // ---------------------------------------------------------------- 补录豁免（E14-T05 口径延伸）

    @Test
    void backfillTasksWithEndedWindowsAreExemptFromOverdue() {
        LocalDate today = today();
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        // 医院 B 形态：手术 T−8，术后第 1/3/5 天窗口均已结束 → 入组即建为 BACKFILL（Q44）。
        // 补录任务是入组即建的产物，这里保留入组当天的创建（不回拨重放）
        long planId = createPublishedPostopBackfillPlan(admin, PLAN_CODE + "-B");
        long patientId = registerPatientWithSurgery("demoa", admin, "B1", today.minusDays(8));
        runInTenant(
                "demoa",
                () -> {
                    enrollmentService.enroll(patientId, planId, null);
                    return null;
                });
        assertEquals(3, taskCount("demoa", patientId), "三个已结束窗口照常建任务（Q44）");
        assertEquals(
                3,
                count(
                        "demoa",
                        "SELECT COUNT(*) FROM fu_task WHERE patient_id = ?"
                                + " AND create_type = 'BACKFILL'",
                        patientId),
                "创建方式全部为补录");

        creationService.runForDate(today);

        assertEquals(
                3,
                count(
                        "demoa",
                        "SELECT COUNT(*) FROM fu_task WHERE patient_id = ? AND status = 'PENDING'",
                        patientId),
                "补录任务不进逾期（建任务时窗口已过，语义是补录历史，followup.md §1）");
        assertEquals(0, taskOverdueEvents("demoa", patientId), "补录不发逾期事件");
    }

    // ---------------------------------------------------------------- 完成方式（验收「逾期完成单独标记」）

    @Test
    void completionKindSeparatesOnTimeAndOverdue() {
        LocalDate today = today();
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedZeroWindowPlan(admin, PLAN_CODE + "-C");
        long operator = operatorId("demoa");

        // 逾期完成（OVERDUE，验收「逾期完成单独标记」）：起算 T−21 → 第 1 次窗口 [T−1, T−1]。
        // 逻辑日须按时间正序推进（job_tenant_run 只记最后成功日，回跑早于它的日期是 no-op）：
        // T−1 创建 PENDING，T 的刷新置逾期（第 2 次开启日 T+9 未到 = 直接录入期内，A-FU-010）
        long late = registerPatient("demoa", admin, "C2", today.minusDays(21));
        enrollBackdated("demoa", late, planId, today.minusDays(40));
        creationService.runForDate(today.minusDays(1));
        assertEquals("PENDING", statusOf("demoa", taskIdOf("demoa", late, 1)), "开启日创建待随访");

        // 窗口内完成（ON_TIME）：起算 T−20 → 第 1 次窗口 [T, T]，今天提交
        long inWindow = registerPatient("demoa", admin, "C1", today.minusDays(20));
        enrollBackdated("demoa", inWindow, planId, today.minusDays(40));
        creationService.runForDate(today);
        long inWindowTask = taskIdOf("demoa", inWindow, 1);
        assertEquals("PENDING", statusOf("demoa", inWindowTask));
        assertEquals(
                "OVERDUE", statusOf("demoa", taskIdOf("demoa", late, 1)), "窗口结束次日调度置逾期（A-FU-010）");

        // 第一次「联系 / 暂存」→ 进行中（A 规格任务状态表；HTTP 联动随 E15-T02 / T04 接入）
        assertTrue(
                runInTenant(
                        "demoa",
                        () -> statusService.markInProgress(inWindowTask, operator, "DRAFT")),
                "待随访 → 进行中");
        assertEquals("IN_PROGRESS", statusOf("demoa", inWindowTask));
        assertEquals(
                1,
                count(
                        "demoa",
                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'TASK_PROGRESS'"
                                + " AND target = CONCAT('TASK:', ?)",
                        inWindowTask),
                "状态变更写审计");

        FollowupTaskStatusService.CompletionView onTime =
                runInTenant("demoa", () -> statusService.complete(inWindowTask, operator));
        assertEquals("COMPLETED", onTime.status());
        assertEquals("ON_TIME", onTime.completeKind(), "窗口内提交 → 窗口内完成");
        assertEquals("ON_TIME", completeKindOf("demoa", inWindowTask), "完成方式落库（V53）");
        assertNotNull(completedAtOf("demoa", inWindowTask), "完成时间落库");
        assertEquals(
                1,
                count(
                        "demoa",
                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'TASK_COMPLETE'"
                                + " AND target = CONCAT('TASK:', ?)",
                        inWindowTask));

        // 逾期完成提交（上面 T 的运行已置逾期）
        long lateTask = taskIdOf("demoa", late, 1);
        assertEquals("OVERDUE", statusOf("demoa", lateTask), "窗口结束次日已置逾期");

        FollowupTaskStatusService.CompletionView overdueKind =
                runInTenant("demoa", () -> statusService.complete(lateTask, operator));

        assertEquals("OVERDUE", overdueKind.completeKind(), "逾期后提交 → 逾期完成（单独标记）");
        assertEquals("COMPLETED", statusOf("demoa", lateTask));
        assertEquals("OVERDUE", completeKindOf("demoa", lateTask));

        // 已完成是终态：再次完成 40901（A-FU-003「已完成任务不变」）
        ApiException rejected =
                runInTenant(
                        "demoa",
                        () -> {
                            try {
                                statusService.complete(lateTask, operator);
                                return null;
                            } catch (ApiException ex) {
                                return ex;
                            }
                        });
        assertNotNull(rejected, "重复完成被拒");
        assertEquals(40901, rejected.code());
    }

    // ---------------------------------------------------------------- 期间未执行：补做 /
    // 关闭（Q32、PLT-TEN-017）

    @Test
    void skippedSuspendedTasksRedoAsOverdueAndCloseAsTerminated() {
        LocalDate today = today();
        String admin = hospitalToken("demoa", "nurse.demo", PASSWORD);
        long planId = createPublishedZeroWindowPlan(admin, PLAN_CODE + "-S");
        long operator = operatorId("demoa");

        // 补做链路：起算 T−25 → 第 1 次窗口 [T−5, T−5] 已结束 → 「停用期间未执行」（E14-T06）；
        // 第 2 次开启日 T+5 未到 → 补做后仍在直接录入期内（A-FU-010），可完成且标逾期完成
        long redoPatient = registerPatient("demoa", admin, "S1", today.minusDays(25));
        enrollBackdated("demoa", redoPatient, planId, today.minusDays(40));
        creationService.runForDate(today);
        long skipped = taskIdOf("demoa", redoPatient, 1);
        assertEquals("SKIPPED_SUSPENDED", statusOf("demoa", skipped));

        // 补做：窗口已结束 → 重回 OVERDUE（医生随后照常联系与提交，Q32）
        FollowupTaskStatusService.SkippedActionView redone =
                runInTenant("demoa", () -> statusService.redoSkipped(skipped, operator));
        assertEquals("OVERDUE", redone.status(), "补做 → 逾期态待补做");
        assertEquals("OVERDUE", statusOf("demoa", skipped));

        // 补做后完成 → 逾期完成（窗口已过，口径一致）
        assertEquals(
                "OVERDUE",
                runInTenant("demoa", () -> statusService.complete(skipped, operator))
                        .completeKind(),
                "补做后提交 → 逾期完成（窗口已过）");

        // 关闭链路：起算 T−35 → 第 1、2 次窗口均已结束，两条「停用期间未执行」
        long closePatient = registerPatient("demoa", admin, "S2", today.minusDays(35));
        enrollBackdated("demoa", closePatient, planId, today.minusDays(40));
        creationService.runForDate(today);
        long first = taskIdOf("demoa", closePatient, 1);
        long second = taskIdOf("demoa", closePatient, 2);
        assertEquals("SKIPPED_SUSPENDED", statusOf("demoa", first));
        assertEquals("SKIPPED_SUSPENDED", statusOf("demoa", second));

        // 关闭：置 TERMINATED 并落原因（人工停止该次随访，复用 E14-T06 终止三列）
        assertNull(terminateReasonOf("demoa", second));
        runInTenant("demoa", () -> statusService.closeSkipped(second, operator, "已随访外院（示例）"));
        assertEquals("TERMINATED", statusOf("demoa", second));
        assertEquals("已随访外院（示例）", terminateReasonOf("demoa", second));
        assertNotNull(terminatedAtOf("demoa", second));

        // 关闭后是终态：补做 / 完成 / 关闭都 40901；关闭须填原因
        ApiException redoRejected =
                assertThrows(
                        ApiException.class,
                        () ->
                                runInTenant(
                                        "demoa", () -> statusService.redoSkipped(second, operator)),
                        "已关闭任务不能补做");
        assertEquals(40901, redoRejected.code());
        ApiException completeRejected =
                assertThrows(
                        ApiException.class,
                        () -> runInTenant("demoa", () -> statusService.complete(second, operator)),
                        "已关闭任务不能完成");
        assertEquals(40901, completeRejected.code());
        ApiException blankReason =
                assertThrows(
                        ApiException.class,
                        () ->
                                runInTenant(
                                        "demoa",
                                        () -> statusService.closeSkipped(second, operator, " ")),
                        "关闭须填原因");
        assertEquals(40001, blankReason.code());
    }

    // ---------------------------------------------------------------- 双租户隔离（每院独立库）

    @Test
    void overdueRefreshRunsPerTenantIndependently() {
        LocalDate today = today();
        String adminA = hospitalToken("demoa", "nurse.demo", PASSWORD);
        String adminB = hospitalToken("demob", "nurse.demo", PASSWORD);
        long planA = createPublishedZeroWindowPlan(adminA, PLAN_CODE + "-T");
        long planB = createTenantZeroWindowPlan(adminB);
        long patientA = registerPatient("demoa", adminA, "T1", today.minusDays(21));
        long patientB = registerPatient("demob", adminB, "T1", today.minusDays(21));
        enrollBackdated("demoa", patientA, planA, today.minusDays(40));
        enrollBackdated("demob", patientB, planB, today.minusDays(40));

        creationService.runForDate(today.minusDays(1));
        creationService.runForDate(today);

        long taskA = taskIdOf("demoa", patientA, 1);
        long taskB = taskIdOf("demob", patientB, 1);
        assertEquals("OVERDUE", statusOf("demoa", taskA), "demoa 各自刷新");
        assertEquals("OVERDUE", statusOf("demob", taskB), "demob 各自刷新（每院独立库）");
        assertEquals(1, taskOverdueEvents("demoa", patientA));
        assertEquals(1, taskOverdueEvents("demob", patientB));
        assertEquals(
                0,
                count("demob", "SELECT COUNT(*) FROM fu_task WHERE id = ?", taskA),
                "demoa 的任务行在 demob 不可见（租户库隔离）");
    }

    // ---------------------------------------------------------------- 辅助

    /** 零宽窗口循环节点（与 EnrollmentLifecycleIT 同形态）：偏移 20 天、周期 10 天、当天提醒、窗口 = [计划日, 计划日]。 */
    private long createPublishedZeroWindowPlan(String token, String code) {
        return createZeroWindowPlan(token, code);
    }

    /** demob 租户的零宽窗口计划（编码独立，两租户互不可见）。 */
    private long createTenantZeroWindowPlan(String token) {
        return createZeroWindowPlan(token, PLAN_CODE + "-T2");
    }

    private long createZeroWindowPlan(String token, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 状态流转计划（" + code + "）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        Map<?, ?> created = post("/api/hospital/followup/plans", token, body);
        assertEquals(0, codeOf(created), "新建计划失败：" + created);
        long planId = idOf(created);

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

    /** 医院 B 形态：手术起算、第 1/3/5 天节点 passed_window_action = BACKFILL（已结束窗口补建补录，Q44）。 */
    private long createPublishedPostopBackfillPlan(String token, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 术后补录计划（" + code + "）");
        body.put("startDateField", "SURGERY_DATE");
        body.put("cycleType", "ONGOING");
        long planId = idOf(post("/api/hospital/followup/plans", token, body));

        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "术后早期随访（示例）");
        subplan.put("repeatMode", "FIXED");
        subplan.put("repeatCount", 1);
        subplan.put("sortNo", 10);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (int day : List.of(1, 3, 5)) {
            Map<String, Object> node = new HashMap<>();
            node.put("key", "nd" + day);
            node.put("parentKey", "s1");
            node.put("name", "术后第 " + day + " 天随访（示例）");
            node.put("basis", "PLAN_START");
            node.put("offsetValue", day);
            node.put("offsetUnit", "DAY");
            node.put("remindValue", 0);
            node.put("remindUnit", "DAY");
            node.put("windowBeforeValue", 0);
            node.put("windowBeforeUnit", "DAY");
            node.put("windowAfterValue", 1);
            node.put("windowAfterUnit", "DAY");
            node.put("taskType", "FORM");
            node.put("ownerType", "WARD_DEFAULT");
            node.put("formCode", "fu_postop_v1");
            node.put("passedWindowAction", "BACKFILL");
            node.put("sortNo", 20);
            nodes.add(node);
        }
        List<Map<String, Object>> all = new ArrayList<>();
        all.add(subplan);
        all.addAll(nodes);
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/followup/plans/" + planId + "/nodes",
                                token,
                                Map.of("baseVersion", 0, "nodes", all))),
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

    /** 登记患者到默认病区（出院日期 = discharge，手术日期 = discharge − 7）。 */
    private long registerPatient(String tenant, String token, String suffix, LocalDate discharge) {
        return register(tenant, token, LEGACY + "-" + suffix, discharge.minusDays(7), discharge);
    }

    /** 登记患者并显式给手术日期（手术起算的补录计划用）。 */
    private long registerPatientWithSurgery(
            String tenant, String token, String suffix, LocalDate surgery) {
        return register(tenant, token, LEGACY + "-" + suffix, surgery, surgery.plusDays(8));
    }

    private long register(
            String tenant, String token, String legacyNo, LocalDate surgery, LocalDate discharge) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", legacyNo);
        body.put("name", "周随访（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", defaultWardId(tenant));
        body.put("admissionDate", surgery.minusDays(3).toString());
        body.put("surgeryDate", surgery.toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", legacyNo);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    /** 服务层入组并回拨 created_at（与 EnrollmentLifecycleIT 同口径：入组即建的任务与事件一并清掉重放）。 */
    private long enrollBackdated(String tenant, long patientId, long planId, LocalDate backdateTo) {
        return runInTenant(
                tenant,
                () -> {
                    long enrollmentId =
                            Long.parseLong(enrollmentService.enroll(patientId, planId, null).id());
                    jdbc.update(
                            "UPDATE fu_enrollment SET created_at = ? WHERE id = ?",
                            backdateTo.atStartOfDay(),
                            enrollmentId);
                    jdbc.update("DELETE FROM fu_task WHERE enrollment_id = ?", enrollmentId);
                    for (String eventType : List.of("TaskOpened", "TASK_OVERDUE")) {
                        jdbc.update(
                                "DELETE FROM sys_outbox WHERE event_type = ?"
                                        + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                                eventType,
                                patientId);
                    }
                    return enrollmentId;
                });
    }

    private long defaultWardId(String tenant) {
        return runInTenant(
                tenant, () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    private long operatorId(String tenant) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = 'nurse.demo'",
                                Long.class));
    }

    private int taskCount(String tenant, long patientId) {
        return count(tenant, "SELECT COUNT(*) FROM fu_task WHERE patient_id = ?", patientId);
    }

    private long taskIdOf(String tenant, long patientId, int occurrence) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM fu_task WHERE patient_id = ? AND occurrence = ?",
                                Long.class,
                                patientId,
                                occurrence));
    }

    private String statusOf(String tenant, long taskId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT status FROM fu_task WHERE id = ?", String.class, taskId));
    }

    private String completeKindOf(String tenant, long taskId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT complete_kind FROM fu_task WHERE id = ?",
                                String.class,
                                taskId));
    }

    private java.time.LocalDateTime completedAtOf(String tenant, long taskId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT completed_at FROM fu_task WHERE id = ?",
                                java.time.LocalDateTime.class,
                                taskId));
    }

    private String terminateReasonOf(String tenant, long taskId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT terminate_reason FROM fu_task WHERE id = ?",
                                String.class,
                                taskId));
    }

    private java.time.LocalDateTime terminatedAtOf(String tenant, long taskId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT terminated_at FROM fu_task WHERE id = ?",
                                java.time.LocalDateTime.class,
                                taskId));
    }

    private LocalDate windowEndOf(String tenant, long taskId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT window_end FROM fu_task WHERE id = ?",
                                LocalDate.class,
                                taskId));
    }

    private String eventWindowEnd(String tenant, long taskId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.windowEndDate'))"
                                        + " FROM sys_outbox WHERE event_type = 'TASK_OVERDUE'"
                                        + " AND JSON_EXTRACT(payload, '$.taskId') = ?",
                                String.class,
                                taskId));
    }

    private int taskOverdueEvents(String tenant, long patientId) {
        return count(
                tenant,
                "SELECT COUNT(*) FROM sys_outbox WHERE event_type = 'TASK_OVERDUE'"
                        + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                patientId);
    }

    /** 计数查询（int 口径）。 */
    private int count(String tenant, String sql, Object... args) {
        return runInTenant(tenant, () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    private static LocalDate today() {
        return PlanDateCalculator.businessToday(Instant.now());
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
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
