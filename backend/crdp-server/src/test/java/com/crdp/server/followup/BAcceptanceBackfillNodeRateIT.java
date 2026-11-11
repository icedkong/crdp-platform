package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.stats.app.NodeOccurrenceRefreshService;
import com.crdp.followup.task.app.FollowupTaskStatusService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E23-T17 验收（端到端 / 真实 MySQL）：医院 B「补录任务与节点随访率」（tasks.csv 验收原文「验收用例通过」， 关联需求 A-DASH-012；用例集与执行面见
 * docs/testing/hospital-b-acceptance.md，锚点 B-1~B-7）。
 *
 * <p>基准：A 规格 §14「术后第 1 / 3 / 5 天任务」表（Q43 / Q44：建档时已过窗口的补建「补录」任务、 不进逾期、可直接录入、提交后「已完成（补录）」）与
 * §14「统计」行（补录按 Q38 口径照常计入）； A-DASH-012 节点随访率（各节点应随访 / 已随访 / 随访率，逾期完成与补录计入已随访）与 A-DASH-013 首页开启（B
 * 初始化后在首页卡片配置中开启；A 默认不显示由目录 default_visible=0 保证，V77）。
 *
 * <p>化身口径同 {@link BPresetPlanVerifyIT}：demob 作医院 B 化身（E23-T15 runbook §3）， 本类 @BeforeAll 走一遍激活（停 A
 * 预置 + 发布 B 预置，E23-T16 runbook §3 机器演练）， 再走出院登记 → 补录行为 → 事实表刷新 → 节点随访率卡片 → 首页开启的完整验收动线：
 *
 * <ol>
 *   <li>B-1 激活与缺省计划：B 预置发布后 node_rate 缺省 planId = B 预置（id 最小的已发布版本， OQ-109③；A 预置停用退出候选）、GET
 *       /auth/features 下发 node_follow_rate=true、开关关闭 40301（回归）；
 *   <li>B-2 补录创建（B-8 TaskOpened 回归内联）：三名虚构患者（手术 8 天 / 14 个月 / 14 个月前）出院登记—— 早期与已过窗口节点补建 BACKFILL
 *       任务（Q44）；{@link FollowupTaskStatusService#refreshOverdue} 刷新后补录任务仍待随访、无 TASK_OVERDUE
 *       事件（不进逾期，A 规格 §14「不进入逾期、不发逾期类通知」）；
 *   <li>B-3 可直接录入并完成「已完成（补录）」：start 录入（不受直接录入期拦截，FIXED 无下一期）→ 提交 完整随访记录表单（f214 实际随访日期落原窗口内，Q43）→ 任务
 *       COMPLETED、complete_kind=OVERDUE （提交日在窗口后）而 create_type 仍
 *       BACKFILL（前端派生「已完成（补录）」，packages/api task.ts 已锁）；
 *   <li>B-5 补录计入节点随访率（Q38）：事实表刷新后 node_rate 各节点行 = 手工总表（应随访含未完成补录、
 *       已随访含补录完成、窗口内完成按实际随访日期）；yearly_rate 第 1 年与 node_rate 年度行一致 （同一事实表，「与每年随访率的关系」）；行标签：固定节点 =
 *       节点名、年度循环 = 第 N 年（OQ-109③）；
 *   <li>B-6 首页开启（A-DASH-013）：开启前默认布局无 node_rate（default_visible=0）→ 租户管理员 PUT role-layouts 逐角色开启 →
 *       GET /dashboard/layout 即生效（配置即时生效，V77）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（病案号 / legacy_no 前缀 IT-E23T17-）； demob
 * 的预置计划行由 @BeforeAll / @AfterAll 恢复随包状态（B 预置 UNPUBLISHED + A 预置 PUBLISHED， 双院配置比对 IT
 * 依赖两库一致）。全部虚构数据（示例）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BAcceptanceBackfillNodeRateIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String LEGACY = "IT-E23T17";

    private static final String A_PLAN_CODE = "A_ANNUAL_FU_PLAN";

    private static final String B_PLAN_CODE = "B_POSTOP_FU_PLAN";

    /** demob 租户管理员的 TENANT_ADMIN 角色（种子固定 role_id = 1，B-5 角色布局用）。 */
    private static final long ADMIN_ROLE_ID = 1L;

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    @Autowired private FollowupTaskStatusService statusService;

    @Autowired private NodeOccurrenceRefreshService refreshService;

    private long bPlanId;

    private long aPlanIdInB;

    /** B-3 / B-4 用的患者 id（@Order(2) 登记，后续用例续用）。 */
    private long patientEightDays;

    private long patientFourteenMonths;

    private long patientYearSubmit;

    @BeforeAll
    void prepare() {
        bPlanId = planIdIn("demob", B_PLAN_CODE);
        aPlanIdInB = planIdIn("demob", A_PLAN_CODE);
        recycleLeftoverFromEarlierRun();
        restorePresetStates();
        // 事实表刷新作业按 (jobCode, logicalDate) 幂等——清掉当日运行记录保证本类刷新真实执行（两租户）
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () ->
                            jdbc.update(
                                    "DELETE FROM job_tenant_run WHERE job_code = 'st-node-refresh'"));
        }
        // demoa：其它 IT 可能临时关掉 A 预置自动入组——恢复随包状态（BPresetPlanVerifyIT 同口径）
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_plan SET auto_enroll = 1 WHERE code = ?"
                                        + " AND status = 'PUBLISHED'",
                                A_PLAN_CODE));
    }

    @AfterAll
    void cleanUp() {
        recycleLeftoverFromEarlierRun();
        restorePresetStates();
    }

    // ---------------------------------------------------------------- B-1 激活与缺省计划（B-7 开关 40301
    // 回归内联）

    /** 激活（E23-T16 §3：停 A 预置 + 发布 B 预置）后：node_rate 缺省计划 = B 预置、候选只剩已发布计划、开关面可用。 */
    @Test
    @Order(1)
    void b1ActivationMakesBPresetDefaultPlanAndFeatureOn() {
        String admin = hospitalToken("demob", "nurse.demo");
        assertEquals(
                0, codeOf(post("/api/hospital/followup/plans/" + aPlanIdInB + "/disable", admin)));
        assertEquals(
                0, codeOf(post("/api/hospital/followup/plans/" + bPlanId + "/publish", admin)));

        // GET /auth/features 下发开关（OQ-03；演示化身 = 种子 V100，生产 = 配置模板 cfg_feature 复制链，E23-T15）
        Map<?, ?> features = data(get("/api/hospital/auth/features", admin));
        assertEquals(Boolean.TRUE, features.get("node_follow_rate"), "B 侧 node_follow_rate 开");

        // 缺省计划 = id 最小的已发布版本 = B 预置（A 预置 DISABLED 退出候选，OQ-109③）
        Map<?, ?> card = data(get(cardPath("node_rate"), admin));
        assertEquals(bPlanId, num(card.get("planId")), "缺省计划 = B 预置");
        assertEquals("心脏术后随访（医院 B）", card.get("planName"));
        List<?> plans = (List<?>) card.get("plans");
        assertEquals(1, plans.size(), "候选只剩已发布计划（A 预置停用后退出）");
        assertEquals(bPlanId, num(((Map<?, ?>) plans.get(0)).get("id")));

        // 开关兜底回归（AGENTS §5.7）：关闭 → 40301；恢复后可用
        runInTenant(
                "demob",
                () ->
                        jdbc.update(
                                "UPDATE cfg_feature SET enabled = 0"
                                        + " WHERE feature_code = 'node_follow_rate'"));
        try {
            assertEquals(
                    40301,
                    codeOf(get(cardPath("node_rate"), admin).getBody()),
                    "开关关闭 → 40301（前端 GET /auth/features 判定后隐藏）");
        } finally {
            runInTenant(
                    "demob",
                    () ->
                            jdbc.update(
                                    "UPDATE cfg_feature SET enabled = 1"
                                            + " WHERE feature_code = 'node_follow_rate'"));
        }
        assertEquals(0, codeOf(get(cardPath("node_rate"), admin).getBody()), "恢复后卡片可用");
    }

    // ---------------------------------------------------------------- B-2 补录创建（B-8 TaskOpened
    // 回归内联）

    /**
     * 三名虚构患者出院登记（Q44）：甲手术 8 天前（第 1 / 3 / 5 天窗口均已结束）、乙丙手术 14 个月前 （第 1 / 3 / 5 天、3 个月、第 1 年窗口均已结束，第 2
     * 年未到）；补建 BACKFILL 任务且逾期刷新豁免。
     */
    @Test
    @Order(2)
    void b2BackfillTasksCreatedAndExemptFromOverdue() {
        LocalDate today = LocalDate.now();
        patientEightDays = registerPatient("A", today.minusDays(8), today);
        patientFourteenMonths = registerPatient("B", today.minusMonths(14), today);
        patientYearSubmit = registerPatient("C", today.minusMonths(14), today);

        runInTenant(
                "demob",
                () -> {
                    // 补录任务落库形态（行级参数已由 BPresetPlanVerifyIT 锁定，这里核验收面的计数与豁免）
                    assertEquals(3, taskCount(patientEightDays), "甲：第 1 / 3 / 5 天补建（未来节点不预生成）");
                    assertEquals(
                            5,
                            taskCount(patientFourteenMonths),
                            "乙：第 1 / 3 / 5 天 + 3 个月 + 第 1 年补建（第 2 年未到不建）");
                    assertEquals(5, taskCount(patientYearSubmit), "丙：同乙（第 1 年留待 B-3 提交）");
                    assertEquals(
                            13,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_task t JOIN pt_patient p"
                                                    + " ON p.id = t.patient_id WHERE p.legacy_no LIKE ?"
                                                    + " AND t.create_type = 'BACKFILL'",
                                            Integer.class,
                                            LEGACY + "-%")
                                    .intValue(),
                            "全部为补录任务（Q44：建档时窗口已结束照常创建、标补录）");

                    // B-3：逾期刷新（A 规格 §14「不进入逾期、不发逾期类通知」）：补录豁免
                    int marked = statusService.refreshOverdue(today);
                    assertEquals(0, marked, "本类补录任务全部豁免（库里无其它候选，刷新标记数 = 0）");
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_task t"
                                                    + " JOIN pt_patient p ON p.id = t.patient_id"
                                                    + " WHERE p.legacy_no LIKE ?"
                                                    + " AND t.status <> 'PENDING'",
                                            Integer.class,
                                            LEGACY + "-%")
                                    .intValue(),
                            "补录任务刷新后仍待随访（不置 OVERDUE）");
                    assertEquals(
                            0, eventCount("TASK_OVERDUE"), "不发逾期类事件（通知规则引擎无 TASK_OVERDUE 可消费）");
                    assertEquals(
                            0,
                            eventCount("TaskOpened"),
                            "补录创建也不写 TaskOpened（BPresetPlanVerifyIT 已锁，验收面回归）");
                });
    }

    // ---------------------------------------------------------------- B-4 可直接录入并完成「已完成（补录）」

    /** 甲补术后第 1 天（f214 = 计划日，落原窗口内）、丙补第 1 年（f214 = 计划日）：提交后已完成（补录）。 */
    @Test
    @Order(3)
    void b3BackfillDirectEntryCompletesAsBackfillKind() {
        String admin = hospitalToken("demob", "nurse.demo");
        LocalDate today = LocalDate.now();

        // 甲 · 术后第 1 天：窗口 [手术+1, 手术+2] = [today-7, today-6]，提交日 today 在窗口后
        long dayOne = runInTenant("demob", () -> taskIdOf(patientEightDays, "术后第 1 天"));
        Map<?, ?> submittedDayOne = submitBackfill(admin, dayOne, today.minusDays(7));
        assertEquals("COMPLETED", data(submittedDayOne).get("taskStatus"), "提交后任务完成");
        assertEquals(
                "OVERDUE",
                data(submittedDayOne).get("completeKind"),
                "完成方式按提交日判定（窗口后提交 = 逾期完成，fu_task.complete_kind）");

        // 丙 · 第 1 年：窗口 [today-3 月, today-1 月]，提交日 today 在窗口后但在直接录入期内（第 2 年未开启）
        long yearOne = runInTenant("demob", () -> taskIdOf(patientYearSubmit, "电话随访"));
        Map<?, ?> submittedYear = submitBackfill(admin, yearOne, today.minusMonths(2));
        assertEquals("COMPLETED", data(submittedYear).get("taskStatus"));
        assertEquals("OVERDUE", data(submittedYear).get("completeKind"));

        runInTenant(
                "demob",
                () -> {
                    // 补录身份保留（前端 taskDisplayStatus 由 createType=BACKFILL 优先派生「已完成（补录）」，
                    // packages/api/src/task.spec.ts 已锁；统计事实行强制
                    // completeKind=BACKFILL，NodeOccurrenceFactCalculator）
                    Map<String, Object> dayOneRow = taskRow(dayOne);
                    assertEquals("BACKFILL", dayOneRow.get("create_type"), "补录身份不因完成丢失");
                    assertEquals("COMPLETED", dayOneRow.get("status"));
                    assertEquals("OVERDUE", dayOneRow.get("complete_kind"), "任务行完成方式 = 逾期完成");
                    Map<String, Object> yearRow = taskRow(yearOne);
                    assertEquals("BACKFILL", yearRow.get("create_type"));
                    assertEquals("COMPLETED", yearRow.get("status"));
                });
    }

    // ---------------------------------------------------------------- B-5 补录计入节点随访率（Q38）

    /**
     * 手工总表（甲 = 8 天前手术；乙丙 = 14 个月前手术；甲补第 1 天、丙补第 1 年、乙未补）：
     *
     * <table border="1">
     *   <tr><th>节点行</th><th>应随访 due</th><th>已随访 followed</th><th>窗口内完成 inWindow</th></tr>
     *   <tr><td>术后第 1 天（固定）</td><td>3（甲乙丙）</td><td>1（甲补录完成）</td><td>1（甲 f214 落窗口内）</td></tr>
     *   <tr><td>术后第 3 天（固定）</td><td>3</td><td>0</td><td>0</td></tr>
     *   <tr><td>术后第 5 天（固定）</td><td>3</td><td>0</td><td>0</td></tr>
     *   <tr><td>术后 3 个月（固定）</td><td>2（乙丙；甲窗口未结束不入分母）</td><td>0</td><td>0</td></tr>
     *   <tr><td>第 1 年（年度循环）</td><td>2（乙丙）</td><td>1（丙补录完成）</td><td>1（丙 f214 落窗口内）</td></tr>
     * </table>
     *
     * <p>Q38：分母 = 窗口已结束的应随访（补录照常计入）；分子 = 已随访（含补录与逾期完成）； 窗口内完成按实际随访日期（f214）判定，与完成方式无关。
     */
    @Test
    @Order(4)
    void b4NodeRateCountsBackfillAsDueAndFollowed() {
        String admin = hospitalToken("demob", "nurse.demo");
        LocalDate today = LocalDate.now();
        refreshService.refreshForDate(today);

        Map<?, ?> card = data(get(cardPath("node_rate"), admin));
        assertEquals(today.toString(), String.valueOf(card.get("asOf")), "asOf = 刷新统计日");
        assertEquals(bPlanId, num(card.get("planId")), "缺省计划仍 = B 预置");
        Map<String, Map<?, ?>> rows = rowsByLabel(card);
        assertEquals(5, rows.size(), "五个节点各一行（一行 = 节点 × 第 N 次）");

        assertRateRow(rows.get("术后第 1 天"), "术后第 1 天", 3, 1, 1);
        assertRateRow(rows.get("术后第 3 天"), "术后第 3 天", 3, 0, 0);
        assertRateRow(rows.get("术后第 5 天"), "术后第 5 天", 3, 0, 0);
        assertRateRow(rows.get("术后 3 个月"), "术后 3 个月", 2, 0, 0);
        assertRateRow(rows.get("第1年"), "第1年", 2, 1, 1);
        assertEquals(
                "电话随访", rows.get("第1年").get("nodeName"), "年度循环节点行的节点名列（label = 第 N 年，OQ-109③）");

        // 每年随访率（A-DASH-006）与节点随访率的年度节点在同一筛选下一致（「与每年随访率的关系」）
        Map<?, ?> yearly = data(get(cardPath("yearly_rate"), admin));
        List<?> yearRows = (List<?>) yearly.get("rows");
        assertEquals(1, yearRows.size(), "只有第 1 年有已结束窗口（单计划世界 = B 预置）");
        Map<?, ?> year1 = (Map<?, ?>) yearRows.get(0);
        assertEquals("第1年", year1.get("label"));
        assertEquals(2L, num(year1.get("dueCount")), "乙丙（与节点随访率年度行同分母）");
        assertEquals(1L, num(year1.get("followedCount")), "丙补录完成计入（Q38）");
        assertEquals(1L, num(year1.get("inWindowCount")));
        Map<?, ?> nodeYearRow = rows.get("第1年");
        assertEquals(num(year1.get("dueCount")), num(nodeYearRow.get("dueCount")), "同一事实表，两卡数字一致");
        assertEquals(num(year1.get("followedCount")), num(nodeYearRow.get("followedCount")));
    }

    // ---------------------------------------------------------------- B-6 首页开启（A-DASH-013）

    /** 默认布局无 node_rate（default_visible=0，A 默认不显示的同一保证）→ PUT role-layouts 开启 → layout 即生效。 */
    @Test
    @Order(5)
    void b5RoleLayoutEnablesNodeRateCard() {
        String admin = hospitalToken("demob", "nurse.demo");

        List<String> before = visibleCardCodes(admin);
        assertFalse(before.contains("node_rate"), "未配置角色用目录默认布局：node_rate 不显示");

        // 租户管理员逐角色开启（V77 注释口径；全量覆盖语义——目录全部卡按默认序重排，node_rate 置可见）
        Map<?, ?> view =
                data(
                        get(
                                "/api/hospital/followup/dashboard/role-layouts?roleId="
                                        + ADMIN_ROLE_ID,
                                admin));
        Map<?, ?> current = (Map<?, ?>) view.get("current");
        assertNotNull(current, "配置页视图应返回当前角色视图（roles + current）");
        List<?> catalog = (List<?>) current.get("cards");
        assertNotNull(catalog, "配置页视图应列出全目录卡");
        List<Map<String, Object>> cards = new ArrayList<>();
        int order = 1;
        boolean nodeRateAdded = false;
        for (Object item : catalog) {
            Map<?, ?> entry = (Map<?, ?>) item;
            String code = String.valueOf(entry.get("code"));
            Map<String, Object> card = new HashMap<>();
            card.put("code", code);
            boolean isNodeRate = "node_rate".equals(code);
            card.put("visible", isNodeRate || Boolean.TRUE.equals(entry.get("visible")));
            card.put("order", order++);
            if (isNodeRate) {
                nodeRateAdded = true;
            }
            cards.add(card);
        }
        assertTrue(nodeRateAdded, "目录应含 node_rate 卡（V77）");

        Map<String, Object> body = new HashMap<>();
        body.put("cards", cards);
        assertEquals(
                0,
                codeOf(
                        put(
                                "/api/hospital/followup/dashboard/role-layouts/" + ADMIN_ROLE_ID,
                                admin,
                                body)),
                "保存角色布局失败");

        List<String> after = visibleCardCodes(admin);
        assertTrue(after.contains("node_rate"), "开启后首页布局出现 node_rate（配置即时生效，V77）");
    }

    // ---------------------------------------------------------------- 自建自清

    /** 清本类残留（患者级数据 + 事件 + 审计 + 作业记录 + 角色布局）；demob 的预置计划状态由 {@link #restorePresetStates()} 恢复。 */
    private void recycleLeftoverFromEarlierRun() {
        runInTenant(
                "demob",
                () -> {
                    List<Long> patientIds =
                            jdbc.queryForList(
                                    "SELECT id FROM pt_patient WHERE legacy_no LIKE ?",
                                    Long.class,
                                    LEGACY + "-%");
                    for (Long patientId : patientIds) {
                        jdbc.update(
                                "DELETE FROM sys_outbox WHERE JSON_EXTRACT(payload, '$.patientId')"
                                        + " = ?",
                                patientId);
                    }
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE (event_type = 'PATIENT_CREATE'"
                                    + " AND target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                    + " WHERE legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (action IN ('TASK_CREATE', 'TASK_PROGRESS', 'TASK_COMPLETE')"
                                    + " AND target IN (SELECT CONCAT('TASK:', t.id) FROM fu_task t"
                                    + " JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (action = 'RECORD_SUBMIT'"
                                    + " AND target IN (SELECT CONCAT('FU_RECORD:', r.id) FROM fu_record r"
                                    + " JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (action IN ('PLAN_DISABLE', 'PLAN_PUBLISH')"
                                    + " AND target IN ('PLAN:"
                                    + aPlanIdInB
                                    + "', 'PLAN:"
                                    + bPlanId
                                    + "'))"
                                    + " OR (action = 'DASHBOARD_ROLE_LAYOUT'"
                                    + " AND target = 'ROLE:"
                                    + ADMIN_ROLE_ID
                                    + "')"
                                    + " OR action = 'STATS_NODE_REFRESH'");
                    // 事实表与作业记录（刷新作业按日幂等，不清会让下一轮刷新 SKIP）
                    jdbc.update(
                            "DELETE FROM st_node_occurrence WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM job_tenant_run WHERE job_code = 'st-node-refresh'");
                    jdbc.update("DELETE FROM dash_role_layout WHERE role_id = " + ADMIN_ROLE_ID);
                    jdbc.update(
                            "DELETE FROM fu_record WHERE patient_id IN"
                                    + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE e FROM fu_enrollment e JOIN pt_patient p ON p.id = e.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    // 激活写下的计划审计与变更记录（操作人 = demob 演示管理员；随包系统配置行不含这些动作）
                    jdbc.update(
                            "DELETE FROM fu_plan_log WHERE action IN ('PLAN_DISABLE', 'PLAN_PUBLISH')"
                                    + " AND plan_code IN (?, ?)",
                            A_PLAN_CODE,
                            B_PLAN_CODE);
                    jdbc.update(
                            "UPDATE cfg_feature SET enabled = 1 WHERE feature_code = 'node_follow_rate'");
                });
    }

    /** demob 恢复随包状态：B 预置 = 未发布草稿（无发布时间 / 发布人），A 预置 = 已发布 + 自动入组开（V61 / V80 落库形态）。 */
    private void restorePresetStates() {
        runInTenant(
                "demob",
                () -> {
                    jdbc.update(
                            "UPDATE fu_plan SET status = 'UNPUBLISHED', auto_enroll = 1,"
                                    + " published_at = NULL, published_by = NULL"
                                    + " WHERE code = ? AND status <> 'UNPUBLISHED'",
                            B_PLAN_CODE);
                    jdbc.update(
                            "UPDATE fu_plan SET status = 'PUBLISHED', auto_enroll = 1"
                                    + " WHERE code = ? AND status <> 'PUBLISHED'",
                            A_PLAN_CODE);
                });
    }

    private long planIdIn(String tenant, String code) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM fu_plan WHERE code = ? AND version = 1",
                                Long.class,
                                code));
    }

    private int taskCount(long patientId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM fu_task WHERE patient_id = ?", Integer.class, patientId);
    }

    private long taskIdOf(long patientId, String nodeName) {
        return jdbc.queryForObject(
                "SELECT t.id FROM fu_task t JOIN fu_plan_node n ON n.id = t.node_id"
                        + " WHERE t.patient_id = ? AND n.name = ?",
                Long.class,
                patientId,
                nodeName);
    }

    private Map<String, Object> taskRow(long taskId) {
        return jdbc.queryForMap(
                "SELECT id, status, create_type, complete_kind FROM fu_task WHERE id = ?", taskId);
    }

    /** 本类患者的 outbox 事件计数（TASK_OVERDUE / TaskOpened 豁免断言用，JSON 提取同 BPresetPlanVerifyIT）。 */
    private int eventCount(String eventType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_outbox WHERE event_type = ?"
                        + " AND JSON_EXTRACT(payload, '$.patientId') IN"
                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                Integer.class,
                eventType,
                LEGACY + "-%");
    }

    /** 开始录入 → 提交完整随访记录表单（f214 = 实际随访日期，Q43 分类 9 全部字段里的随访日期字段）。 */
    private Map<?, ?> submitBackfill(String token, long taskId, LocalDate visitDate) {
        Map<?, ?> started =
                post("/api/hospital/followup/tasks/" + taskId + "/start", token, Map.of());
        assertEquals(0, codeOf(started), "补录任务开始录入失败（不设直接录入期拦截，Q44「可直接录入」）：" + started);
        String recordId = String.valueOf(data(started).get("recordId"));
        Map<String, Object> form = new HashMap<>();
        form.put("baseVersion", 0);
        form.put("data", Map.of("f214", visitDate.toString(), "f215", 0, "f213", 1));
        Map<?, ?> submitted =
                post("/api/hospital/followup/records/" + recordId + "/submit", token, form);
        assertEquals(0, codeOf(submitted), "补录提交失败：" + submitted);
        return submitted;
    }

    /** 首页布局的可见卡片编码（GET /dashboard/layout，登录即可）。 */
    private List<String> visibleCardCodes(String token) {
        Map<?, ?> layout = data(get("/api/hospital/followup/dashboard/layout", token));
        List<String> codes = new ArrayList<>();
        for (Object item : (List<?>) layout.get("cards")) {
            Map<?, ?> card = (Map<?, ?>) item;
            if (Boolean.TRUE.equals(card.get("visible"))) {
                codes.add(String.valueOf(card.get("code")));
            }
        }
        return codes;
    }

    /** 卡片行按 label 索引（B-4 手工总表断言用）。 */
    private static Map<String, Map<?, ?>> rowsByLabel(Map<?, ?> card) {
        Map<String, Map<?, ?>> byLabel = new HashMap<>();
        for (Object item : (List<?>) card.get("rows")) {
            Map<?, ?> row = (Map<?, ?>) item;
            byLabel.put(String.valueOf(row.get("label")), row);
        }
        return byLabel;
    }

    private static void assertRateRow(
            Map<?, ?> row, String expectedLabel, long due, long followed, long inWindow) {
        assertNotNull(row, "节点行应存在：" + expectedLabel);
        assertEquals(expectedLabel, row.get("label"), "行标签（固定节点 = 节点名、年度循环 = 第 N 年）");
        assertEquals(due, num(row.get("dueCount")), expectedLabel + " 应随访（补录计入分母，Q38）");
        assertEquals(followed, num(row.get("followedCount")), expectedLabel + " 已随访（补录完成计入，Q38）");
        assertEquals(
                inWindow, num(row.get("inWindowCount")), expectedLabel + " 窗口内完成（按实际随访日期，与完成方式无关）");
    }

    private static String cardPath(String cardCode) {
        return "/api/hospital/followup/dashboard/cards/" + cardCode;
    }

    private long registerPatient(String suffix, LocalDate surgery, LocalDate discharge) {
        String token = hospitalToken("demob", "nurse.demo");
        long wardId =
                runInTenant(
                        "demob", () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", "孙示例（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1958-03-21");
        body.put("wardId", wardId);
        body.put("admissionDate", surgery.minusDays(3).toString());
        body.put("surgeryDate", surgery.toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + suffix + "-demob");
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
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

    private Map<?, ?> post(String path, String token) {
        return post(path, token, Map.of());
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
        headers.setBearerAuth(token);
        ResponseEntity<Map> response =
                rest.exchange(path, HttpMethod.PUT, new HttpEntity<>(body, headers), Map.class);
        return response.getBody();
    }

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private static Map<?, ?> data(ResponseEntity<Map> response) {
        return data(response.getBody());
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static long num(Object value) {
        return ((Number) value).longValue();
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
