package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.followup.task.app.FollowupTaskCreationService;
import com.crdp.framework.outbox.OutboxDispatcher;
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
 * E15-T08 任务与沟通链路用例（tasks.csv 验收「失访与跟进用例通过」；同 E13-T10 PatientDomainAcceptanceIT 的链路口径）。
 *
 * <p>与各任务的分项 IT（TaskStatusFlowIT / FollowupWorkbenchIT / TaskAssignmentIT / ContactLogIT /
 * LostAndFollowupIT）不同：把 E15-T01～T05 五条功能线接成两条连续旅程，后一阶段消费前一阶段真实产生的状态，全程走 HTTP（认领、
 * 改派、沟通、开始录入、提交、确认失访），仅计划入组回拨与每日调度用服务层（与 LostAndFollowupIT 同口径）：
 *
 * <ol>
 *   <li>失访旅程（A-FU-012）：公共池认领（T03）→ 第一次「未联系到」沟通：任务进行中 + 同事务生成次日跟进任务 + UNREACHED_FOLLOWUP
 *       事件（T01/T04/T05）→ 改派责任医生并收站内信（T03 验收）→ 补足两窗口各 3 个不同日期「未联系到 / 号码错误」→ 每日调度两任务
 *       LOST_CANDIDATE、窗口内跟进任务逾期（T05）→ 医生逐窗口确认：第一窗口失访（同窗口 2 条跟进终止、不提示患者级失访）、第二窗口 失访触发
 *       patientLostPrompt = true（连续 2 个窗口，患者状态不自动改）；
 *   <li>跟进旅程（A-FU-024）：认领 → 主任务未联系到生成跟进 1 → 跟进 1 上仍未联系到生成跟进 2（序号递增、次日开启、窗口继承）→ 医生经工作台在跟进 2
 *       上开始录入并提交随访表单（T02 验收「工作台可直接录入随访表单」）→ 跟进 2 完成且主任务一并完成 ON_TIME、跟进 1 终止（窗口口径完成传播）→
 *       源任务终态后再联系仍可记沟通但不再生成跟进、不再发事件。
 * </ol>
 *
 * <p>本链路发现并修复一处调度幂等回归：FollowupTaskMapper.findTaskId 未过滤 followup_seq，同窗口已有未联系到跟进任务（1 + N 行） 时
 * selectOne 抛 TooManyResultsException，整个入组被当日调度跳过、后续次数任务建不出来（失访旅程「今天这轮仍创建第 2 次任务」即其 回归用例）。
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 PLAN-IT-E15T08 前缀、病案号 IT-E15T08- 前缀、
 * 沟通 / 跟进 / 失访 / 改派事件与站内信、fu_record 与字段历史、任务与沟通审计、job_tenant_run 本作业行）。全部虚构数据。后台 outbox 分发停用（1
 * 小时间隔），改派站内信在测试内同步 dispatchForCurrentTenant 保证送达断言确定（同 TaskAssignmentIT）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.port=0", "crdp.outbox.dispatch.interval-ms=3600000"})
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskContactAcceptanceIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E15T08";
    private static final String PLAN_CODE = "PLAN-IT-E15T08";
    private static final String FORM = "A_ANNUAL_FU";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private FollowupTaskCreationService creationService;
    @Autowired private OutboxDispatcher dispatcher;

    @BeforeEach
    @AfterEach
    void cleanState() {
        SecurityContextHolderClear.clear();
        runInTenant(
                "demoa",
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE"
                                    + " (event_type IN ('TASK_CLAIM', 'TASK_ASSIGN'))"
                                    + " OR (event_type IN"
                                    + " ('TASK_START', 'TASK_PROGRESS', 'TASK_COMPLETE',"
                                    + " 'TASK_FOLLOWUP_CREATE', 'TASK_LOST_CONFIRM')"
                                    + " AND target IN (SELECT CONCAT('TASK:', t.id) FROM fu_task t"
                                    + " JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (event_type = 'RECORD_SUBMIT'"
                                    + " AND target IN (SELECT CONCAT('FU_RECORD:', r.id)"
                                    + " FROM fu_record r JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (event_type = 'CONTACT_CREATE'"
                                    + " AND target IN (SELECT CONCAT('CONTACT_LOG:', c.id)"
                                    + " FROM fu_contact_log c JOIN pt_patient p"
                                    + " ON p.id = c.patient_id WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (event_type = 'TASK_OVERDUE_MARK'"
                                    + " AND detail LIKE '%每日逾期刷新%')"
                                    + " OR (event_type = 'TASK_LOST_CANDIDATE_MARK'"
                                    + " AND detail LIKE '%每日失访候选刷新%')");
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
                    jdbc.update("DELETE FROM sys_outbox WHERE event_type = 'TASK_REASSIGNED'");
                    // dispatchForCurrentTenant 会消费当时全部待发事件（不止改派），通知引擎落下的站内信与
                    // 发送台账按本链路会派发的事件类型清理（NotifySendLogsIT 等按接收人清理，覆盖不到这些行）
                    jdbc.update(
                            "DELETE FROM ntf_message WHERE event_type IN"
                                    + " ('TASK_REASSIGNED', 'UNREACHED_FOLLOWUP', 'LOST_CANDIDATE',"
                                    + " 'TASK_OVERDUE')");
                    jdbc.update(
                            "DELETE FROM ntf_send_log WHERE event_type IN"
                                    + " ('TASK_REASSIGNED', 'UNREACHED_FOLLOWUP', 'LOST_CANDIDATE',"
                                    + " 'TASK_OVERDUE')");
                    jdbc.update(
                            "DELETE fh FROM fe_field_history fh"
                                    + " JOIN fu_record fr ON fr.id = fh.record_id"
                                    + " JOIN pt_patient p ON p.id = fr.patient_id"
                                    + " WHERE fh.record_type = 'FU_RECORD'"
                                    + " AND p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE fr FROM fu_record fr JOIN pt_patient p"
                                    + " ON p.id = fr.patient_id WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE c FROM fu_contact_log c JOIN pt_patient p"
                                    + " ON p.id = c.patient_id WHERE p.legacy_no LIKE ?",
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
                    jdbc.update("DELETE FROM job_tenant_run WHERE job_code = 'fu-task-create'");
                });
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 旅程 1：认领 → 沟通未接通 → 改派 →
    // 两窗口失访确认

    /**
     * 失访链（验收「失访…用例通过」）。窗口前 2 后 0（偏移 10 天、周期 10 天、当天提醒）：起算 今天−20 时第 1 次计划日 今天−10 （窗口 [今天−12,
     * 今天−10]）、第 2 次 今天（窗口 [今天−2, 今天]）。3 个不同日期的「未联系到 / 号码错误」按 窗口首日、次日、结束日
     * 布点：前两次的次日开启日仍在窗口内各生成一条跟进，结束日沟通的开启日（+1）出窗口不生成——两窗口各恰好 2 条跟进，供确认失访时 「同窗口未完成跟进终止」计数核对。
     */
    @Test
    void lostJourneyChainsClaimContactReassignAndTwoWindowLostConfirm() {
        LocalDate today = today();
        String nurse = hospitalToken("demoa", "nurse.demo");
        String doctor = hospitalToken("demoa", "doctor.demo");
        long nurseId = userId("demoa", "nurse.demo");
        long doctorId = userId("demoa", "doctor.demo");
        long planId = createCyclePlan(nurse, PLAN_CODE + "-L", 2, 0, "fu_annual_v1");
        long patientId = registerPatient("A1", today.minusDays(20));
        enrollBackdated(patientId, planId, today.minusDays(40));
        // 逻辑日按时间正序推进：第 1 次在其开启日创建；第 1 窗口的认领 / 沟通 / 改派都发生在它仍 PENDING 时，
        // 今天这轮（创建第 2 次 + 逾期刷新）推迟到第 1 窗口联系动作做完再跑
        creationService.runForDate(today.minusDays(10));
        long first = mainTaskId(patientId, 1);
        assertEquals(today.minusDays(10), windowEndOf(first), "第 1 窗口 [今天−12, 今天−10]");
        assertEquals("PENDING", statusOf(first), "开启日当天创建待随访");

        // 认领（T03）：置入公共池 → 护士认领第 1 个任务
        jdbcUpdate("UPDATE fu_task SET assignee_id = NULL WHERE id = ?", first);
        Map<?, ?> claimed =
                post("/api/hospital/followup/tasks/" + first + "/claim", nurse, Map.of());
        assertEquals(0, codeOf(claimed), "认领失败：" + claimed);
        assertEquals(nurseId, assigneeOf(first), "认领后负责人 = 认领人");
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'TASK_CLAIM'"
                                + " AND target = ?",
                        "TASK:" + first),
                "TASK_CLAIM 审计");

        // 第一次「未联系到」（T04 + T05 + T01 联动）：任务进行中 + 同事务跟进任务（次日开启、窗口继承）+ 事件
        Map<?, ?> contact1 =
                postContact(
                        patientId, nurse, first, "NOT_REACHED", today.minusDays(12).atTime(9, 0));
        assertEquals(0, codeOf(contact1), "沟通记录失败：" + contact1);
        Long followup1 = followupIdOf(contact1);
        assertNotNull(followup1, "生成跟进任务");
        assertEquals("IN_PROGRESS", statusOf(first), "第一次联系 → 进行中（E15-T01 联动）");
        assertEquals(today.minusDays(11), openDateOf(followup1), "开启日 = 沟通日 + 间隔天数（默认次日）");
        assertEquals(windowEndOf(first), windowEndOf(followup1), "窗口继承源任务");
        assertEquals(1, outboxCount(patientId, "UNREACHED_FOLLOWUP"), "UNREACHED_FOLLOWUP 事件");

        // 改派（T03 验收「改派后新责任人收到通知」）：医生收回任务 → 站内信送达新责任人
        Map<?, ?> reassigned =
                post(
                        "/api/hospital/followup/tasks/batch-assign",
                        doctor,
                        Map.of(
                                "taskIds",
                                List.of(first),
                                "assigneeId",
                                doctorId,
                                "reason",
                                "电话未接通，收回由责任医生跟进（示例）"));
        assertEquals(0, codeOf(reassigned), "改派失败：" + reassigned);
        assertEquals(1, ((Number) data(reassigned).get("succeeded")).intValue());
        assertEquals(doctorId, assigneeOf(first), "改派后负责人 = 新责任人");
        runInTenant("demoa", () -> dispatcher.dispatchForCurrentTenant());
        Map<String, Object> message =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForMap(
                                        "SELECT recipient_id, title, summary, target_type, target_id"
                                                + " FROM ntf_message WHERE event_type ="
                                                + " 'TASK_REASSIGNED' ORDER BY id DESC LIMIT 1"));
        assertEquals(doctorId, ((Number) message.get("recipient_id")).longValue(), "站内信接收人 = 新责任人");
        assertEquals(first, ((Number) message.get("target_id")).longValue(), "跳转目标 = 改派任务");
        assertTrue(
                String.valueOf(message.get("summary")).contains("收回由责任医生跟进"),
                "摘要含改派原因：" + message.get("summary"));

        // 新责任人补足第 1 窗口 3 个不同日期「未联系到 / 号码错误」（含拒访不计入口径之外的号码错误）
        Map<?, ?> contact2 =
                postContact(
                        patientId,
                        doctor,
                        first,
                        "WRONG_NUMBER",
                        today.minusDays(11).atTime(9, 30));
        assertNotNull(followupIdOf(contact2), "次日开启日仍在窗口内 → 第 2 条跟进");
        Map<?, ?> contact3 =
                postContact(
                        patientId, doctor, first, "NOT_REACHED", today.minusDays(10).atTime(10, 0));
        assertNull(followupIdOf(contact3), "结束日沟通的开启日出窗口 → 不再生成（A-FU-024）");
        assertEquals(2, followupCount(first), "第 1 窗口跟进恰好 2 条（窗口结束前可开启的沟通只有首日 / 次日两次）");

        // 今天这轮：为第 2 次创建任务 + 刷新（第 1 窗口已有 3 个不同日期未接通且窗口已结束 → 直接标失访候选；
        // 跟进任务存在时本入组不被跳过、后续次数照常创建是 E15-T08 修复的调度幂等回归，见 FollowupTaskMapper.findTaskId）
        creationService.runForDate(today);
        long second = mainTaskId(patientId, 2);
        assertEquals(today, windowEndOf(second), "第 2 窗口 [今天−2, 今天]");
        assertEquals("PENDING", statusOf(second), "第 2 次开启日当天创建待随访");
        assertEquals(
                "LOST_CANDIDATE",
                statusOf(first),
                "第 1 窗口满足「连续 3 次不同日期未接通 + 窗口已结束」→ 首次刷新即标失访候选（先逾期后候选，终态为候选）");
        assertEquals("OVERDUE", statusOf(followup1), "窗口结束仍未完成的跟进任务被标逾期（不参与失访候选：联系都挂在主任务上）");

        // 第 2 窗口同样 3 个不同日期（确认第 2 个窗口时凑连续 2 窗口失访提示）
        postContact(patientId, doctor, second, "NOT_REACHED", today.minusDays(2).atTime(9, 0));
        postContact(patientId, doctor, second, "NOT_REACHED", today.minusDays(1).atTime(9, 30));
        postContact(patientId, doctor, second, "NOT_REACHED", today.atTime(10, 0));
        assertEquals(2, followupCount(second), "第 2 窗口跟进恰好 2 条");

        // 每日调度（目标逻辑日 = 明天）：第 2 窗口结束 → 第 2 个主任务也标失访候选（第 1 个状态守卫幂等不重复发事件）
        creationService.runForDate(today.plusDays(1));
        assertEquals("LOST_CANDIDATE", statusOf(first), "已标候选的任务保持（状态守卫幂等）");
        assertEquals("LOST_CANDIDATE", statusOf(second), "第 2 窗口同样标失访候选");
        assertEquals(2, outboxCount(patientId, "LOST_CANDIDATE"), "每个任务一条 LOST_CANDIDATE 事件");

        // 医生确认第 1 个（HTTP confirm-lost）：→ 失访，同窗口 2 条未完成跟进终止，仅 1 个窗口不提示
        Map<?, ?> confirmFirst =
                post("/api/hospital/followup/tasks/" + first + "/confirm-lost", doctor, Map.of());
        assertEquals(0, codeOf(confirmFirst), "确认失访失败：" + confirmFirst);
        assertEquals("LOST", statusOf(first));
        assertEquals(
                2, ((Number) data(confirmFirst).get("terminatedFollowups")).intValue(), "同窗口跟进终止数");
        assertEquals(
                Boolean.FALSE,
                ((Map<?, ?>) confirmFirst.get("data")).get("patientLostPrompt"),
                "仅 1 个窗口失访，不提示患者级失访");
        assertEquals(
                "TERMINATED",
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT status FROM fu_task WHERE id = ?",
                                        String.class,
                                        followup1)),
                "跟进任务随窗口确认失访终止");
        assertEquals(
                "窗口任务已确认失访，跟进任务关闭",
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT terminate_reason FROM fu_task WHERE id = ?",
                                        String.class,
                                        followup1)),
                "终止原因留痕（A-FU-024 同窗口口径）");

        // 确认第 2 个：连续 2 个窗口失访 → patientLostPrompt = true（提示把患者状态改为失访，不自动改）
        Map<?, ?> confirmSecond =
                post("/api/hospital/followup/tasks/" + second + "/confirm-lost", doctor, Map.of());
        assertEquals(0, codeOf(confirmSecond));
        assertEquals("LOST", statusOf(second));
        assertEquals(
                Boolean.TRUE,
                ((Map<?, ?>) confirmSecond.get("data")).get("patientLostPrompt"),
                "连续 2 个窗口失访 → 提示把患者状态改为失访（A-FU-012）");
        assertEquals(
                2,
                ((Number) data(confirmSecond).get("terminatedFollowups")).intValue(),
                "第 2 窗口跟进同样终止");
        assertEquals(
                "ACTIVE",
                query(
                        "SELECT followup_status FROM pt_patient WHERE id = ?",
                        String.class,
                        patientId),
                "患者状态不自动改（医生确认后另行修改）");
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM aud_log WHERE event_type = 'TASK_LOST_CONFIRM'"
                                + " AND target IN (?, ?)",
                        "TASK:" + first,
                        "TASK:" + second),
                "两次确认各写一条 TASK_LOST_CONFIRM 审计");
    }

    // ---------------------------------------------------------------- 旅程 2：认领 → 跟进递进 → 工作台提交完成

    /**
     * 跟进链（验收「…跟进用例通过」）。窗口 [今天, 今天+3]（偏移 10 天、当天提醒、窗口前 0 后 3）：主任务与两条跟进都发生在窗口内， 医生最终在跟进 2
     * 上经工作台提交随访表单（E15-T02 验收「工作台可直接录入随访表单」），验证窗口口径完成传播（主任务一并完成、 跟进 1 终止）与终态后不再跟进。
     */
    @Test
    void followupJourneyCompletesMainTaskThroughWorkbenchSubmit() {
        LocalDate today = today();
        String nurse = hospitalToken("demoa", "nurse.demo");
        String doctor = hospitalToken("demoa", "doctor.demo");
        long doctorId = userId("demoa", "doctor.demo");
        long planId = createCyclePlan(nurse, PLAN_CODE + "-F", 0, 3, FORM);
        long patientId = registerPatient("B1", today.minusDays(10));
        enrollBackdated(patientId, planId, today.minusDays(40));
        creationService.runForDate(today);
        long taskId = mainTaskId(patientId, 1);
        assertEquals("PENDING", statusOf(taskId), "开启日当天创建待随访");
        assertEquals(today.plusDays(3), windowEndOf(taskId), "窗口 [今天, 今天+3]");

        // 认领（T03）→ 医生开始跟进链
        jdbcUpdate("UPDATE fu_task SET assignee_id = NULL WHERE id = ?", taskId);
        Map<?, ?> claimed =
                post("/api/hospital/followup/tasks/" + taskId + "/claim", doctor, Map.of());
        assertEquals(0, codeOf(claimed), "认领失败：" + claimed);
        assertEquals(doctorId, assigneeOf(taskId));

        // 主任务未联系到 → 跟进 1（序号 1、次日开启）；跟进 1 上仍未联系到 → 跟进 2（序号 2，T05 递进）
        Long followup1 =
                followupIdOf(
                        postContact(patientId, doctor, taskId, "NOT_REACHED", today.atTime(9, 0)));
        assertNotNull(followup1, "跟进 1 生成");
        assertEquals(1, followupSeqOf(followup1), "窗口内跟进序号 1");
        assertEquals("IN_PROGRESS", statusOf(taskId), "第一次联系 → 进行中");
        Long followup2 =
                followupIdOf(
                        postContact(
                                patientId, doctor, followup1, "NOT_REACHED", today.atTime(10, 0)));
        assertNotNull(followup2, "跟进 1 上仍未联系到 → 跟进 2 生成（窗口内继续）");
        assertEquals(2, followupSeqOf(followup2), "窗口内跟进序号 2");
        assertEquals(today.plusDays(1), openDateOf(followup2), "开启日 = 沟通业务日 + 间隔天数（默认次日）");
        assertEquals(windowEndOf(taskId), windowEndOf(followup2), "窗口继承");
        assertEquals(2, outboxCount(patientId, "UNREACHED_FOLLOWUP"), "两次未接通各一条事件");

        // 工作台直接录入（T02 验收）：在跟进 2 上开始 → 草稿记录 + 进行中
        Map<?, ?> started =
                post("/api/hospital/followup/tasks/" + followup2 + "/start", doctor, Map.of());
        assertEquals(0, codeOf(started), "开始录入失败：" + started);
        String recordId = String.valueOf(data(started).get("recordId"));
        assertTrue(!recordId.isBlank(), "返回 recordId 路由");
        assertEquals("IN_PROGRESS", statusOf(followup2), "跟进 2 进行中");
        assertEquals("IN_PROGRESS", statusOf(taskId), "主任务保持进行中");

        // 提交随访表单（f214 随访日期 / f215 是否死亡 / f213 随访方式，与 FollowupWorkbenchIT 同构）
        Map<?, ?> submitted =
                post(
                        "/api/hospital/followup/records/" + recordId + "/submit",
                        doctor,
                        Map.of(
                                "baseVersion",
                                0,
                                "data",
                                Map.of("f214", today.toString(), "f215", 0, "f213", 1)));
        assertEquals(0, codeOf(submitted), "提交失败：" + submitted);
        assertEquals("COMPLETED", data(submitted).get("taskStatus"), "提交后任务完成");
        assertEquals("ON_TIME", data(submitted).get("completeKind"), "窗口内完成");

        // 窗口口径完成传播（OQ-86⑤）：跟进 2 完成 → 主任务一并完成、跟进 1 终止
        assertEquals("COMPLETED", statusOf(followup2));
        assertEquals("COMPLETED", statusOf(taskId), "主任务随跟进完成（同一窗口）");
        assertEquals("ON_TIME", completeKindOf(taskId), "完成方式与触发任务同口径");
        assertEquals("TERMINATED", statusOf(followup1), "窗口内其余跟进终止");
        assertEquals(
                "窗口任务已完成，跟进任务关闭",
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT terminate_reason FROM fu_task WHERE id = ?",
                                        String.class,
                                        followup1)),
                "完成传播的终止原因留痕");
        assertEquals(
                "SUBMITTED",
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT status FROM fu_record WHERE id = ?",
                                        String.class,
                                        Long.parseLong(recordId))),
                "记录落 SUBMITTED");

        // 终态后：沟通仍可补记（联系事实），但不再生成跟进、不再发事件（A-FU-024 源任务须未完成）
        Map<?, ?> lateContact =
                postContact(patientId, doctor, taskId, "NOT_REACHED", today.atTime(11, 0));
        assertEquals(0, codeOf(lateContact), "终态任务的沟通事实仍可记录");
        assertNull(followupIdOf(lateContact), "已完成任务不再生成跟进");
        assertEquals(2, outboxCount(patientId, "UNREACHED_FOLLOWUP"), "不重复发事件");
        assertEquals(2, followupCount(taskId), "窗口内跟进仍是 2 条");
    }

    // ---------------------------------------------------------------- 辅助

    /** 循环节点计划：偏移 10 天、周期 10 天、当天提醒、窗口前 windowBefore 天 / 后 windowAfter 天、表单 formCode。 */
    private long createCyclePlan(
            String token, String code, int windowBefore, int windowAfter, String formCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 任务与沟通链计划（" + code + "）");
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
        node.put("formCode", formCode);
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

    /** 服务层入组并回拨 created_at（与 LostAndFollowupIT 同口径：入组即建的任务与事件一并清掉重放）。 */
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

    /** 某主任务所在窗口已生成的跟进任务数（create_type = FOLLOWUP）。 */
    private int followupCount(long taskId) {
        return count(
                "SELECT COUNT(*) FROM fu_task WHERE enrollment_id ="
                        + " (SELECT enrollment_id FROM fu_task WHERE id = ?)"
                        + " AND occurrence = (SELECT occurrence FROM fu_task WHERE id = ?)"
                        + " AND create_type = 'FOLLOWUP'",
                taskId,
                taskId);
    }

    private int followupSeqOf(long taskId) {
        return query("SELECT followup_seq FROM fu_task WHERE id = ?", Integer.class, taskId);
    }

    private long assigneeOf(long taskId) {
        return query("SELECT assignee_id FROM fu_task WHERE id = ?", Long.class, taskId);
    }

    private LocalDate openDateOf(long taskId) {
        return query("SELECT open_date FROM fu_task WHERE id = ?", LocalDate.class, taskId);
    }

    private String statusOf(long taskId) {
        return query("SELECT status FROM fu_task WHERE id = ?", String.class, taskId);
    }

    private String completeKindOf(long taskId) {
        return query("SELECT complete_kind FROM fu_task WHERE id = ?", String.class, taskId);
    }

    private LocalDate windowEndOf(long taskId) {
        return query("SELECT window_end FROM fu_task WHERE id = ?", LocalDate.class, taskId);
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

    private long userId(String tenant, String username) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM sys_user WHERE username = ?",
                                Long.class,
                                username));
    }

    private void jdbcUpdate(String sql, Object... args) {
        runInTenant("demoa", () -> jdbc.update(sql, args));
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
