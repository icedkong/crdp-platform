package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.notice.app.NotifyDigestService;
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
 * E15-T09 M2 集成测试（tasks.csv 验收「全链路用例通过」）：把 M2 主流程——出院登记 → 入组 → 任务 → 沟通 → 通知——接成两条连续旅程
 * （hospital-a-spec §5「年度随访流程」第 1～5 步 + 第 7 章通知）。
 *
 * <p>与分项 IT 的分工：EnrollmentBackfillIT 锁 E14-T05 补录策略、TaskStatusFlowIT / LostAndFollowupIT /
 * TaskContactAcceptanceIT 锁任务状态 / 失访 / 跟进链、NotifyRulesIT / NoticeInboxIT / NotifyDigestIT /
 * NotifyAcceptanceIT 锁通知引擎各段。本套件只消费「真实链路产物」：出院登记走 HTTP 且入组由登记事务内的自动入组完成（Q35，不回拨
 * created_at、不经服务层造数入组），通知只消费链路自己发布的 outbox 事件（不手工插事件、不手工投递），收件箱与已读流转走 HTTP。
 *
 * <p>旅程 1（失联链，§5 第 1/2/3/5 步 + 通知）：出院登记（存活）→ 事务内自动入组 + 窗口已开启未结束的次数当天 NORMAL 建任务（病区未配 默认负责人 → 公共池）→
 * {@code TaskOpened} 事件（驼峰，通知引擎枚举 TASK_OPENED 未接线，无订阅方保持 PENDING——OQ-78④ / OQ-60③ 既定口径，分发后不丢）→
 * 沟通「未联系到」→ 任务进行中 + 同事务跟进任务 + UNREACHED_FOLLOWUP 事件 → 分发 → 病区成员兜底收站内信（负责人缺失， A-NTF-004）→ 窗口结束次日每日调度
 * → 主任务与跟进任务一并逾期 + TASK_OVERDUE ×2 → 分发 → 站内信（跳转落点随消息下发）+ 短信台账 QUEUED （A 规格：短信不即时发，并入每日汇总）→ 每日汇总作业
 * → 站内信「每日汇总」+ 台账出队（本地通道未启用记 FAILED，行不丢）。
 *
 * <p>旅程 2（顺利链，§5 第 3/4 步）：登记 → 自动入组 → 公共池认领 → 「已联系」沟通（进行中、不生成跟进）→ 开始录入并提交随访表单 → 窗口内完成
 * ON_TIME；通知侧静默：除保持 PENDING 的 TaskOpened 外链路不发事件、站内信无该患者任务的落点，窗口结束后已完成的任务不进逾期通知。
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 PLAN-IT-E15T09 前缀、病案号 IT-E15T09- 前缀、
 * 本链路 outbox 事件与站内信 / 短信台账、fu_record 与字段历史、沟通 / 任务 / 登记 / 计划审计、job_tenant_run 本作业行）。全部虚构数据。 后台
 * outbox 分发与汇总 tick 停用（1 小时间隔），链路内同步 dispatchForCurrentTenant / digest 保证断言确定（同
 * NotifyAcceptanceIT）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.port=0",
            "crdp.outbox.dispatch.interval-ms=3600000",
            "crdp.notify.digest.tick-ms=3600000"
        })
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M2DischargeToNotifyChainIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E15T09";
    private static final String PLAN_CODE = "PLAN-IT-E15T09";
    private static final String FORM = "A_ANNUAL_FU";

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FollowupTaskCreationService creationService;
    @Autowired private OutboxDispatcher dispatcher;
    @Autowired private NotifyDigestService digestService;

    @BeforeEach
    void cleanState() {
        SecurityContextHolderClear.clear();
        runInTenant(
                "demoa",
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE"
                                    + " (event_type LIKE 'PLAN_%'"
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
                                    + "-%'))"
                                    + " OR (event_type IN"
                                    + " ('TASK_CLAIM', 'TASK_START', 'TASK_PROGRESS', 'TASK_COMPLETE',"
                                    + " 'TASK_FOLLOWUP_CREATE', 'RECORD_SUBMIT', 'CONTACT_CREATE')"
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
                                    + " OR (action = 'TASK_CREATE' AND detail LIKE '%每日调度%')"
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
                    // 链路会派发的通知事件类型（含每日汇总），站内信与短信台账一并清理（口径同 TaskContactAcceptanceIT）
                    jdbc.update(
                            "DELETE FROM ntf_message WHERE event_type IN"
                                    + " ('UNREACHED_FOLLOWUP', 'TASK_OVERDUE', 'DAILY_DIGEST')");
                    jdbc.update(
                            "DELETE FROM ntf_send_log WHERE event_type IN"
                                    + " ('UNREACHED_FOLLOWUP', 'TASK_OVERDUE', 'DAILY_DIGEST')");
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

    // ---------------------------------------------------------------- 旅程 1：出院登记 → 入组 → 任务 →
    // 沟通（未联系到）→ 通知（站内信 / 短信台账 / 每日汇总）

    /**
     * 失联链。循环节点（偏移 10 天、周期 10 天、当天提醒、窗口 [今天, 今天+3]）：出院日 = 今天−10 → 首次计划日 = 今天，登记（= 入组）当天窗口 已开启未结束 →
     * 入组即建 NORMAL 任务；窗口结束日 今天+3，次日（今天+4）调度刷新逾期。
     */
    @Test
    void unreachedJourneyRunsFromDischargeRegistrationToDailyDigest() {
        LocalDate today = today();
        String nurse = hospitalToken("demoa", "nurse.demo");
        String doctor = hospitalToken("demoa", "doctor.demo");
        long doctorId = userId("demoa", "doctor.demo");
        long planId = createAutoEnrollCyclePlan(nurse, PLAN_CODE + "-N");

        // ① 出院登记（存活）→ ② 入组：登记事务内自动加入预置计划（Q35 / A 规格第 5 章第 1 步）
        long patientId = registerPatient("N1", today.minusDays(10));
        long enrollmentId = enrollmentIdOf(patientId, planId);
        assertEquals("ACTIVE", enrollmentStatusOf(enrollmentId), "出院登记自动入组（Q35）");

        // ③ 任务：窗口已开启未结束的次数入组当天正常创建（E14-T05）；病区未配默认负责人 → 公共池（OQ-60 回落链）
        long taskId = mainTaskId(patientId, 1);
        assertEquals("NORMAL", createTypeOf(taskId), "窗口已开启未结束 → 入组当天 NORMAL 创建");
        assertEquals("PENDING", statusOf(taskId), "创建即待随访");
        assertEquals(today, windowStartOf(taskId), "窗口开始 = 今天");
        assertEquals(today.plusDays(3), windowEndOf(taskId), "窗口 [今天, 今天+3]");
        assertNull(assigneeOf(taskId), "病区无默认负责人 → 任务进病区待认领池");
        assertEquals(1, outboxCount(patientId, "TaskOpened"), "入组建任务写 TaskOpened 事件");

        // 任务开启通知未接线（驼峰事件无订阅方）：分发后保持 PENDING 不丢、不投站内信（OQ-78④ / OQ-60③，随 E15-T02 统一）
        long unreadBefore = unreadCount(doctor);
        runInTenant("demoa", () -> dispatcher.dispatchForCurrentTenant());
        assertEquals("PENDING", outboxStatusOf(patientId, "TaskOpened"), "无订阅方事件分发后保持 PENDING");
        assertEquals(0, inboxCountByTarget(taskId, "TASK_OPENED"), "任务开启站内信未接线（既定口径）");
        assertEquals(unreadBefore, unreadCount(doctor), "本轮分发不产生新未读");

        // ④ 沟通「未联系到」：任务进行中 + 同事务跟进任务（次日开启、窗口继承）+ UNREACHED_FOLLOWUP 事件（A-FU-006 / A-FU-024）
        Map<?, ?> contact =
                postContact(patientId, nurse, taskId, "NOT_REACHED", today.atTime(9, 0));
        assertEquals(0, codeOf(contact), "沟通记录失败：" + contact);
        Long followupId = followupIdOf(contact);
        assertNotNull(followupId, "未联系到自动生成跟进任务");
        assertEquals("IN_PROGRESS", statusOf(taskId), "第一次联系 → 进行中（§5 第 3 步）");
        assertEquals(today.plusDays(1), openDateOf(followupId), "跟进任务次日开启");
        assertEquals(today.plusDays(3), windowEndOf(followupId), "跟进任务窗口继承源任务");
        assertEquals(1, outboxCount(patientId, "UNREACHED_FOLLOWUP"), "写一条跟进事件");

        // ⑤ 通知（跟进）：分发 → 通知规则引擎消费事件，负责人缺失 → 病区成员兜底收站内信（A-NTF-004）
        runInTenant("demoa", () -> dispatcher.dispatchForCurrentTenant());
        assertEquals("SENT", outboxStatusOf(patientId, "UNREACHED_FOLLOWUP"), "事件被通知引擎消费");
        assertEquals(
                1,
                inboxCountByTarget(followupId, "UNREACHED_FOLLOWUP"),
                "站内信跳转落点 = 跟进任务（A-NTF-002）");

        // 通知中心 HTTP：病区成员登录看到未读消息（姓名脱敏、落点随消息下发），标已读后未读回落
        Map<?, ?> message = inboxMessageByTarget(doctor, followupId);
        assertEquals("UNREACHED_FOLLOWUP", message.get("eventType"));
        String patientName = String.valueOf(message.get("patientName"));
        assertTrue(
                patientName.startsWith("周") && !patientName.contains("随访"),
                "患者姓名脱敏：" + patientName);
        assertEquals(Boolean.FALSE, message.get("read"), "新消息未读");
        assertEquals(unreadBefore + 1, unreadCount(doctor), "未读数 +1（本链路新消息）");
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/notify/messages/" + message.get("id") + "/read",
                                doctor,
                                Map.of())));
        assertEquals(unreadBefore, unreadCount(doctor), "标已读后未读回落");

        // ⑥ 窗口结束次日每日调度：主任务与跟进任务一并逾期 + 各写一条 TASK_OVERDUE 事件（§5 第 5 步「变为逾期，并通知」）
        creationService.runForDate(today.plusDays(4));
        assertEquals("OVERDUE", statusOf(taskId), "窗口结束仍未完成 → 逾期");
        assertEquals("OVERDUE", statusOf(followupId), "跟进任务同窗口一并逾期");
        assertEquals(2, outboxCount(patientId, "TASK_OVERDUE"), "两个任务各一条逾期事件");

        // ⑦ 通知（逾期）：站内信即时 + 短信不即时发只落台账 QUEUED（A 规格：同日多条短信并入每日汇总）
        runInTenant("demoa", () -> dispatcher.dispatchForCurrentTenant());
        assertEquals(1, inboxCountByTarget(taskId, "TASK_OVERDUE"), "主任务逾期站内信落点");
        assertEquals(1, inboxCountByTarget(followupId, "TASK_OVERDUE"), "跟进任务逾期站内信落点");
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM ntf_send_log WHERE event_type = 'TASK_OVERDUE'"
                                + " AND recipient_id = ? AND channel = 'SMS' AND send_status = 'QUEUED'",
                        doctorId),
                "逾期短信台账 QUEUED ×2（待汇总合并发送）");

        // ⑧ 每日汇总作业：站内信汇总（数量与短信一致）+ 台账出队（本地通道未启用记 FAILED，行不丢可重试）
        runInTenant("demoa", () -> digestService.digest(today.atTime(10, 0)));
        Map<?, ?> digest = inboxMessageByEvent(doctor, "DAILY_DIGEST");
        assertEquals("每日汇总", digest.get("title"));
        assertTrue(
                String.valueOf(digest.get("summary")).contains("2 项随访待处理"),
                "汇总含当日待办数：" + digest.get("summary"));
        assertTrue(
                String.valueOf(digest.get("summary")).contains("逾期 2 项"),
                "汇总含逾期数：" + digest.get("summary"));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM ntf_send_log WHERE event_type = 'TASK_OVERDUE'"
                                + " AND recipient_id = ? AND channel = 'SMS' AND send_status = 'QUEUED'",
                        doctorId),
                "汇总后短信台账出队（不再 QUEUED）");
        assertEquals(
                2,
                count(
                        "SELECT COUNT(*) FROM ntf_send_log WHERE event_type = 'TASK_OVERDUE'"
                                + " AND recipient_id = ? AND channel = 'SMS' AND send_status = 'FAILED'"
                                + " AND detail LIKE '%CHANNEL_DISABLED%'",
                        doctorId),
                "本地通道未启用记 FAILED（行不丢，通道接入后可重试）");
        // 链路收尾：TaskOpened 全程保持 PENDING（订阅方上线即消费，不丢）
        assertEquals("PENDING", outboxStatusOf(patientId, "TaskOpened"));
    }

    // ---------------------------------------------------------------- 旅程 2：出院登记 → 入组 → 任务 →
    // 沟通（已联系）→ 录入提交完成（通知静默）

    /**
     * 顺利链（§5 第 3/4 步 + 通知静默）。同一预置计划仍发布：登记第二位患者 → 自动入组 → 公共池认领 →「已联系」不生成跟进 → 工作台开始录入并提交随访表单 → 窗口内完成
     * ON_TIME；已完成的任务窗口结束后不进逾期通知。
     */
    @Test
    void reachedJourneyCompletesTaskThroughWorkbenchWithoutNotifications() {
        LocalDate today = today();
        String nurse = hospitalToken("demoa", "nurse.demo");
        String doctor = hospitalToken("demoa", "doctor.demo");
        long doctorId = userId("demoa", "doctor.demo");
        createAutoEnrollCyclePlan(nurse, PLAN_CODE + "-R");

        long patientId = registerPatient("R1", today.minusDays(10));
        long taskId = mainTaskId(patientId, 1);
        assertEquals("PENDING", statusOf(taskId), "登记 → 自动入组 → 入组即建任务");
        assertNull(assigneeOf(taskId), "公共池待认领");

        // 认领（公共池）→ 负责人 = 认领人
        Map<?, ?> claimed =
                post("/api/hospital/followup/tasks/" + taskId + "/claim", doctor, Map.of());
        assertEquals(0, codeOf(claimed), "认领失败：" + claimed);
        assertEquals(doctorId, assigneeOf(taskId), "认领后负责人 = 认领人");

        // 沟通「已联系」：进行中、不生成跟进任务、不写跟进事件（A-FU-006）
        Map<?, ?> contact = postContact(patientId, doctor, taskId, "REACHED", today.atTime(9, 0));
        assertEquals(0, codeOf(contact), "沟通记录失败：" + contact);
        assertNull(followupIdOf(contact), "已联系不生成跟进任务");
        assertEquals("IN_PROGRESS", statusOf(taskId), "第一次联系 → 进行中");
        assertEquals(0, outboxCount(patientId, "UNREACHED_FOLLOWUP"), "不写跟进事件");

        // 工作台开始录入 → 提交随访表单 → 窗口内完成 ON_TIME（§5 第 4 步；提交字段与 FollowupWorkbenchIT 同构）
        Map<?, ?> started =
                post("/api/hospital/followup/tasks/" + taskId + "/start", doctor, Map.of());
        assertEquals(0, codeOf(started), "开始录入失败：" + started);
        String recordId = String.valueOf(data(started).get("recordId"));
        assertTrue(!recordId.isBlank(), "返回 recordId 路由");
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

        // 通知静默：顺利链只写 TaskOpened（未接线保持 PENDING），无跟进 / 逾期事件，站内信无该患者任务落点
        assertEquals(1, outboxCount(patientId, "TaskOpened"), "只有任务开启事件（未接线）");
        assertEquals(0, outboxCount(patientId, "UNREACHED_FOLLOWUP"));
        assertEquals(0, outboxCount(patientId, "TASK_OVERDUE"));
        assertEquals(0, inboxCountByTarget(taskId, null), "无站内信落点该任务（顺利链不打扰）");

        // 窗口结束次日的每日调度：已完成的任务不进逾期刷新、不发逾期通知
        creationService.runForDate(today.plusDays(4));
        assertEquals("COMPLETED", statusOf(taskId), "已完成任务保持完成");
        assertEquals(0, outboxCount(patientId, "TASK_OVERDUE"), "不发逾期事件");
        runInTenant("demoa", () -> dispatcher.dispatchForCurrentTenant());
        assertEquals(0, inboxCountByTarget(taskId, "TASK_OVERDUE"), "无逾期站内信");
    }

    // ---------------------------------------------------------------- 辅助

    /** 预置计划（Q35 自动入组）：循环节点偏移 10 天、周期 10 天、当天提醒、窗口 [今天, 今天+3]、完整随访表单、病区默认责任人。 */
    private long createAutoEnrollCyclePlan(String token, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 全链路预置计划（" + code + "）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        body.put("autoEnroll", true);
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
        node.put("windowBeforeValue", 0);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 3);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", FORM);
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

    /** 出院登记到默认病区（HTTP；登记事务内自动入组，出院日期 = 起算日期）。 */
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

    private long enrollmentIdOf(long patientId, long planId) {
        return query(
                "SELECT id FROM fu_enrollment WHERE patient_id = ? AND plan_id = ?",
                Long.class,
                patientId,
                planId);
    }

    private String enrollmentStatusOf(long enrollmentId) {
        return query("SELECT status FROM fu_enrollment WHERE id = ?", String.class, enrollmentId);
    }

    /** 该患者某次的主任务 id（followup_seq = 0）。 */
    private long mainTaskId(long patientId, int occurrence) {
        return query(
                "SELECT id FROM fu_task WHERE patient_id = ? AND occurrence = ?"
                        + " AND followup_seq = 0",
                Long.class,
                patientId,
                occurrence);
    }

    private String createTypeOf(long taskId) {
        return query("SELECT create_type FROM fu_task WHERE id = ?", String.class, taskId);
    }

    private String statusOf(long taskId) {
        return query("SELECT status FROM fu_task WHERE id = ?", String.class, taskId);
    }

    private Long assigneeOf(long taskId) {
        return query("SELECT assignee_id FROM fu_task WHERE id = ?", Long.class, taskId);
    }

    private LocalDate openDateOf(long taskId) {
        return query("SELECT open_date FROM fu_task WHERE id = ?", LocalDate.class, taskId);
    }

    private LocalDate windowStartOf(long taskId) {
        return query("SELECT window_start FROM fu_task WHERE id = ?", LocalDate.class, taskId);
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

    /** 该患者该类型最新一条事件的处理状态（PENDING / SENT）。 */
    private String outboxStatusOf(long patientId, String eventType) {
        return query(
                "SELECT status FROM sys_outbox WHERE event_type = ?"
                        + " AND JSON_EXTRACT(payload, '$.patientId') = ?"
                        + " ORDER BY id DESC LIMIT 1",
                String.class,
                eventType,
                patientId);
    }

    /** 落点为该任务的站内信条数（本租户；eventType 为 null 时不区分事件）。 */
    private int inboxCountByTarget(long taskId, String eventType) {
        StringBuilder sql =
                new StringBuilder("SELECT COUNT(*) FROM ntf_message WHERE target_type = 'TASK'");
        List<Object> args = new java.util.ArrayList<>();
        sql.append(" AND target_id = ?");
        args.add(taskId);
        if (eventType != null) {
            sql.append(" AND event_type = ?");
            args.add(eventType);
        }
        return runInTenant(
                "demoa", () -> jdbc.queryForObject(sql.toString(), Integer.class, args.toArray()));
    }

    /** 通知中心 HTTP：找落点为该任务的本人消息（找不到 fail）。 */
    private Map<?, ?> inboxMessageByTarget(String token, long taskId) {
        for (Map<?, ?> message : inboxItems(token)) {
            Map<?, ?> target = (Map<?, ?>) message.get("target");
            if (target != null
                    && "TASK".equals(target.get("type"))
                    && String.valueOf(taskId).equals(String.valueOf(target.get("id")))) {
                return message;
            }
        }
        throw new AssertionError("站内信缺少落点 TASK:" + taskId + " 的消息");
    }

    /** 通知中心 HTTP：找本人某事件类型的最新消息（找不到 fail）。 */
    private Map<?, ?> inboxMessageByEvent(String token, String eventType) {
        for (Map<?, ?> message : inboxItems(token)) {
            if (eventType.equals(message.get("eventType"))) {
                return message;
            }
        }
        throw new AssertionError("站内信缺少事件 " + eventType + " 的消息");
    }

    /** 本人站内信一页（id 倒序；取大页避免历史消息挤出本链路条目）。 */
    private List<Map<?, ?>> inboxItems(String token) {
        Map<?, ?> page = get("/api/hospital/notify/messages?pageSize=50", token);
        List<?> items = (List<?>) data(page).get("items");
        @SuppressWarnings("unchecked")
        List<Map<?, ?>> messages = (List<Map<?, ?>>) (List<?>) items;
        return messages;
    }

    private long unreadCount(String token) {
        Map<?, ?> count = data(get("/api/hospital/notify/messages/unread-count", token));
        return ((Number) count.get("unread")).longValue();
    }

    private long defaultWardId() {
        return query("SELECT MIN(id) FROM ward", Long.class);
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

    private Map<?, ?> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
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
