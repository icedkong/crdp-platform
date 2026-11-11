package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.LocalDate;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E23-T16 核对（端到端 / 真实 MySQL）：迁移 V80 落库后的医院 B 预置计划「节点、提醒与窗口按 Q42 配出」（tasks.csv 验收原文）。
 *
 * <p>基准同 {@link BPresetPlanSqlTest}（第 14 章随访计划表、Q42 / Q43 / Q44），这里是库侧行为核对，化身口径同 {@link
 * APresetPlanVerifyIT}：demoa 作「随包落库 + 医院 A 不受影响」侧（只读核对，B 预置草稿不参与 A 的自动入组）， demob 作医院 B 化身（E23-T15
 * runbook §3：演示化身是 demob）演练 B 侧激活——停用 A 的「冠心病术后年度随访」、发布 B 预置， 再走出院登记验证：
 *
 * <ol>
 *   <li>预置行：fu_plan（未发布草稿、手术日期起算、长期、auto_enroll 开）+ 三组子计划（早期 FIXED ×1 含第 1 / 3 / 5 天、 3 个月 FIXED
 *       ×1、年度 CYCLE 1 YEAR）+ 五个节点（偏移 / 提醒 / 窗口逐项同 Q42 表）；fu_plan_log 只留新建一条（发布条目由激活产生）； 医院 A
 *       侧自动入组候选仍只有 A 预置（V80 对其他租户惰性，OQ-128）；
 *   <li>B 侧激活（runbook docs/ops/hospital-b-plan-preset.md §3 的机器演练）：租户管理员调 停用 A 预置 + 发布 B 预置 → A 预置
 *       DISABLED、B 预置 PUBLISHED，fu_plan_log 分别留 PLAN_DISABLE / PLAN_PUBLISH（操作人非空，A-FU-021）；
 *   <li>出院登记自动入组（Q44 与 A 的 Q35 同口径）：只入 B 预置一条，起算日期 = <b>手术日期</b>（B 与 A 的核心差异）； 手术 8 天前出院 → 术后第 1 /
 *       3 / 5 天窗口均已结束，补建三条「补录」任务（BACKFILL，Q44）且不写 TaskOpened， 未来节点不预生成，缓存指向 3 个月节点（计划日期 = 手术 +3
 *       个月、开启日 = 前 14 天，Q42）；
 *   <li>手术 4 天前出院（§14 示例 03-05 口径的相对化）：第 1 天补录、第 3 天窗口至当天未结束 → 当天正常创建并写 TaskOpened；
 *   <li>满 1 年入组：第 1 年节点窗口 ±1 个月、提前 1 个月提醒（Q42），窗口内正常创建；第 2 年为下次计划日期（每年一次、长期不设结束）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（病案号 / legacy_no IT-E23T16- 前缀）； demob
 * 的预置计划行由本类 @BeforeAll / @AfterAll 恢复为随包状态（B 预置 UNPUBLISHED + A 预置 PUBLISHED， 双院配置比对 IT 如
 * ConfigTemplateTenantInitMySqlIT 依赖两库一致）。全部虚构数据（示例）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BPresetPlanVerifyIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String LEGACY = "IT-E23T16";

    private static final String A_PLAN_CODE = "A_ANNUAL_FU_PLAN";

    private static final String B_PLAN_CODE = "B_POSTOP_FU_PLAN";

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    private long bPlanId;

    private long aPlanIdInB;

    @BeforeAll
    void prepare() {
        // demob（B 化身）：先定位两套预置（id 稳定），清理上次残留并恢复随包状态（B 预置草稿 / A 预置已发布 + 自动入组开）
        bPlanId = planIdIn("demob", B_PLAN_CODE);
        aPlanIdInB = planIdIn("demob", A_PLAN_CODE);
        recycleLeftoverFromEarlierRun();
        restorePresetStates();
        // demoa：其他 IT 按「单计划世界」可能临时关掉 A 预置自动入组——恢复随包状态（V61）再核（APresetPlanVerifyIT 同口径）
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

    /** 预置行落库与 Q42 表一致（行级逐项核对在 {@link BPresetPlanSqlTest}，这里核库内落点与对 A 的惰性）。 */
    @Test
    @Order(1)
    void presetRowsMatchQ42InDatabase() {
        runInTenant(
                "demoa",
                () -> {
                    Long planId = planIdIn("demoa", B_PLAN_CODE);
                    assertNotNull(planId, "V80 应已随包落库（所有租户库同脚本）");
                    Map<String, Object> plan =
                            jdbc.queryForMap(
                                    "SELECT code, name, version, status, start_date_field,"
                                            + " cycle_type, cycle_years, auto_enroll, published_at"
                                            + " FROM fu_plan WHERE id = ?",
                                    planId);
                    assertEquals("心脏术后随访（医院 B）", plan.get("name"));
                    assertEquals(1, ((Number) plan.get("version")).intValue());
                    assertEquals("UNPUBLISHED", plan.get("status"), "随包为未发布草稿（OQ-128）");
                    assertEquals("SURGERY_DATE", plan.get("start_date_field"), "以手术日期起算（§14）");
                    assertEquals("ONGOING", plan.get("cycle_type"), "长期（Q42 不设结束）");
                    assertNull(plan.get("cycle_years"));
                    assertEquals(Boolean.TRUE, plan.get("auto_enroll"), "自动入组开关开（Q44 / Q35）");
                    assertNull(plan.get("published_at"), "草稿无发布时间");

                    // 子计划三组与节点五行（列值逐项在 SqlTest 锁定，这里核形状与关键行）
                    assertEquals(
                            3, countNodes(planId, "SUBPLAN"), "三组子计划：术后早期随访 / 术后 3 个月随访 / 年度随访");
                    assertEquals(5, countNodes(planId, "NODE"), "五个节点（第 1 / 3 / 5 天、3 个月、电话随访）");
                    assertEquals(
                            2,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_plan_node WHERE plan_id = ?"
                                                    + " AND node_type = 'SUBPLAN' AND repeat_mode = 'FIXED'"
                                                    + " AND repeat_count = 1",
                                            Integer.class,
                                            planId)
                                    .intValue(),
                            "两组固定里程碑（术后早期随访 + 术后 3 个月随访，各 FIXED ×1）");
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_plan_node WHERE plan_id = ?"
                                                    + " AND node_type = 'SUBPLAN' AND repeat_mode = 'CYCLE'"
                                                    + " AND cycle_value = 1 AND cycle_unit = 'YEAR'",
                                            Integer.class,
                                            planId)
                                    .intValue(),
                            "年度子计划持续循环每年一次（Q42）");
                    assertEquals(
                            5,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_plan_node WHERE plan_id = ?"
                                                    + " AND node_type = 'NODE' AND task_type = 'FORM'"
                                                    + " AND form_code = 'A_ANNUAL_FU' AND owner_type = 'DOCTOR'"
                                                    + " AND passed_window_action = 'BACKFILL'",
                                            Integer.class,
                                            planId)
                                    .intValue(),
                            "全部节点 = 表单任务 + 完整随访记录表单（Q43）+ 责任医生 + 已过窗口补建（Q44）");
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_plan_log WHERE plan_id = ?"
                                                    + " AND action = 'PLAN_CREATE'",
                                            Integer.class,
                                            planId)
                                    .intValue(),
                            "随包草稿只留新建一条变更记录（发布由 B 侧激活产生）");
                    // 医院 A 不受影响：自动入组候选（PUBLISHED + auto_enroll）仍只有 A 预置（OQ-128 惰性）
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_plan WHERE auto_enroll = 1"
                                                    + " AND status = 'PUBLISHED'",
                                            Integer.class)
                                    .intValue(),
                            "demoa 的自动入组候选只有 A 预置（B 草稿惰性）");
                });
    }

    /** B 侧激活（runbook §3 机器演练）：停用 A 预置 + 发布 B 预置 → 状态、变更记录（A-FU-021）与审计落库。 */
    @Test
    @Order(2)
    void bSideActivationDisablesAAndPublishesB() {
        String admin = hospitalToken("demob", "nurse.demo");
        assertEquals(
                0, codeOf(post("/api/hospital/followup/plans/" + aPlanIdInB + "/disable", admin)));
        assertEquals(
                0, codeOf(post("/api/hospital/followup/plans/" + bPlanId + "/publish", admin)));

        runInTenant(
                "demob",
                () -> {
                    assertEquals(
                            "DISABLED", statusOf(aPlanIdInB), "B 不启用 A 的「冠心病术后年度随访」（第 14 章随访计划行）");
                    assertEquals("PUBLISHED", statusOf(bPlanId), "B 预置发布后即可用");
                    assertNotNull(
                            jdbc.queryForObject(
                                    "SELECT published_at FROM fu_plan WHERE id = ?",
                                    java.util.Date.class,
                                    bPlanId),
                            "发布时间由激活写入");
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_plan_log WHERE plan_id = ?"
                                                    + " AND action = 'PLAN_PUBLISH' AND operator_id IS NOT NULL",
                                            Integer.class,
                                            bPlanId)
                                    .intValue(),
                            "发布变更记录操作人非空（与 V80 系统配置行区分，A-FU-021）");
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_plan_log WHERE plan_id = ?"
                                                    + " AND action = 'PLAN_DISABLE' AND operator_id IS NOT NULL",
                                            Integer.class,
                                            aPlanIdInB)
                                    .intValue(),
                            "停用 A 预置同样留变更记录");
                    // 发布后 B 的自动入组候选就位、A 预置退出（DISABLED 不参与，V50）
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_plan WHERE auto_enroll = 1"
                                                    + " AND status = 'PUBLISHED'",
                                            Integer.class)
                                    .intValue(),
                            "demob 激活后自动入组候选只有 B 预置");
                });
    }

    /** 手术 8 天前出院：自动入组 B 预置、起算 = 手术日期；第 1 / 3 / 5 天窗口已结束补建补录（Q44），缓存指向 3 个月节点（Q42）。 */
    @Test
    @Order(3)
    void registrationBackfillsPassedEarlyNodesAndCachesThreeMonth() {
        LocalDate today = LocalDate.now();
        LocalDate surgery =
                today.minusDays(8); // 第 1 / 3 / 5 天窗口分别止于 today-6 / today-4 / today-2，均已结束
        long patientId = registerPatient("B1", surgery, today);

        runInTenant(
                "demob",
                () -> {
                    List<Map<String, Object>> enrollments =
                            jdbc.queryForList(
                                    "SELECT * FROM fu_enrollment WHERE patient_id = ?", patientId);
                    assertEquals(1, enrollments.size(), "只入组 B 预置一条（A 预置已停用）");
                    Map<String, Object> enrollment = enrollments.get(0);
                    assertEquals(B_PLAN_CODE, enrollment.get("plan_code"));
                    assertEquals("ACTIVE", enrollment.get("status"));
                    assertEquals(
                            surgery,
                            date(enrollment.get("start_date")),
                            "起算日期 = 手术日期（B 与 A 的核心差异，第 14 章）");

                    Map<String, Map<String, Object>> tasks = tasksByNodeName(patientId);
                    assertEquals(3, tasks.size(), "只补建已过窗口的术后第 1 / 3 / 5 天，未来节点不预生成");
                    assertBackfill(
                            tasks.get("术后第 1 天"),
                            surgery.plusDays(1),
                            surgery.plusDays(1),
                            surgery.plusDays(2));
                    assertBackfill(
                            tasks.get("术后第 3 天"),
                            surgery.plusDays(3),
                            surgery.plusDays(3),
                            surgery.plusDays(4));
                    assertBackfill(
                            tasks.get("术后第 5 天"),
                            surgery.plusDays(5),
                            surgery.plusDays(5),
                            surgery.plusDays(6));
                    assertEquals(
                            0,
                            outboxCount(patientId, "TaskOpened"),
                            "补录任务不写 TaskOpened（不进通知，A 规格第 5 章同口径）");

                    // 缓存指向 3 个月节点：计划日期 = 手术 +3 个月、开启日 = 计划日期前 14 天（Q42：提醒 2 周 = 窗口前 14 天）
                    assertEquals(surgery.plusMonths(3), date(enrollment.get("next_plan_date")));
                    assertEquals(
                            surgery.plusMonths(3).minusDays(14),
                            date(enrollment.get("next_open_date")));
                });
    }

    /** 手术 4 天前出院（§14 示例「出院 03-05」口径的相对化）：第 1 天补录、第 3 天窗口至当天未结束当天正常创建并提醒。 */
    @Test
    @Order(4)
    void straddlingWindowCreatesNormalTaskOnEnrollmentDay() {
        LocalDate today = LocalDate.now();
        LocalDate surgery =
                today.minusDays(4); // 第 1 天窗口 [today-3, today-2] 已结束；第 3 天窗口 [today-1, today] 未结束
        long patientId = registerPatient("N1", surgery, today);

        runInTenant(
                "demob",
                () -> {
                    Map<String, Map<String, Object>> tasks = tasksByNodeName(patientId);
                    assertEquals(2, tasks.size(), "第 1 天补录 + 第 3 天当天正常创建（第 5 天 03-06 式未开启不建）");
                    assertBackfill(
                            tasks.get("术后第 1 天"),
                            surgery.plusDays(1),
                            surgery.plusDays(1),
                            surgery.plusDays(2));
                    Map<String, Object> day3 = tasks.get("术后第 3 天");
                    assertEquals("NORMAL", day3.get("create_type"), "窗口结束日 = 建档日 → 未结束，正常创建（Q44）");
                    assertEquals("PENDING", day3.get("status"));
                    assertEquals(surgery.plusDays(3), date(day3.get("plan_date")));
                    assertEquals(surgery.plusDays(3), date(day3.get("window_start")));
                    assertEquals(surgery.plusDays(4), date(day3.get("window_end")));
                    assertEquals(
                            surgery.plusDays(3),
                            date(day3.get("open_date")),
                            "开启日 = 提醒开始（当天提醒，Q42）");
                    assertEquals(
                            1,
                            outboxCount(patientId, "TaskOpened"),
                            "正常创建写 TaskOpened（A-FU-001 同口径）");

                    Map<String, Object> enrollment =
                            jdbc.queryForMap(
                                    "SELECT * FROM fu_enrollment WHERE patient_id = ?", patientId);
                    assertEquals(
                            surgery.plusDays(5),
                            date(enrollment.get("next_plan_date")),
                            "下次 = 术后第 5 天（次日开启，§14 示例 03-06）");
                    assertEquals(surgery.plusDays(5), date(enrollment.get("next_open_date")));
                });
    }

    /** 满 1 年入组：第 1 年节点提前 1 个月提醒、窗口前后 1 个月（Q42），窗口内正常创建；第 2 年为下次计划（每年一次、长期）。 */
    @Test
    @Order(5)
    void yearOneWindowPlusMinusOneMonth() {
        LocalDate today = LocalDate.now();
        LocalDate surgery = today.minusYears(1); // 第 1 年计划日期 = 今天，窗口 [today-1 月, today+1 月] 处于窗口内
        long patientId = registerPatient("Y1", surgery, today);

        runInTenant(
                "demob",
                () -> {
                    Map<String, Map<String, Object>> tasks = tasksByNodeName(patientId);
                    assertEquals(5, tasks.size(), "第 1 / 3 / 5 天与 3 个月补录 + 第 1 年窗口内正常创建");
                    assertBackfill(
                            tasks.get("术后 3 个月"),
                            surgery.plusMonths(3),
                            surgery.plusMonths(3).minusDays(14),
                            surgery.plusMonths(3).plusDays(14));
                    Map<String, Object> yearOne = tasks.get("电话随访");
                    assertEquals(
                            "NORMAL",
                            yearOne.get("create_type"),
                            "第 1 年窗口 [今天-1 月, 今天+1 月] 未结束 → 正常创建");
                    assertEquals(
                            1,
                            ((Number) yearOne.get("occurrence")).intValue(),
                            "第 1 年 = 年度节点第 1 次");
                    assertEquals(today, date(yearOne.get("plan_date")), "计划日期 = 手术 +1 年 = 今天");
                    assertEquals(
                            today.minusMonths(1),
                            date(yearOne.get("remind_start")),
                            "提前 1 个月提醒（Q42）");
                    assertEquals(
                            today.minusMonths(1), date(yearOne.get("window_start")), "窗口前 1 个月");
                    assertEquals(today.plusMonths(1), date(yearOne.get("window_end")), "窗口后 1 个月");
                    assertEquals("A_ANNUAL_FU", yearOne.get("form_code"), "完整随访记录表单（Q43）");

                    Map<String, Object> enrollment =
                            jdbc.queryForMap(
                                    "SELECT * FROM fu_enrollment WHERE patient_id = ?", patientId);
                    assertEquals(
                            today.plusYears(1),
                            date(enrollment.get("next_plan_date")),
                            "下次计划日期 = 手术 +2 年（每年一次，Q42）");
                    assertEquals(
                            today.plusYears(1).minusMonths(1),
                            date(enrollment.get("next_open_date")),
                            "下次开启日 = 提醒开始 = 窗口开始（第 2 年前 1 个月）");
                });
    }

    // ---------------------------------------------------------------- 自建自清

    /** 清本类残留（患者级数据 + 事件 + 审计）；demob 的预置计划状态由 {@link #restorePresetStates()} 恢复。 */
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
                                    + " OR (action IN ('TASK_CREATE', 'PLAN_DISABLE', 'PLAN_PUBLISH')"
                                    + " AND target IN (SELECT CONCAT('TASK:', t.id) FROM fu_task t"
                                    + " JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))");
                    // 激活写下的计划审计与变更记录（操作人 = demob 演示管理员；V61 / V80 的系统配置行不含这些动作）
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE action IN ('PLAN_DISABLE', 'PLAN_PUBLISH')"
                                    + " AND target IN ('PLAN:"
                                    + aPlanIdInB
                                    + "', 'PLAN:"
                                    + bPlanId
                                    + "')");
                    jdbc.update(
                            "DELETE FROM fu_plan_log WHERE action IN ('PLAN_DISABLE', 'PLAN_PUBLISH')"
                                    + " AND plan_code IN (?, ?)",
                            A_PLAN_CODE,
                            B_PLAN_CODE);
                    jdbc.update(
                            "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE e FROM fu_enrollment e JOIN pt_patient p ON p.id = e.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
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

    private String statusOf(long planId) {
        return jdbc.queryForObject("SELECT status FROM fu_plan WHERE id = ?", String.class, planId);
    }

    private int countNodes(long planId, String nodeType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM fu_plan_node WHERE plan_id = ? AND node_type = ?",
                Integer.class,
                planId,
                nodeType);
    }

    /** 患者任务按节点名索引（fu_task 只存 node_id，join fu_plan_node 取名；一节点一条，本类场景无多次）。 */
    private Map<String, Map<String, Object>> tasksByNodeName(long patientId) {
        Map<String, Map<String, Object>> byName = new HashMap<>();
        for (Map<String, Object> task :
                jdbc.queryForList(
                        "SELECT t.*, n.name AS node_name FROM fu_task t"
                                + " JOIN fu_plan_node n ON n.id = t.node_id"
                                + " WHERE t.patient_id = ?",
                        patientId)) {
            byName.put(String.valueOf(task.get("node_name")), task);
        }
        return byName;
    }

    private void assertBackfill(
            Map<String, Object> task,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd) {
        assertNotNull(task, "任务应存在");
        assertEquals("BACKFILL", task.get("create_type"), "入组前已过窗口补建补录（Q44）");
        assertEquals("PENDING", task.get("status"));
        assertEquals("FORM", task.get("task_type"));
        assertEquals("A_ANNUAL_FU", task.get("form_code"), "完整随访记录表单（Q43）");
        assertEquals(planDate, date(task.get("plan_date")));
        assertEquals(windowStart, date(task.get("window_start")));
        assertEquals(windowEnd, date(task.get("window_end")));
        assertEquals(windowStart, date(task.get("open_date")), "开启日 = 窗口开始（提醒 0 = 当天，Q42）");
    }

    private int outboxCount(long patientId, String eventType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_outbox WHERE event_type = ?"
                        + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                Integer.class,
                eventType,
                patientId);
    }

    private long registerPatient(String suffix, LocalDate surgery, LocalDate discharge) {
        String token = hospitalToken("demob", "nurse.demo");
        long wardId =
                runInTenant(
                        "demob", () -> jdbc.queryForObject("SELECT MIN(id) FROM ward", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", "钱示例（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1956-07-12");
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

    /** JDBC 取回的 DATE 列统一换算 LocalDate 再断言（java.sql.Date 与 LocalDate 不 equals）。 */
    private static LocalDate date(Object value) {
        if (value instanceof LocalDate local) {
            return local;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        throw new IllegalArgumentException("不是日期列：" + value);
    }

    private static Map<?, ?> data(Map<?, ?> response) {
        return (Map<?, ?>) response.get("data");
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
