package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
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
 * E15-T10 M2 回归（tasks.csv 验收「M2 完成标准逐项通过」；本行未关联需求编号，属 M2 回归收尾，同 E06-T12 口径）。
 *
 * <p>M2 完成标准（tech-plan §16）：「医院 A 能出院登记、录入基线、自动入组、按日生成任务、沟通记录、收到通知」——六项逐项各一个用例，
 * 挂在同一名患者的一条连续动线上（hospital-a-spec §5 首段的整段描述： 出院建档 → 入组后不预生成任务 → 每日定时任务在开启日创建 → 每次联系写沟通记录 → 通知到人）：
 *
 * <ol>
 *   <li><b>出院登记</b>：HTTP 登记（A-PAT-003 最小必填集），同病案号重复登记 40902（查重，A-PAT-007）；
 *   <li><b>录入基线</b>：基线全量视图（8 分类全 EMPTY）→ 暂存草稿（f013 身高 / f014 体重，A-PAT-003 可分次补录）→ 硬范围外 42200
 *       （A-FLD-003）→ 正式保存（派生重算 f015 BMI、清草稿、A-PAT-019 完整度重算）→ 字段级历史（A-PAT-005 旧值 → 新值）；
 *   <li><b>自动入组</b>：登记事务内加入预置计划（Q35）、ACTIVE；窗口未开启不预建任务（§5「入组后不预先生成任务」）， 入组缓存下次计划日期 / 开启日；
 *   <li><b>按日生成任务</b>：开启日前一日调度不建 → 开启日当天调度建当期任务（PENDING / NORMAL、日期组齐全）， job_tenant_run 记
 *       SUCCESS、TaskOpened 事件、每日调度 TASK_CREATE 审计；
 *   <li><b>沟通记录</b>：认领 →「未联系到」沟通（A-FU-006）：任务进行中、同事务跟进任务（A-FU-024）， 沟通列表 HTTP 回读该记录；
 *   <li><b>收到通知</b>：分发 → UNREACHED_FOLLOWUP 被通知引擎消费（SENT），医生收件箱收到落点跟进任务的站内信（姓名脱敏）， 未读
 *       +1、标已读回落（A-NTF-001 / 002）。
 * </ol>
 *
 * <p>与既有用例的分工：E15-T09 M2DischargeToNotifyChainIT 两段旅程走「入组即建任务」（E14-T05 当期创建）并深挖通知（短信台账 / 每日汇总 /
 * 逾期）；FollowupTaskSchedulerIT 以服务层回拨入组日期锁 E14-T04 补跑与幂等；BaselineSectionServiceTest 是 mock
 * 单测。本套件的独有覆盖： <b>录入基线</b>的 HTTP 全链路（视图 → 草稿 → 保存 → 历史 → 完整度）此前没有任何集成用例；<b>按日生成任务</b>走「未来开启」真实路径
 * （登记不预建 → 开启日前一日不建 → 开启日建），与入组即建互补；六项挂在同一名患者上按业务顺序连成一条动线。
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（计划编码 PLAN-IT-E15T10 前缀、病案号 IT-E15T10- 前缀、
 * 基线分节与字段历史、任务 / 沟通 / 登记 / 计划审计、outbox 事件与站内信、job_tenant_run 本作业行）。全部虚构数据。后台 outbox 分发 tick 停用（1
 * 小时间隔），链路内同步 dispatchForCurrentTenant 保证断言确定（同 E15-T09）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.port=0", "crdp.outbox.dispatch.interval-ms=3600000"})
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class M2CoreRegressionIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E15T10";
    private static final String PLAN_CODE = "PLAN-IT-E15T10";
    private static final String FORM = "A_ANNUAL_FU";

    /** 首次计划日 = 出院日（今天−10）+ 20 天 = 今天+10：登记当天窗口未开启（验证「不预生成」），开启日在未来（验证按日创建）。 */
    private static final int NODE_OFFSET_DAYS = 20;

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FollowupTaskCreationService creationService;
    @Autowired private OutboxDispatcher dispatcher;

    /** 动线状态：六项用例共用（计划、患者、任务随动线推进写入）。 */
    private long planId;

    private long patientId;
    private long taskId;
    private long followupTaskId;

    // ---------------------------------------------------------------- 标准①：出院登记

    @Test
    @Order(1)
    void item1_dischargeRegistrationCreatesPatientAndRejectsDuplicate() {
        recycleLeftoverFromEarlierRun();
        String nurse = hospitalToken("demoa", "nurse.demo");
        planId = createAutoEnrollFuturePlan(nurse, PLAN_CODE);

        // 出院登记（HTTP，A-PAT-003 最小必填集；出院日期 = 起算日期）
        Map<String, Object> body = registrationBody("P1", today().minusDays(10));
        Map<?, ?> created = post("/api/hospital/followup/patients", nurse, body);
        assertEquals(0, codeOf(created), "出院登记失败：" + created);
        patientId = Long.parseLong((String) data(created).get("id"));
        assertTrue(patientId > 0, "返回患者 id");

        // 同病案号重复登记被查重拦截（A-PAT-007 盲索引唯一）
        Map<?, ?> duplicate =
                post(
                        "/api/hospital/followup/patients",
                        nurse,
                        registrationBody("P1", today().minusDays(10)));
        assertEquals(40902, codeOf(duplicate), "同病案号重复登记应返回 40902：" + duplicate);
    }

    // ---------------------------------------------------------------- 标准②：录入基线

    @Test
    @Order(2)
    void item2_baselineEntryDraftSaveHistoryAndCompleteness() {
        String doctor = hospitalToken("demoa", "doctor.demo");

        // 基线全量视图：按当前发布版模板列全部分节，登记只落实体列 → 8 分类全 EMPTY / v0、未锁定（E13-T02）
        Map<?, ?> view =
                data(get("/api/hospital/followup/patients/" + patientId + "/baseline", doctor));
        assertEquals("A_BASELINE", view.get("formCode"), "医院 A 基线表单（V38 字段字典）");
        Map<?, ?> lock = (Map<?, ?>) view.get("lock");
        assertEquals(Boolean.FALSE, lock.get("locked"), "新登记患者基线未锁定");
        List<?> sections = (List<?>) view.get("sections");
        assertEquals(8, sections.size(), "分类 1–8 各一节（patient.md §4）");
        for (Object section : sections) {
            assertEquals("EMPTY", ((Map<?, ?>) section).get("status"), "未保存过的分类输出 EMPTY");
        }

        // 暂存草稿：只带变更字段（f013 身高 / f014 体重），A-PAT-003「可分次补录」；首次暂存建行 baseVersion = 0
        Map<?, ?> drafted =
                put(
                        "/api/hospital/followup/baseline/" + patientId + "/sections/case/draft",
                        doctor,
                        Map.of("baseVersion", 0, "changes", Map.of("f013", 170, "f014", 70)));
        assertEquals(0, codeOf(drafted), "暂存草稿失败：" + drafted);
        assertEquals(1, ((Number) data(drafted).get("version")).intValue(), "首次暂存 v1");

        Map<?, ?> afterDraft = sectionOf(patientId, "case", doctor);
        assertEquals(
                "DONE",
                afterDraft.get("status"),
                "该分类无必填 json 字段（必填均在实体列），已填值即完整（A-PAT-019 应填为 0 视为完整）");
        assertEquals(Boolean.TRUE, afterDraft.get("hasDraft"), "存在晚于正式保存的草稿");
        assertEquals(170, ((Map<?, ?>) afterDraft.get("data")).get("f013"), "草稿值可回读");

        // 硬范围外不可保存（A-FLD-003：身高 hard max 220）
        Map<?, ?> hardRange =
                put(
                        "/api/hospital/followup/baseline/" + patientId + "/sections/case/draft",
                        doctor,
                        Map.of("baseVersion", 1, "changes", Map.of("f013", 300)));
        assertEquals(42200, codeOf(hardRange), "硬范围外应 42200：" + hardRange);

        // 正式保存（复核对身高，170 → 172）：派生重算（f015 BMI = 70 ÷ 1.72² ≈ 23.7，A-FLD-005）、清草稿、版本推进
        Map<?, ?> saved =
                put(
                        "/api/hospital/followup/baseline/" + patientId + "/sections/case",
                        doctor,
                        Map.of("baseVersion", 1, "data", Map.of("f013", 172, "f014", 70)));
        assertEquals(0, codeOf(saved), "正式保存失败：" + saved);
        Map<?, ?> savedSection = (Map<?, ?>) data(saved).get("section");
        assertEquals(2, ((Number) savedSection.get("version")).intValue(), "保存后 v2");
        assertEquals(Boolean.FALSE, savedSection.get("hasDraft"), "正式保存清除草稿");
        Map<?, ?> savedData = (Map<?, ?>) savedSection.get("data");
        assertEquals(172, ((Number) savedData.get("f013")).intValue(), "保存值落库");
        assertEquals(23.7, ((Number) savedData.get("f015")).doubleValue(), 0.001, "派生字段 BMI 随保存重算");

        // 字段级历史（A-PAT-005：谁、何时、旧值 → 新值；草稿不写、正式保存只写与上一版的差异——
        // 用户改的 f013 与随之重算的派生 f015 各一条）
        List<?> entries =
                (List<?>)
                        get(
                                        "/api/hospital/followup/baseline/"
                                                + patientId
                                                + "/sections/case/history",
                                        doctor)
                                .get("data");
        assertEquals(2, entries.size(), "差异 = 用户改动 + 派生重算（草稿不写历史）：" + entries);
        Map<?, ?> heightHistory = historyEntryOf(entries, "f013");
        assertEquals(170, ((Number) heightHistory.get("oldValue")).intValue(), "旧值 = 草稿值");
        assertEquals(172, ((Number) heightHistory.get("newValue")).intValue(), "新值 = 保存值");
        Map<?, ?> bmiHistory = historyEntryOf(entries, "f015");
        assertEquals(
                24.2, ((Number) bmiHistory.get("oldValue")).doubleValue(), 0.001, "BMI 旧值 = 草稿期派生");
        assertEquals(
                23.7, ((Number) bmiHistory.get("newValue")).doubleValue(), 0.001, "BMI 新值 = 重算值");

        // 完整度重算（A-PAT-019）：登记时 null → 保存后写入数值；本字典必填均在实体列（登记已填）→ 应填 json 必填为 0 视为 100
        Map<?, ?> summary =
                data(get("/api/hospital/followup/patients/" + patientId + "/summary", doctor));
        Object completeness = summary.get("baselineCompleteness");
        assertNotNull(completeness, "基线完整度已随分节保存写入");
        assertEquals(
                100, ((Number) completeness).intValue(), "应填为 0 视为 100（A-PAT-019）：" + completeness);
    }

    // ---------------------------------------------------------------- 标准③：自动入组

    @Test
    @Order(3)
    void item3_autoEnrollmentIsActiveWithoutPrecreatedTasks() {
        // 登记事务内自动加入预置计划（Q35）：在组、ACTIVE、起算日期 = 出院日期
        Map<String, Object> enrollment =
                queryMap(
                        "SELECT * FROM fu_enrollment WHERE patient_id = ? AND plan_id = ?",
                        patientId,
                        planId);
        assertNotNull(enrollment, "登记事务内自动入组（Q35）");
        assertEquals("ACTIVE", enrollment.get("status"), "入组即已启动");
        assertEquals(
                java.sql.Date.valueOf(today().minusDays(10)).toString(),
                String.valueOf(enrollment.get("start_date")).substring(0, 10),
                "起算日期 = 出院日期");

        // 入组后不预先生成任务（§5）：首次窗口今天+10 才开启 → 现在无任何任务
        assertEquals(
                0,
                count("SELECT COUNT(*) FROM fu_task WHERE patient_id = ?", patientId),
                "窗口未开启不预建任务");

        // 入组缓存：下次计划日期与开启日 = 今天+10（提醒 0 天 → 提醒开始 = 窗口开始）
        assertEquals(
                today().plusDays(NODE_OFFSET_DAYS - 10).toString(),
                String.valueOf(enrollment.get("next_plan_date")).substring(0, 10),
                "下次计划日期 = 出院 + 20 天");
        assertEquals(
                today().plusDays(NODE_OFFSET_DAYS - 10).toString(),
                String.valueOf(enrollment.get("next_open_date")).substring(0, 10),
                "下次开启日 = 提醒开始日与窗口开始日较早一天");
    }

    // ---------------------------------------------------------------- 标准④：按日生成任务

    @Test
    @Order(4)
    void item4_dailySchedulerCreatesTaskExactlyOnOpenDate() {
        LocalDate openDate = today().plusDays(NODE_OFFSET_DAYS - 10);

        // 开启日前一日：每日调度不建（未来节点不预生成），作业正常记 SUCCESS
        creationService.runForDate(openDate.minusDays(1));
        assertEquals(
                0,
                count("SELECT COUNT(*) FROM fu_task WHERE patient_id = ?", patientId),
                "开启日未到不创建");
        assertEquals(
                "SUCCESS",
                query(
                        "SELECT status FROM job_tenant_run WHERE job_code = 'fu-task-create'"
                                + " AND run_date = ?",
                        String.class,
                        openDate.minusDays(1)),
                "前一日作业执行台账 SUCCESS");

        // 开启日当天：创建当期任务（PENDING / NORMAL、日期组齐全）
        creationService.runForDate(openDate);
        taskId =
                query(
                        "SELECT id FROM fu_task WHERE patient_id = ? AND followup_seq = 0",
                        Long.class,
                        patientId);
        Map<String, Object> task = queryMap("SELECT * FROM fu_task WHERE id = ?", taskId);
        assertEquals("PENDING", task.get("status"), "创建即待随访（A-FU-003）");
        assertEquals("NORMAL", task.get("create_type"), "按日创建的当期任务");
        assertEquals("FORM", task.get("task_type"));
        assertEquals(FORM, task.get("form_code"), "任务挂随访表单");
        assertEquals(1, ((Number) task.get("occurrence")).intValue(), "第 1 次");
        assertEquals(
                openDate.toString(),
                String.valueOf(task.get("plan_date")).substring(0, 10),
                "计划日期");
        assertEquals(
                openDate.toString(), String.valueOf(task.get("open_date")).substring(0, 10), "开启日");
        assertEquals(
                openDate.toString(),
                String.valueOf(task.get("window_start")).substring(0, 10),
                "窗口开始");
        assertEquals(
                openDate.plusDays(3).toString(),
                String.valueOf(task.get("window_end")).substring(0, 10),
                "窗口 [开启日, 开启日+3]");
        assertEquals(
                openDate.toString(),
                String.valueOf(task.get("created_logical_date")).substring(0, 10),
                "逻辑创建日 = 开启日");
        assertNull(task.get("assignee_id"), "病区无默认负责人 → 公共池待认领");

        // 台账 / 事件 / 审计三件套
        assertEquals(
                "SUCCESS",
                query(
                        "SELECT status FROM job_tenant_run WHERE job_code = 'fu-task-create'"
                                + " AND run_date = ?",
                        String.class,
                        openDate),
                "开启日作业执行台账 SUCCESS");
        assertEquals(1, outboxCount(patientId, "TaskOpened"), "新建任务写 TaskOpened 事件");
        assertTrue(
                count(
                                "SELECT COUNT(*) FROM aud_log WHERE action = 'TASK_CREATE'"
                                        + " AND detail LIKE '%每日调度%'")
                        >= 1,
                "全租户汇总写每日调度 TASK_CREATE 审计");
    }

    // ---------------------------------------------------------------- 标准⑤：沟通记录

    @Test
    @Order(5)
    void item5_contactLogRecordsUnreachedCallWithFollowupTask() {
        LocalDate openDate = today().plusDays(NODE_OFFSET_DAYS - 10);
        String doctor = hospitalToken("demoa", "doctor.demo");
        long doctorId = userId("demoa", "doctor.demo");

        // 公共池认领 → 负责人 = 认领人（A-FU-009）
        Map<?, ?> claimed =
                post("/api/hospital/followup/tasks/" + taskId + "/claim", doctor, Map.of());
        assertEquals(0, codeOf(claimed), "认领失败：" + claimed);
        assertEquals(
                doctorId,
                query("SELECT assignee_id FROM fu_task WHERE id = ?", Long.class, taskId),
                "认领后负责人");

        // 「未联系到」沟通（A-FU-006）：任务进行中 + 同事务跟进任务（A-FU-024）
        Map<?, ?> contact =
                postContact(patientId, doctor, taskId, "NOT_REACHED", openDate.atTime(9, 0));
        assertEquals(0, codeOf(contact), "沟通记录失败：" + contact);
        followupTaskId = followupIdOf(contact);
        assertTrue(followupTaskId > 0, "未联系到自动生成跟进任务");
        assertEquals(
                "IN_PROGRESS",
                query("SELECT status FROM fu_task WHERE id = ?", String.class, taskId),
                "第一次联系 → 进行中（§5 状态流转）");
        assertEquals(
                openDate.plusDays(1).toString(),
                String.valueOf(
                                query(
                                        "SELECT open_date FROM fu_task WHERE id = ?",
                                        java.sql.Date.class,
                                        followupTaskId))
                        .substring(0, 10),
                "跟进任务次日开启");
        assertEquals(
                openDate.plusDays(3).toString(),
                String.valueOf(
                                query(
                                        "SELECT window_end FROM fu_task WHERE id = ?",
                                        java.sql.Date.class,
                                        followupTaskId))
                        .substring(0, 10),
                "跟进任务窗口继承源任务");
        assertEquals(1, outboxCount(patientId, "UNREACHED_FOLLOWUP"), "写一条跟进事件");

        // 沟通列表 HTTP 回读（A-FU-006：分页含方式、结果、时间）
        Map<?, ?> page =
                data(get("/api/hospital/followup/patients/" + patientId + "/contacts", doctor));
        assertEquals(1L, ((Number) page.get("total")).longValue(), "沟通列表共 1 条");
        Map<?, ?> row = (Map<?, ?>) ((List<?>) page.get("items")).get(0);
        assertEquals("PHONE", row.get("method"), "联系方式 = 电话");
        assertEquals("NOT_REACHED", row.get("result"), "结果 = 未联系到");
        assertEquals(String.valueOf(taskId), String.valueOf(row.get("taskId")), "关联主任务");
    }

    // ---------------------------------------------------------------- 标准⑥：收到通知

    @Test
    @Order(6)
    void item6_notificationReceivedInDoctorInbox() {
        String doctor = hospitalToken("demoa", "doctor.demo");
        // 标准⑤落库的跟进事件可能已被 5 秒一次的 outbox 定时分发（OutboxDispatchScheduler）
        // 先行消费——先看投递是否已发生，未读断言按两种时序分别收敛（竞态容错）
        boolean alreadyDelivered = inboxHasTarget(doctor, followupTaskId);
        long unreadBefore = unreadCount(doctor);

        // 分发 → 通知规则引擎消费跟进事件（负责人已认领 → 通知医生本人）
        runInTenant("demoa", () -> dispatcher.dispatchForCurrentTenant());
        assertEquals(
                "SENT", outboxStatusOf(patientId, "UNREACHED_FOLLOWUP"), "事件被通知引擎消费（A-NTF-001）");

        // 医生收件箱收到落点跟进任务的站内信（A-NTF-002 跳转落点），患者姓名脱敏
        Map<?, ?> message = inboxMessageByTarget(doctor, followupTaskId);
        assertEquals("UNREACHED_FOLLOWUP", message.get("eventType"));
        String patientName = String.valueOf(message.get("patientName"));
        assertTrue(
                patientName.startsWith("周") && !patientName.contains("随访"),
                "患者姓名脱敏：" + patientName);
        assertEquals(Boolean.FALSE, message.get("read"), "新消息未读");
        if (alreadyDelivered) {
            assertEquals(unreadBefore, unreadCount(doctor), "定时分发已先行投递：未读数不重复 +1");
        } else {
            assertEquals(unreadBefore + 1, unreadCount(doctor), "未读数 +1（本链路新消息）");
        }

        // 标已读 → 未读回落（alreadyDelivered 分支的未读基数已含本条：回落到 unreadBefore - 1；
        // 未先行投递分支本条是标已读前 +1 的新消息：回落到 unreadBefore——两种时序分别收敛）
        assertEquals(
                0,
                codeOf(
                        post(
                                "/api/hospital/notify/messages/" + message.get("id") + "/read",
                                doctor,
                                Map.of())));
        assertEquals(
                alreadyDelivered ? unreadBefore - 1 : unreadBefore,
                unreadCount(doctor),
                "标已读后未读回落");

        // 链路收尾：TaskOpened 无订阅方保持 PENDING（订阅方上线即消费，不丢——OQ-78④ / OQ-60③ 既定口径）
        assertEquals("PENDING", outboxStatusOf(patientId, "TaskOpened"));
    }

    // ---------------------------------------------------------------- 自建自清

    /** 回收早前中断运行残留（本类标记前缀）。审计先行（target 子查询要用还没删的行）。 */
    private void recycleLeftoverFromEarlierRun() {
        SecurityContextHolderClear.clear();
        runInTenant(
                "demoa",
                () -> {
                    List<Long> patientIds =
                            jdbc.queryForList(
                                    "SELECT id FROM pt_patient WHERE legacy_no LIKE ?",
                                    Long.class,
                                    LEGACY + "-%");
                    for (Long patientId : patientIds) {
                        jdbc.update(
                                "DELETE FROM sys_outbox WHERE event_type IN"
                                        + " ('TaskOpened', 'TASK_OVERDUE', 'UNREACHED_FOLLOWUP',"
                                        + " 'LOST_CANDIDATE')"
                                        + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                                patientId);
                    }
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
                                    + " 'TASK_FOLLOWUP_CREATE', 'CONTACT_CREATE')"
                                    + " AND target IN (SELECT CONCAT('TASK:', t.id) FROM fu_task t"
                                    + " JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (event_type = 'CONTACT_CREATE'"
                                    + " AND target IN (SELECT CONCAT('CONTACT_LOG:', c.id)"
                                    + " FROM fu_contact_log c JOIN pt_patient p"
                                    + " ON p.id = c.patient_id WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (event_type IN ('BASELINE_SECTION_SAVE', 'BASELINE_DRAFT_OVERWRITE')"
                                    + " AND target IN (SELECT CONCAT('PT_BASELINE_SECTION:', s.id)"
                                    + " FROM pt_baseline_section s JOIN pt_patient p"
                                    + " ON p.id = s.patient_id WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (action = 'TASK_CREATE' AND detail LIKE '%每日调度%')");
                    // 站内信落点限定本动线任务，不整表清（通知中心为共享页面）
                    jdbc.update(
                            "DELETE FROM ntf_message WHERE target_type = 'TASK' AND target_id IN"
                                    + " (SELECT t.id FROM fu_task t JOIN pt_patient p"
                                    + " ON p.id = t.patient_id WHERE p.legacy_no LIKE ?)",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE fh FROM fe_field_history fh"
                                    + " JOIN pt_baseline_section s ON s.id = fh.record_id"
                                    + " JOIN pt_patient p ON p.id = s.patient_id"
                                    + " WHERE fh.record_type = 'PT_BASELINE_SECTION'"
                                    + " AND p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE s FROM pt_baseline_section s JOIN pt_patient p"
                                    + " ON p.id = s.patient_id WHERE p.legacy_no LIKE ?",
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
        runInTenant(
                "demob",
                () -> jdbc.update("DELETE FROM job_tenant_run WHERE job_code = 'fu-task-create'"));
    }

    @AfterAll
    void cleanUp() {
        recycleLeftoverFromEarlierRun();
    }

    // ---------------------------------------------------------------- 辅助

    /** 预置计划（Q35 自动入组）：循环节点偏移 20 天、周期 10 天、当天提醒、窗口 [计划日, 计划日+3]、完整随访表单、病区默认责任人。 */
    private long createAutoEnrollFuturePlan(String token, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 M2 回归预置计划（" + code + "）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        body.put("autoEnroll", true);
        Map<?, ?> created = post("/api/hospital/followup/plans", token, body);
        assertEquals(0, codeOf(created), "新建计划失败：" + created);
        long planId = Long.parseLong((String) data(created).get("id"));

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
        node.put("offsetValue", NODE_OFFSET_DAYS);
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

    /** 出院登记请求体（A-PAT-003 最小必填集，HTTP；病案号 = legacy 前缀便于自建自清）。 */
    private Map<String, Object> registrationBody(String suffix, LocalDate discharge) {
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
        return body;
    }

    /** 基线全量视图里找指定分类的分节（找不到 fail）。 */
    private Map<?, ?> sectionOf(long patientId, String code, String token) {
        Map<?, ?> view =
                data(get("/api/hospital/followup/patients/" + patientId + "/baseline", token));
        for (Object section : (List<?>) view.get("sections")) {
            if (code.equals(((Map<?, ?>) section).get("code"))) {
                return (Map<?, ?>) section;
            }
        }
        throw new AssertionError("基线视图缺少分类 " + code);
    }

    /** 历史条目里找指定字段（找不到 fail）。 */
    private static Map<?, ?> historyEntryOf(List<?> entries, String fieldKey) {
        for (Object entry : entries) {
            if (fieldKey.equals(((Map<?, ?>) entry).get("fieldKey"))) {
                return (Map<?, ?>) entry;
            }
        }
        throw new AssertionError("字段历史缺少 " + fieldKey + "：" + entries);
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

    /** 通知中心 HTTP：落点为该任务的本人消息是否已存在（定时分发可能先行投递，竞态容错用）。 */
    private boolean inboxHasTarget(String token, long taskId) {
        Map<?, ?> page = get("/api/hospital/notify/messages?pageSize=50", token);
        for (Map<?, ?> message : (List<Map<?, ?>>) (List<?>) data(page).get("items")) {
            Map<?, ?> target = (Map<?, ?>) message.get("target");
            if (target != null
                    && "TASK".equals(target.get("type"))
                    && String.valueOf(taskId).equals(String.valueOf(target.get("id")))) {
                return true;
            }
        }
        return false;
    }

    /** 通知中心 HTTP：找落点为该任务的本人消息（找不到 fail）。 */
    private Map<?, ?> inboxMessageByTarget(String token, long taskId) {
        Map<?, ?> page = get("/api/hospital/notify/messages?pageSize=50", token);
        for (Map<?, ?> message : (List<Map<?, ?>>) (List<?>) data(page).get("items")) {
            Map<?, ?> target = (Map<?, ?>) message.get("target");
            if (target != null
                    && "TASK".equals(target.get("type"))
                    && String.valueOf(taskId).equals(String.valueOf(target.get("id")))) {
                return message;
            }
        }
        throw new AssertionError("站内信缺少落点 TASK:" + taskId + " 的消息");
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

    /** 租户上下文内的单行查询（列名 → 值）。 */
    private Map<String, Object> queryMap(String sql, Object... args) {
        return runInTenant("demoa", () -> jdbc.queryForMap(sql, args));
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
