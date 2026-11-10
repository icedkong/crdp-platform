package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.followup.task.app.FollowupTaskStatusService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 * E15-T05 端到端（A-FU-012 失访判定、A-FU-024 未联系到自动跟进；tasks.csv 验收「每窗口最多 5 次跟进，窗口结束不再生成」）。场景：
 *
 * <ol>
 *   <li>未联系到自动跟进（A-FU-024）：沟通结果「未联系到」→ 同事务生成跟进任务（FOLLOWUP + followup_seq、次日开启、窗口继承、
 *       UNREACHED_FOLLOWUP 事件）；每窗口最多 5 次（第 6 次不再生成）；窗口结束不再生成（开启日出窗口 → 不生成， 边界日 = window_end
 *       仍生成）；跟进任务完成 → 主任务一并完成、其余跟进终止（窗口口径完成传播，OQ-86⑤）；
 *   <li>失访判定（A-FU-012）：窗口内不同日期连续 3 次「未联系到 / 号码错误」且窗口已结束 → 每日刷新标 LOST_CANDIDATE + 事件 + 汇总审计；
 *       医生确认（HTTP confirm-lost）→ LOST；连续 2 个窗口失访 → patientLostPrompt = true。
 * </ul>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 PLAN-IT-E15T05 前缀、病案号 IT-E15T05- 前缀、
 * 沟通 / 跟进 / 失访事件与审计、job_tenant_run 本作业行）。全部虚构数据。 节点形态：循环节点（偏移 10 天、周期 10 天、当天提醒、窗口前 2 天 / 后 0–3
 * 天），窗口与联系日期由起算日期和 contactTime 精确控制；沟通记录走 HTTP（E15-T04 接口），每日调度与完成 / 确认走领域服务（与 TaskStatusFlowIT
 * 同口径）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LostAndFollowupIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E15T05";
    private static final String PLAN_CODE = "PLAN-IT-E15T05";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private FollowupTaskCreationService creationService;
    @Autowired private FollowupTaskStatusService statusService;

    @BeforeEach
    void cleanState() {
        SecurityContextHolderClear.clear();
        runInTenant(
                "demoa",
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE (event_type IN"
                                    + " ('CONTACT_CREATE', 'TASK_PROGRESS', 'TASK_COMPLETE',"
                                    + " 'TASK_FOLLOWUP_CREATE', 'TASK_LOST_CANDIDATE_MARK',"
                                    + " 'TASK_LOST_CONFIRM', 'PATIENT_LOST_MARK')"
                                    + " AND (detail LIKE '%未联系到%'"
                                    + " OR detail LIKE '%失访%'"
                                    + " OR detail LIKE '%完成任务%'"
                                    + " OR target IN (SELECT CONCAT('TASK:', t.id) FROM fu_task t"
                                    + " JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%')))"
                                    + " OR (event_type = 'TASK_OVERDUE_MARK'"
                                    + " AND detail LIKE '%每日逾期刷新%')");
                    List<Long> patientIds =
                            jdbc.queryForList(
                                    "SELECT id FROM pt_patient WHERE legacy_no LIKE ?",
                                    Long.class,
                                    LEGACY + "-%");
                    for (Long patientId : patientIds) {
                        for (String eventType :
                                List.of(
                                        "TaskOpened",
                                        "TASK_OVERDUE",
                                        "UNREACHED_FOLLOWUP",
                                        "LOST_CANDIDATE")) {
                            jdbc.update(
                                    "DELETE FROM sys_outbox WHERE event_type = ?"
                                            + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                                    eventType,
                                    patientId);
                        }
                    }
                    jdbc.update(
                            "DELETE c FROM fu_contact_log c JOIN pt_patient p"
                                    + " ON p.id = c.patient_id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    // S5：状态时间线随患者清理（无外键，只追加表也不能留孤儿行）
                    jdbc.update(
                            "DELETE h FROM pt_status_history h JOIN pt_patient p"
                                    + " ON p.id = h.patient_id WHERE p.legacy_no LIKE ?",
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

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 验收：未联系到自动跟进（A-FU-024）

    /** 窗口 [今天, 今天+3]（偏移 10、当天提醒、窗口前 0 后 3）：联系与跟进开启日都可控。 */
    @Test
    void unreachedContactCreatesFollowupCappedAtFivePerWindow() {
        LocalDate today = today();
        String token = hospitalToken("demoa", "nurse.demo");
        long planId = createCyclePlan(token, PLAN_CODE + "-F", 0, 3);
        long patientId = registerPatient("F1", today.minusDays(10));
        enrollBackdated(patientId, planId, today.minusDays(40));

        creationService.runForDate(today);
        long taskId = mainTaskId(patientId, 1);
        assertEquals("PENDING", statusOf(taskId), "开启日当天创建待随访");
        assertEquals(today.plusDays(3), windowEndOf(taskId), "窗口 [今天, 今天+3]");

        // 第 1 次「未联系到」→ 跟进任务（次日开启、窗口继承、FOLLOWUP + seq 1）
        Map<?, ?> created =
                postContact(patientId, token, taskId, "NOT_REACHED", today.atTime(9, 0));
        assertEquals(0, codeOf(created), "沟通记录创建失败：" + created);
        Long firstFollowup = followupIdOf(created);
        assertNotNull(firstFollowup, "返回跟进任务 id");
        assertEquals(
                "FOLLOWUP",
                query("SELECT create_type FROM fu_task WHERE id = ?", String.class, firstFollowup));
        assertEquals(
                1,
                query(
                        "SELECT followup_seq FROM fu_task WHERE id = ?",
                        Integer.class,
                        firstFollowup),
                "窗口内跟进序号 1");
        assertEquals(
                today.plusDays(1),
                query("SELECT open_date FROM fu_task WHERE id = ?", LocalDate.class, firstFollowup),
                "开启日 = 沟通日 + 间隔天数（默认次日）");
        assertEquals(
                windowEndOf(taskId),
                query(
                        "SELECT window_end FROM fu_task WHERE id = ?",
                        LocalDate.class,
                        firstFollowup),
                "窗口继承源任务");
        assertEquals("IN_PROGRESS", statusOf(taskId), "第一次联系 → 进行中（E15-T01 联动）");
        assertEquals(1, outboxCount(patientId, "UNREACHED_FOLLOWUP"), "写一条 UNREACHED_FOLLOWUP 事件");

        // 窗口结束不再生成（验收；此时跟进数 1 < 5，规则独立可判）：沟通在窗口最后一日（今天+3）→ 次日出窗口
        Map<?, ?> lastDay =
                postContact(
                        patientId, token, taskId, "NOT_REACHED", today.plusDays(3).atTime(9, 0));
        assertNull(followupIdOf(lastDay), "窗口结束不再生成（开启日 = window_end + 1 出窗口）");
        // 边界：沟通在 window_end 前一日 → 开启日恰为 window_end，仍生成
        Map<?, ?> edge =
                postContact(
                        patientId, token, taskId, "NOT_REACHED", today.plusDays(2).atTime(9, 0));
        assertNotNull(followupIdOf(edge), "开启日 = window_end 当天仍属窗口内（含边界）");

        // 灌满每窗口 5 次（边界跟进已占 1 条，再补 3 条；第 6 次不再生成——验收「每窗口最多 5 次跟进」）
        for (int i = 3; i <= 5; i++) {
            Map<?, ?> next =
                    postContact(patientId, token, taskId, "WRONG_NUMBER", today.atTime(10 + i, 0));
            assertNotNull(followupIdOf(next), "第 " + i + " 次跟进仍生成（未满 5）");
        }
        assertEquals(
                5,
                count(
                        "SELECT COUNT(*) FROM fu_task WHERE enrollment_id = ? AND create_type = 'FOLLOWUP'",
                        enrollmentIdOf(taskId)),
                "窗口内跟进任务恰好 5 条（A-FU-024）");
        Map<?, ?> sixth = postContact(patientId, token, taskId, "NOT_REACHED", today.atTime(18, 0));
        assertNull(followupIdOf(sixth), "第 6 次不再生成（每窗口最多 5 次）");
        assertEquals(
                5,
                count(
                        "SELECT COUNT(*) FROM fu_task WHERE enrollment_id = ? AND create_type = 'FOLLOWUP'",
                        enrollmentIdOf(taskId)),
                "跟进任务仍是 5 条");
    }

    /** 跟进任务完成 → 主任务一并完成、其余跟进终止（窗口口径完成传播，OQ-86⑤）。 */
    @Test
    void completingFollowupCompletesMainTaskAndClosesSiblings() {
        LocalDate today = today();
        String token = hospitalToken("demoa", "nurse.demo");
        long planId = createCyclePlan(token, PLAN_CODE + "-P", 0, 3);
        long patientId = registerPatient("P1", today.minusDays(10));
        enrollBackdated(patientId, planId, today.minusDays(40));
        creationService.runForDate(today);
        long taskId = mainTaskId(patientId, 1);

        Long firstFollowup =
                followupIdOf(
                        postContact(patientId, token, taskId, "NOT_REACHED", today.atTime(9, 0)));
        Long secondFollowup =
                followupIdOf(
                        postContact(
                                patientId,
                                token,
                                firstFollowup,
                                "NOT_REACHED",
                                today.atTime(11, 0)));
        assertNotNull(secondFollowup, "在跟进任务上再未接通 → 窗口内继续生成（seq 2）");

        // 医生经第 2 条跟进任务联系上患者并提交 → 跟进与主任务完成、第 1 条跟进终止
        long operator = operatorId();
        runInTenant(
                "demoa",
                () -> {
                    statusService.markInProgress(secondFollowup, operator, "开始录入");
                    return statusService.complete(secondFollowup, operator);
                });
        assertEquals("COMPLETED", statusOf(secondFollowup));
        assertEquals("COMPLETED", statusOf(taskId), "跟进完成 → 主任务一并完成（窗口口径）");
        assertEquals("ON_TIME", completeKindOf(taskId), "完成方式与触发任务同口径（窗口内）");
        assertEquals("TERMINATED", statusOf(firstFollowup), "窗口内其余未完成跟进任务终止（窗口工作已结束）");
    }

    // ---------------------------------------------------------------- 失访判定（A-FU-012）

    /** 窗口前 2 后 0（窗口 [计划日−2, 计划日]）：不同日期连续未接通与窗口结束可控。 */
    @Test
    void windowEndWithThreeDistinctUnreachedDatesMarksLostCandidateAndConfirmLost() {
        LocalDate today = today();
        String token = hospitalToken("demoa", "nurse.demo");
        // 起算 今天−20：第 1 次计划日 今天−10（窗口 [今天−12, 今天−10]）、第 2 次 今天（窗口 [今天−2, 今天]）
        long planId = createCyclePlan(token, PLAN_CODE + "-L", 2, 0);
        long patientId = registerPatient("L1", today.minusDays(20));
        enrollBackdated(patientId, planId, today.minusDays(40));
        // 逻辑日按时间正序推进（job_tenant_run 只记最后成功日）：第 1 次在其窗口结束日当天创建（NORMAL），
        // 第 2 次开启日 今天−2 由今天这轮创建
        creationService.runForDate(today.minusDays(10));
        creationService.runForDate(today);

        long first = mainTaskId(patientId, 1);
        long second = mainTaskId(patientId, 2);
        assertEquals(today.minusDays(10), windowEndOf(first), "第 1 次窗口结束日");
        assertEquals(today, windowEndOf(second), "第 2 次窗口结束日");

        // 第 1 个窗口：不同日期连续 3 次「未联系到 / 号码错误」
        postContact(patientId, token, first, "NOT_REACHED", today.minusDays(12).atTime(9, 0));
        postContact(patientId, token, first, "WRONG_NUMBER", today.minusDays(11).atTime(9, 30));
        postContact(patientId, token, first, "NOT_REACHED", today.minusDays(10).atTime(10, 0));
        // 第 2 个窗口同样三次（确认第 2 个时凑连续 2 窗口失访）
        postContact(patientId, token, second, "NOT_REACHED", today.minusDays(2).atTime(9, 0));
        postContact(patientId, token, second, "NOT_REACHED", today.minusDays(1).atTime(9, 30));
        postContact(patientId, token, second, "NOT_REACHED", today.atTime(10, 0));

        // 每日调度（目标逻辑日 = 明天）：先逾期刷新再失访候选刷新
        creationService.runForDate(today.plusDays(1));
        assertEquals("LOST_CANDIDATE", statusOf(first), "连续 3 次不同日期 + 窗口已结束 → 失访候选");
        assertEquals("LOST_CANDIDATE", statusOf(second));
        assertEquals(2, outboxCount(patientId, "LOST_CANDIDATE"), "每个任务一条 LOST_CANDIDATE 事件");
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'TASK_LOST_CANDIDATE_MARK'"
                                + " AND detail LIKE '%每日失访候选刷新%'"),
                "批量刷新写一条汇总审计");
        // 重放：状态守卫幂等，不重发事件
        creationService.runForDate(today.plusDays(1));
        assertEquals(2, outboxCount(patientId, "LOST_CANDIDATE"));

        // 医生确认第 1 个（HTTP confirm-lost）：→ 失访，仅 1 个窗口失访不提示
        Map<?, ?> confirmedFirst =
                post("/api/hospital/followup/tasks/" + first + "/confirm-lost", token, Map.of());
        assertEquals(0, codeOf(confirmedFirst), "确认失访失败：" + confirmedFirst);
        assertEquals("LOST", statusOf(first));
        assertEquals(
                Boolean.FALSE,
                ((Map<?, ?>) confirmedFirst.get("data")).get("patientLostPrompt"),
                "仅 1 个窗口失访，不提示患者级失访");
        // 非失访候选不能确认（40901）
        Map<?, ?> rejected =
                post("/api/hospital/followup/tasks/" + first + "/confirm-lost", token, Map.of());
        assertEquals(40901, codeOf(rejected), "已失访是终态，重复确认 40901");

        // 确认第 2 个：连续 2 个窗口失访 → patientLostPrompt = true（提示把患者状态改为失访，不自动改）
        Map<?, ?> confirmedSecond =
                post("/api/hospital/followup/tasks/" + second + "/confirm-lost", token, Map.of());
        assertEquals(0, codeOf(confirmedSecond));
        assertEquals(
                Boolean.TRUE,
                ((Map<?, ?>) confirmedSecond.get("data")).get("patientLostPrompt"),
                "连续 2 个窗口失访 → 提示把患者状态改为失访（A-FU-012）");
        assertEquals(
                "ACTIVE",
                query(
                        "SELECT followup_status FROM pt_patient WHERE id = ?",
                        String.class,
                        patientId),
                "患者状态不自动改（医生确认后另行修改）");
    }

    /** 「拒访」不计入失访：最新一次联系是拒访 → 连续段断开，不标候选（A-FU-012）。 */
    @Test
    void refusedContactBreaksStreakAndPreventsLostCandidate() {
        LocalDate today = today();
        String token = hospitalToken("demoa", "nurse.demo");
        long planId = createCyclePlan(token, PLAN_CODE + "-R", 2, 0);
        long patientId = registerPatient("R1", today.minusDays(10));
        enrollBackdated(patientId, planId, today.minusDays(40));
        creationService.runForDate(today);
        long taskId = mainTaskId(patientId, 1);
        assertEquals(today, windowEndOf(taskId), "窗口 [今天−2, 今天]");

        postContact(patientId, token, taskId, "NOT_REACHED", today.minusDays(2).atTime(9, 0));
        postContact(patientId, token, taskId, "NOT_REACHED", today.minusDays(1).atTime(9, 0));
        postContact(patientId, token, taskId, "REFUSED", today.atTime(9, 0)); // 最新一次拒访

        creationService.runForDate(today.plusDays(1));

        assertEquals("OVERDUE", statusOf(taskId), "只逾期；「拒访」断开连续段，不标失访候选（改走移出计划）");
        assertEquals(0, outboxCount(patientId, "LOST_CANDIDATE"), "不发失访候选事件");
    }

    // ---------------------------------------------------------------- S5（M3 审查）：患者改为失访写入口

    /**
     * S5 / A-FU-012、A-PAT-011：POST /patients/{id}/status（action=LOST，原因必填）——写状态历史（source=MANUAL）与
     * PATIENT_LOST_MARK 审计， 未开始 / 未完成任务全部终止（终止原因「失访」），之后不再创建任务；重复失访 40901、原因缺失 40001、 无
     * followup:patient:edit 40300、跨院 40400（不暴露存在）。
     */
    @Test
    void patientMarkedLostWritesHistoryTerminatesTasksAndStopsFutureOnes() {
        LocalDate today = today();
        String token = hospitalToken("demoa", "nurse.demo");
        long planId = createCyclePlan(token, PLAN_CODE + "-P", 2, 0);
        long patientId = registerPatient("P1", today.minusDays(20));
        enrollBackdated(patientId, planId, today.minusDays(40));
        creationService.runForDate(today.minusDays(10));
        creationService.runForDate(today);
        long first = mainTaskId(patientId, 1);
        long second = mainTaskId(patientId, 2);
        // 第 1 个窗口已结束（补跑今天这轮时被每日逾期刷新标 OVERDUE），第 2 个窗口今天结束仍 PENDING
        assertEquals("OVERDUE", statusOf(first), "失访改判前：窗口已结束的任务先被逾期刷新");
        assertEquals("PENDING", statusOf(second), "失访改判前：本窗口任务待随访");

        // 权限：A_WARD_NURSE 无 followup:patient:edit → 40300
        String wardNurse = hospitalToken("demoa", "a.cvs1.nur01");
        assertEquals(
                40300,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/status",
                                wardNurse,
                                Map.of("action", "LOST", "reason", "连续两年未联系到（示例）"))),
                "无 followup:patient:edit 40300");
        // 跨院：B 院无此患者 → 40400（不暴露存在）
        String tenantB = hospitalToken("demob", "nurse.demo");
        assertEquals(
                40400,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/status",
                                tenantB,
                                Map.of("action", "LOST", "reason", "连续两年未联系到（示例）"))));
        // 校验：原因必填 40001；非 LOST 动作 40001（本轮只开放失访）
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/status",
                                token,
                                Map.of("action", "LOST"))),
                "失访原因必填");
        assertEquals(
                40001,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/status",
                                token,
                                Map.of("action", "PAUSE", "reason", "走计划入组操作（示例）"))),
                "暂停等动作走计划入组操作");

        // 医生改为失访：两个未开始任务一并终止
        Map<?, ?> marked =
                post(
                        "/api/hospital/followup/patients/" + patientId + "/status",
                        token,
                        Map.of("action", "LOST", "reason", "连续两年未联系到（示例）"));
        assertEquals(0, codeOf(marked), "改为失访失败：" + marked);
        assertEquals(2, ((Number) data(marked).get("terminatedTasks")).intValue());
        assertEquals("LOST", data(marked).get("status"));
        assertEquals(
                "LOST",
                query(
                        "SELECT followup_status FROM pt_patient WHERE id = ?",
                        String.class,
                        patientId),
                "患者状态改为失访");
        assertEquals(
                "MANUAL",
                query(
                        "SELECT source FROM pt_status_history WHERE patient_id = ?"
                                + " AND status = 'LOST' ORDER BY id DESC LIMIT 1",
                        String.class,
                        patientId),
                "状态历史落 source=MANUAL 一行");
        assertEquals("TERMINATED", statusOf(first), "未开始任务终止");
        assertEquals("TERMINATED", statusOf(second));
        assertEquals(
                "失访",
                query("SELECT terminate_reason FROM fu_task WHERE id = ?", String.class, first),
                "终止原因「失访」");
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'PATIENT_LOST_MARK'"
                                + " AND result = 'SUCCESS' AND target = ?",
                        "PATIENT:" + patientId),
                "写 PATIENT_LOST_MARK 成功审计");

        // 重复失访 40901（重复操作被拒，不重复写历史）
        assertEquals(
                40901,
                codeOf(
                        post(
                                "/api/hospital/followup/patients/" + patientId + "/status",
                                token,
                                Map.of("action", "LOST", "reason", "再点一次（示例）"))));

        // 失访后调度不再创建：第 3 次计划日 today+10，跑该逻辑日不建任务（S5 调度过滤 LOST）
        creationService.runForDate(today.plusDays(10));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM fu_task WHERE patient_id = ? AND occurrence = 3",
                        patientId),
                "失访患者不再创建后续任务（A-FU-012）");
    }

    // ---------------------------------------------------------------- 辅助

    /** 循环节点计划：偏移 10 天、周期 10 天、当天提醒、窗口前 windowBefore 天 / 后 windowAfter 天。 */
    private long createCyclePlan(String token, String code, int windowBefore, int windowAfter) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 失访与跟进计划（" + code + "）");
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
        node.put("offsetValue", 10);
        node.put("offsetUnit", "DAY");
        node.put("remindValue", 0);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", windowBefore);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", windowAfter);
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

    /** 记一条联系（HTTP，E15-T04 接口；contactTime 控制业务日）。 */
    private Map<?, ?> postContact(
            long patientId, String token, long taskId, String result, LocalDateTime contactTime) {
        Map<String, Object> body = new HashMap<>();
        body.put("taskId", taskId);
        body.put("method", "PHONE");
        body.put("result", result);
        body.put("contactTime", contactTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return post("/api/hospital/followup/patients/" + patientId + "/contacts", token, body);
    }

    /** 创建响应里的跟进任务 id（data.followupTaskId；未生成为 null）。 */
    private static Long followupIdOf(Map<?, ?> response) {
        Object value = ((Map<?, ?>) response.get("data")).get("followupTaskId");
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }

    /** 登记患者到默认病区（出院日期 = discharge，起算日期）。 */
    private long registerPatient(String suffix, LocalDate discharge) {
        String token = hospitalToken("demoa", "nurse.demo");
        Map<String, Object> body = new HashMap<>();
        String legacyNo = LEGACY + "-" + suffix;
        body.put("medicalRecordNo", legacyNo);
        body.put("name", "周随访（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1955-01-01");
        body.put("wardId", defaultWardId());
        body.put("admissionDate", discharge.minusDays(10).toString());
        body.put("surgeryDate", discharge.minusDays(7).toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", legacyNo);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    /** 服务层入组并回拨 created_at（与 TaskStatusFlowIT 同口径：入组即建的任务与事件一并清掉重放）。 */
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
                            "DELETE FROM sys_outbox WHERE JSON_EXTRACT(payload, '$.patientId') = ?",
                            patientId);
                    return enrollmentId;
                });
    }

    /** 该患者某次的主任务 id（followup_seq = 0）。 */
    private long mainTaskId(long patientId, int occurrence) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM fu_task WHERE patient_id = ? AND occurrence = ?"
                                        + " AND followup_seq = 0",
                                Long.class,
                                patientId,
                                occurrence));
    }

    private long enrollmentIdOf(long taskId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT enrollment_id FROM fu_task WHERE id = ?",
                                Long.class,
                                taskId));
    }

    private String statusOf(long taskId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT status FROM fu_task WHERE id = ?", String.class, taskId));
    }

    private String completeKindOf(long taskId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT complete_kind FROM fu_task WHERE id = ?",
                                String.class,
                                taskId));
    }

    private LocalDate windowEndOf(long taskId) {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT window_end FROM fu_task WHERE id = ?",
                                LocalDate.class,
                                taskId));
    }

    private int outboxCount(long patientId, String eventType) {
        return count(
                "SELECT COUNT(*) FROM sys_outbox WHERE event_type = ?"
                        + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                eventType,
                patientId);
    }

    private long defaultWardId() {
        return runInTenant(
                "demoa", () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
    }

    private long operatorId() {
        return runInTenant(
                "demoa",
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = 'nurse.demo'",
                                Long.class));
    }

    private int count(String sql, Object... args) {
        return runInTenant("demoa", () -> jdbc.queryForObject(sql, Integer.class, args));
    }

    /** 租户上下文内的单值查询（直接用 jdbc 须先挂租户，AGENTS §5.1）。 */
    private <T> T query(String sql, Class<T> type, Object... args) {
        return runInTenant("demoa", () -> jdbc.queryForObject(sql, type, args));
    }

    private static LocalDate today() {
        return PlanDateCalculator.businessToday(Instant.now());
    }

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
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

    /** 测试内会话清理（服务层造数可能装配认证上下文）。 */
    static final class SecurityContextHolderClear {
        private SecurityContextHolderClear() {}

        static void clear() {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
