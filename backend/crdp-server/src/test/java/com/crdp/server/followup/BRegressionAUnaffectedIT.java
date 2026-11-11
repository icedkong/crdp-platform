package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.domain.PlanDateCalculator;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
import java.time.Instant;
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
 * E23-T28 回归（端到端 / 真实 MySQL）：医院 B 上线后「A 功能不受影响」（tasks.csv 验收原文「A 回归用例通过」， 本行未关联需求编号；用例集与执行面见
 * docs/testing/hospital-b-regression-a.md，锚点 RA-1~RA-6）。
 *
 * <p>回归面 = B 上线（E23-T15~T19）引入的共享面变更逐项核对 A 不受影响（手册 §6 风险面清单）：followup V80 B 预置草稿进所有租户库、E23-T17 修复的
 * TaskCreationPlanner 共享排期代码、B 侧激活动作（停 A 预置 / 发布 B 预置）与 node_rate 角色布局、tenant/seed V136 B
 * 用户命名空间（引用）。回归基线 = A 上线冒烟（{@link GoliveSmokeIT} S-x）与 A 预置落库形态（V61）。
 *
 * <p>与既有 IT 的时序差异（本类存在理由）：A 侧 IT（GoliveSmokeIT / APresetPlanVerifyIT）跑在 B 随包草稿态、 {@link
 * BPresetPlanVerifyIT} 的 A 侧断言先于 B 侧激活；生产 W38 形态是 B 已激活上线。本类 @BeforeAll 把 demob 置为 <b>B 上线态</b>（停用
 * A 预置 + 发布 B 预置，E23-T16 激活；管理员角色布局开启 node_rate，E23-T17 B-6），demoa 恢复 A 基线（A 预置已发布 +
 * 自动入组、受检角色布局清回目录默认），再跑 A 侧断言：
 *
 * <ol>
 *   <li>RA-1 计划面：demob 激活态自证（A 预置 DISABLED / B 预置 PUBLISHED）；demoa——A 预置仍 PUBLISHED + auto_enroll、B
 *       草稿仍 UNPUBLISHED 惰性（自动入组候选只有 A 预置，OQ-128）、计划列表在列、node_rate 卡片缺省 planId = A 预置且候选不含 B 草稿；
 *   <li>RA-2 A 主链闭环（S-6 / S-7 面）：病区医生登记「周回归（示例）」（出院 = 一年前）→ 只入组 A 预置一条、起算 = 出院日期（Q35）→ 第 1 年任务
 *       行级参数按 V61（计划日 = 出院 +1 年、提前 7 天提醒、窗口 ±7 天、表单 A_ANNUAL_FU、NORMAL、写 1 条 TaskOpened）→ 随访员认领 →
 *       start → 提交 → COMPLETED + ON_TIME（TaskCreationPlanner 修复后的 A 向回归）；
 *   <li>RA-3 A 补录与第 2 年排期：登记「吴补录（示例）」（出院 = 13 个月前）→ 第 1 年窗口已结束补建 1 条 BACKFILL、不写
 *       TaskOpened（Q44）；next_plan_date = 出院 +2 年、next_open_date = 前 7 天（A-FU-016 长期循环不设结束，FIXED
 *       排完返回 null 的修复不改变无限循环排期）；
 *   <li>RA-4 开关与首页：demoa features 与 A 基线一致（wide_import / report / compare_highlight 开、implant
 *       关，S-2）； 随访员首页布局可见卡不含 node_rate（V77 default_visible=0 未被 B 侧角色布局动作改动）；
 *   <li>RA-6 隔离红线：B 激活 / 上线动作只写 B 库——demoa 无操作人非空的 PLAN_DISABLE / PLAN_PUBLISH 变更记录（V61 系统行操作人为空）、
 *       激活型动作审计较 @BeforeAll 基线零新增（共享库其他 IT 的历史行不计）、受检角色无布局行；医院令牌调平台接口 40300（AGENTS §5.1、S-11）。
 * </ol>
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（病案号 / legacy_no 前缀 IT-E23T28-）；两库的预置计划与
 * 角色布局由 @BeforeAll / @AfterAll 恢复随包状态（demob：B 预置 UNPUBLISHED + A 预置 PUBLISHED、管理员布局清空； 双院配置比对 IT
 * 依赖两库一致）。全部虚构数据（示例）。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BRegressionAUnaffectedIT {

    private static final String PASSWORD = "Demo@2026";

    private static final String BASE = "/api/hospital/followup";

    private static final String LEGACY = "IT-E23T28";

    private static final String A_PLAN_CODE = "A_ANNUAL_FU_PLAN";

    private static final String B_PLAN_CODE = "B_POSTOP_FU_PLAN";

    /** V123 预置账号（demoa = 医院 A 化身，E23-T04 / E23-T12 同口径）。 */
    private static final String WARD_DOCTOR = "a.cvs1.doc01";

    private static final String NURSE = "a.cvs1.nur01";

    /** demob 租户管理员的 TENANT_ADMIN 角色（种子固定 role_id = 1，B 上线态角色布局用，同 BAcceptance IT）。 */
    private static final long B_ADMIN_ROLE_ID = 1L;

    /** demoa 出院 = 北京业务日 − 1 年：第 1 年计划日 = 今天，窗口 [今天−7, 今天+7] 开启中（S-6 口径）。 */
    private static final LocalDate TODAY = PlanDateCalculator.businessToday(Instant.now());

    @Autowired private TestRestTemplate rest;

    @Autowired private JdbcTemplate jdbc;

    private long aPlanIdInA;

    private long bPlanIdInA;

    private long aPlanIdInB;

    private long bPlanIdInB;

    /** RA-2 的患者 id（@Order(2) 登记，@Order(3) 复用库内断言口径独立造第二名）。 */
    private long onTimePatientId;

    /**
     * RA-6 基线：@BeforeAll 清理后、B 侧激活前 demoa 的激活型动作审计行数（其他 IT 在共享库留下的 历史行不计——回归判据是 B 侧激活与 A
     * 主链**零新增**，不是全库为零）。
     */
    private int activationActionAuditBaseline;

    @BeforeAll
    void prepare() {
        aPlanIdInA = planIdIn("demoa", A_PLAN_CODE);
        bPlanIdInA = planIdIn("demoa", B_PLAN_CODE);
        aPlanIdInB = planIdIn("demob", A_PLAN_CODE);
        bPlanIdInB = planIdIn("demob", B_PLAN_CODE);

        // demoa：清本类残留 + 恢复 A 基线（A 预置已发布 + 自动入组；受检角色布局清回目录默认 = V77 随包无布局行）
        recycleALeftovers();
        runInTenant(
                "demoa",
                () -> {
                    jdbc.update(
                            "UPDATE fu_plan SET auto_enroll = 1 WHERE code = ?"
                                    + " AND status = 'PUBLISHED'",
                            A_PLAN_CODE);
                    jdbc.update(
                            "DELETE r FROM dash_role_layout r JOIN sys_role ro ON ro.id = r.role_id"
                                    + " WHERE ro.code IN ('A_FOLLOWUP_NURSE', 'A_WARD_DOCTOR')");
                });

        // demob：清激活残留 → 置为 B 上线态（E23-T16 激活 + E23-T17 B-6 角色布局开启 node_rate）
        recycleBLeftovers();
        // RA-6 基线（激活前）：demoa 激活型动作审计行数——其他 IT 在共享库的历史行不计，判据是零新增
        activationActionAuditBaseline =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM aud_log WHERE action IN"
                                                + " ('PLAN_DISABLE', 'PLAN_PUBLISH',"
                                                + " 'DASHBOARD_ROLE_LAYOUT')",
                                        Integer.class));
        String bAdmin = hospitalToken("demob", "nurse.demo");
        assertEquals(
                0,
                codeOf(post(BASE + "/plans/" + aPlanIdInB + "/disable", bAdmin)),
                "B 侧停用 A 预置失败（激活，E23-T16 §3）");
        assertEquals(
                0,
                codeOf(post(BASE + "/plans/" + bPlanIdInB + "/publish", bAdmin)),
                "B 侧发布 B 预置失败（激活，E23-T16 §3）");
        enableNodeRateForBAdmin(bAdmin);
    }

    @AfterAll
    void cleanUp() {
        recycleALeftovers();
        recycleBLeftovers();
    }

    // ---------------------------------------------------------------- RA-1 计划面：B 上线态下 A 的计划行不变

    /** demob 激活态自证 + demoa：A 预置仍已发布 + 自动入组、B 草稿惰性、卡片缺省计划与候选不含 B 草稿。 */
    @Test
    @Order(1)
    void ra1BGoLiveStateLeavesAPlansAtBaseline() {
        // demob（B 化身）上线形态自证：B 侧「停用 A 预置 / 发布 B 预置」已生效（否则后续 A 侧断言不构成回归）
        runInTenant(
                "demob",
                () -> {
                    assertEquals("DISABLED", statusOf(aPlanIdInB), "B 库：A 预置停用（E23-T16 激活）");
                    assertEquals("PUBLISHED", statusOf(bPlanIdInB), "B 库：B 预置已发布");
                });

        runInTenant(
                "demoa",
                () -> {
                    // B 侧动作只写 B 库：A 库的 A 预置仍处上线基线（V61 随包形态）
                    assertEquals("PUBLISHED", statusOf(aPlanIdInA), "A 库：A 预置仍已发布（未被 B 侧停用波及）");
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                            "SELECT auto_enroll FROM fu_plan WHERE id = ?",
                                            Integer.class,
                                            aPlanIdInA)
                                    .intValue(),
                            "A 预置自动入组仍开（Q35）");
                    assertNotNull(
                            jdbc.queryForObject(
                                    "SELECT published_at FROM fu_plan WHERE id = ?",
                                    java.util.Date.class,
                                    aPlanIdInA),
                            "A 预置发布时间仍在（V61 随包）");
                    // V80 B 草稿在 A 库惰性：自动入组候选（PUBLISHED + auto_enroll）只有 A 预置（OQ-128）
                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_plan WHERE auto_enroll = 1"
                                                    + " AND status = 'PUBLISHED'",
                                            Integer.class)
                                    .intValue(),
                            "A 库自动入组候选只有 A 预置（B 草稿惰性）");
                    assertEquals("UNPUBLISHED", statusOf(bPlanIdInA), "A 库：B 预置仍为未发布草稿");
                });

        // A 管理员正口视角：计划列表 A 预置已发布在列；node_rate 卡片缺省计划 = A 预置、候选不含 B 草稿
        String admin = hospitalToken("demoa", "nurse.demo");
        Map<?, ?> plans = data(get(BASE + "/plans", admin));
        Map<?, ?> aPlanInView =
                ((List<?>) plans.get("items"))
                        .stream()
                                .map(item -> (Map<?, ?>) item)
                                .filter(item -> A_PLAN_CODE.equals(item.get("code")))
                                .findFirst()
                                .orElseThrow(() -> new AssertionError("计划列表缺 A 预置"));
        assertTrue(
                "PUBLISHED".equals(aPlanInView.get("status"))
                        || "RUNNING".equals(aPlanInView.get("status")),
                "列表内 A 预置为已发布 / 随访中（PUBLISHED 且有在组入组的派生展示态，PlanService.toView），不得是停用或草稿，实际 "
                        + aPlanInView.get("status"));
        assertEquals(Boolean.TRUE, aPlanInView.get("autoEnroll"), "列表内 A 预置自动入组开");

        Map<?, ?> card = data(get(BASE + "/dashboard/cards/node_rate", admin));
        assertEquals(aPlanIdInA, num(card.get("planId")), "A 卡片缺省计划 = A 预置（id 最小的已发布版本）");
        assertEquals("冠心病术后年度随访", card.get("planName"));
        List<?> candidates = (List<?>) card.get("plans");
        assertEquals(1, candidates.size(), "A 卡片候选只有 A 预置（B 草稿未发布退出候选）");
        assertEquals(aPlanIdInA, num(((Map<?, ?>) candidates.get(0)).get("id")));
    }

    // ---------------------------------------------------------------- RA-2 A 主链闭环（S-6 / S-7 面）

    /** 登记「周回归（示例）」（出院 = 一年前）：入组 A 预置、第 1 年任务按 V61 → 认领提交「已完成（按时）」。 */
    @Test
    @Order(2)
    void ra2ARegistrationEnrollsAndCompletesOnTime() {
        String doctor = hospitalToken("demoa", WARD_DOCTOR);
        onTimePatientId = registerPatient(doctor, "M1", "周回归（示例）", TODAY.minusYears(1));

        runInTenant(
                "demoa",
                () -> {
                    List<Map<String, Object>> enrollments =
                            jdbc.queryForList(
                                    "SELECT * FROM fu_enrollment WHERE patient_id = ?",
                                    onTimePatientId);
                    assertEquals(1, enrollments.size(), "只入组 A 预置一条（B 草稿惰性，Q35）");
                    Map<String, Object> enrollment = enrollments.get(0);
                    assertEquals(A_PLAN_CODE, enrollment.get("plan_code"));
                    assertEquals("ACTIVE", enrollment.get("status"));
                    assertEquals(
                            TODAY.minusYears(1),
                            date(enrollment.get("start_date")),
                            "起算日期 = 出院日期（A 与 B 的核心差异）");

                    // 第 1 年任务行级参数按 V61：计划日 = 今天、提前 7 天提醒、窗口 ±7 天、表单 / 责任医生 / 正常创建
                    Map<String, Object> task =
                            jdbc.queryForMap(
                                    "SELECT * FROM fu_task WHERE patient_id = ?", onTimePatientId);
                    assertEquals("NORMAL", task.get("create_type"), "窗口开启中正常创建（A-FU-003）");
                    assertEquals("PENDING", task.get("status"));
                    assertEquals("FORM", task.get("task_type"));
                    assertEquals("A_ANNUAL_FU", task.get("form_code"), "完整随访记录表单（V61）");
                    assertEquals(TODAY, date(task.get("plan_date")), "计划日 = 出院 +1 年 = 今天");
                    assertEquals(TODAY.minusDays(7), date(task.get("remind_start")), "提前 7 天提醒");
                    assertEquals(TODAY.minusDays(7), date(task.get("window_start")), "窗口前 7 天");
                    assertEquals(TODAY.plusDays(7), date(task.get("window_end")), "窗口后 7 天");
                    assertEquals(
                            1,
                            outboxCount(onTimePatientId, "TaskOpened"),
                            "正常创建写 TaskOpened（A-FU-001）");
                });

        // 认领 → 开始录入 → 提交（S-7 机器面；默认负责人置空进可认领态，GoliveSmokeIT 同口径）
        String nurse = hospitalToken("demoa", NURSE);
        long taskId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT MIN(id) FROM fu_task WHERE patient_id = ?",
                                        Long.class,
                                        onTimePatientId));
        runInTenant(
                "demoa",
                () -> jdbc.update("UPDATE fu_task SET assignee_id = NULL WHERE id = ?", taskId));
        assertEquals(
                0, codeOf(post(BASE + "/tasks/" + taskId + "/claim", nurse, Map.of())), "认领失败");
        Map<?, ?> started = post(BASE + "/tasks/" + taskId + "/start", nurse, Map.of());
        assertEquals(0, codeOf(started), "开始录入失败：" + started);
        String recordId = String.valueOf(data(started).get("recordId"));

        Map<String, Object> form = new HashMap<>();
        form.put("baseVersion", 0);
        form.put("data", Map.of("f214", TODAY.toString(), "f215", 0, "f213", 1));
        Map<?, ?> submitted = post(BASE + "/records/" + recordId + "/submit", nurse, form);
        assertEquals(0, codeOf(submitted), "提交失败：" + submitted);
        assertEquals("COMPLETED", data(submitted).get("taskStatus"), "提交后任务完成");
        assertEquals("ON_TIME", data(submitted).get("completeKind"), "窗口内完成（按时，A-FU-005）");
    }

    // ---------------------------------------------------------------- RA-3 A 补录与第 2 年排期

    /** 登记「吴补录（示例）」（出院 = 13 个月前）：第 1 年窗口已结束补建补录一条、缓存指向第 2 年（长期不设结束）。 */
    @Test
    @Order(3)
    void ra3ABackfillAndSecondYearCacheKeepAShape() {
        String doctor = hospitalToken("demoa", WARD_DOCTOR);
        LocalDate discharge = TODAY.minusMonths(13); // 第 1 年窗口 [出院+1年−7, +7] 已结束约 23 天
        long patientId = registerPatient(doctor, "M2", "吴补录（示例）", discharge);

        runInTenant(
                "demoa",
                () -> {
                    Map<String, Object> enrollment =
                            jdbc.queryForMap(
                                    "SELECT * FROM fu_enrollment WHERE patient_id = ?", patientId);
                    assertEquals(discharge, date(enrollment.get("start_date")), "起算日期 = 出院日期（Q35）");

                    assertEquals(
                            1,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_task WHERE patient_id = ?",
                                            Integer.class,
                                            patientId)
                                    .intValue(),
                            "只补建已过窗口的第 1 年，未来年度不预生成");
                    Map<String, Object> task =
                            jdbc.queryForMap(
                                    "SELECT * FROM fu_task WHERE patient_id = ?", patientId);
                    assertEquals("BACKFILL", task.get("create_type"), "入组前已过窗口补建补录（Q44）");
                    assertEquals("PENDING", task.get("status"));
                    assertEquals("A_ANNUAL_FU", task.get("form_code"));
                    assertEquals(discharge.plusYears(1), date(task.get("plan_date")), "出院 +1 年");
                    assertEquals(
                            discharge.plusYears(1).minusDays(7),
                            date(task.get("window_start")),
                            "窗口前 7 天");
                    assertEquals(
                            discharge.plusYears(1).plusDays(7),
                            date(task.get("window_end")),
                            "窗口后 7 天");
                    assertEquals(
                            0,
                            outboxCount(patientId, "TaskOpened"),
                            "补录任务不写 TaskOpened（A 规格第 5 章同口径）");

                    // 缓存指向第 2 年：CYCLE 无固定次数的无限循环在 FIXED 修复后仍照常可取（A-FU-016）
                    assertEquals(
                            discharge.plusYears(2),
                            date(enrollment.get("next_plan_date")),
                            "下次计划日期 = 出院 +2 年（长期不设结束年度）");
                    assertEquals(
                            discharge.plusYears(2).minusDays(7),
                            date(enrollment.get("next_open_date")),
                            "下次开启日 = 提醒开始 = 窗口开始（A-FU-001）");
                });
    }

    // ---------------------------------------------------------------- RA-4 开关与首页（A 基线）

    /** demoa features 与 A 基线一致（S-2）；随访员首页布局可见卡不含 node_rate（V77 default_visible=0 未被改动）。 */
    @Test
    @Order(4)
    void ra4AFeaturesAndDashboardStayAtABaseline() {
        String admin = hospitalToken("demoa", "nurse.demo");
        Map<?, ?> features = data(get("/api/hospital/auth/features", admin));
        assertEquals(Boolean.TRUE, features.get("wide_import"), "wide_import 开（A 基线，S-2）");
        assertEquals(Boolean.TRUE, features.get("report"), "report 开（A 基线）");
        assertEquals(Boolean.TRUE, features.get("compare_highlight"), "compare_highlight 开（A 基线）");
        assertEquals(Boolean.FALSE, features.get("implant"), "implant 关（A 无植入，仅医院 C）");

        String nurse = hospitalToken("demoa", NURSE);
        List<String> visible = visibleCardCodes(nurse);
        assertFalse(visible.contains("node_rate"), "A 首页默认不显示节点随访率（A 规格 §9，未被 B 侧动作改动）");
        assertTrue(visible.contains("kpi"), "A 首页默认卡片仍在（目录布局可用）");

        runInTenant(
                "demoa",
                () ->
                        assertEquals(
                                0,
                                jdbc.queryForObject(
                                                "SELECT default_visible FROM dash_card"
                                                        + " WHERE card_code = 'node_rate'",
                                                Integer.class)
                                        .intValue(),
                                "node_rate 目录行 default_visible=0 仍在（V77 随包形态）"));
    }

    // ---------------------------------------------------------------- RA-6 隔离红线：B 上线动作只写 B 库

    /** demoa 无 B 侧激活 / 布局动作痕迹（变更记录操作人为空、无对应审计）；医院令牌进不了平台接口（40300）。 */
    @Test
    @Order(5)
    void ra6BActivationWritesStayInBDatabaseOnly() {
        runInTenant(
                "demoa",
                () -> {
                    // V61 随包的系统配置行操作人为空；B 侧激活（E23-T16）/ 复发布会在 A 库留下操作人非空的行——不应存在
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM fu_plan_log WHERE plan_code IN (?, ?)"
                                                    + " AND action IN ('PLAN_DISABLE', 'PLAN_PUBLISH')"
                                                    + " AND operator_id IS NOT NULL",
                                            Integer.class,
                                            A_PLAN_CODE,
                                            B_PLAN_CODE)
                                    .intValue(),
                            "A 库无操作人非空的停用 / 发布变更记录（激活只写 B 库）");
                    assertEquals(
                            activationActionAuditBaseline,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM aud_log WHERE action IN"
                                                    + " ('PLAN_DISABLE', 'PLAN_PUBLISH', 'DASHBOARD_ROLE_LAYOUT')",
                                            Integer.class)
                                    .intValue(),
                            "A 库激活型动作审计零新增（基线 "
                                    + activationActionAuditBaseline
                                    + " 行，B 侧激活与 A 主链只写 B 库 / 业务行）");
                    assertEquals(
                            0,
                            jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM dash_role_layout r"
                                                    + " JOIN sys_role ro ON ro.id = r.role_id"
                                                    + " WHERE ro.code IN ('A_FOLLOWUP_NURSE', 'A_WARD_DOCTOR')",
                                            Integer.class)
                                    .intValue(),
                            "A 受检角色无角色布局行（目录默认，未被 B 侧布局波及）");
                });

        String nurse = hospitalToken("demoa", NURSE);
        assertEquals(
                40300,
                codeOf(get("/api/platform/menus", nurse).getBody()),
                "医院令牌进不了平台接口（AGENTS §5.1，S-11 口径）");
    }

    // ---------------------------------------------------------------- B 上线态置位（@BeforeAll 用）

    /** B 管理员角色布局开启 node_rate（E23-T17 B-6 生产动作的机器演练形态：目录全量 + node_rate 置可见）。 */
    private void enableNodeRateForBAdmin(String bAdmin) {
        Map<?, ?> view =
                data(get(BASE + "/dashboard/role-layouts?roleId=" + B_ADMIN_ROLE_ID, bAdmin));
        List<?> catalog = (List<?>) ((Map<?, ?>) view.get("current")).get("cards");
        assertNotNull(catalog, "B 配置页视图应列出全目录卡");
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
            nodeRateAdded |= isNodeRate;
            cards.add(card);
        }
        assertTrue(nodeRateAdded, "B 目录应含 node_rate 卡（V77）");
        Map<String, Object> body = new HashMap<>();
        body.put("cards", cards);
        assertEquals(
                0,
                codeOf(put(BASE + "/dashboard/role-layouts/" + B_ADMIN_ROLE_ID, bAdmin, body)),
                "B 管理员角色布局开启 node_rate 失败");
    }

    // ---------------------------------------------------------------- 自建自清

    /** demoa 清本类残留（患者级数据 + 事件 + 审计 + 布局行 + 激活型变更记录残留），恢复可重跑。 */
    private void recycleALeftovers() {
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
                                "DELETE FROM sys_outbox WHERE JSON_EXTRACT(payload, '$.patientId')"
                                        + " = ?",
                                patientId);
                    }
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE"
                                    + " (event_type IN ('TASK_CREATE', 'TASK_CLAIM', 'TASK_START',"
                                    + " 'TASK_PROGRESS', 'TASK_COMPLETE')"
                                    + " AND target IN (SELECT CONCAT('TASK:', t.id) FROM fu_task t"
                                    + " JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (event_type = 'PATIENT_CREATE'"
                                    + " AND target IN (SELECT CONCAT('PATIENT:', id) FROM pt_patient"
                                    + " WHERE legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR (event_type = 'RECORD_SUBMIT'"
                                    + " AND target IN (SELECT CONCAT('FU_RECORD:', r.id) FROM fu_record r"
                                    + " JOIN pt_patient p ON p.id = r.patient_id"
                                    + " WHERE p.legacy_no LIKE '"
                                    + LEGACY
                                    + "-%'))"
                                    + " OR detail LIKE '%"
                                    + LEGACY
                                    + "%'");
                    // 激活型动作在 A 库的残留（历史套件异常中断时可能留下；正常应为 0 行，删除保证 RA-6 断言确定）
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE action IN ('PLAN_DISABLE', 'PLAN_PUBLISH')"
                                    + " AND target IN ('PLAN:"
                                    + aPlanIdInA
                                    + "', 'PLAN:"
                                    + bPlanIdInA
                                    + "')");
                    jdbc.update(
                            "DELETE FROM fu_plan_log WHERE plan_code IN (?, ?)"
                                    + " AND action IN ('PLAN_DISABLE', 'PLAN_PUBLISH')"
                                    + " AND operator_id IS NOT NULL",
                            A_PLAN_CODE,
                            B_PLAN_CODE);
                    jdbc.update(
                            "DELETE fh FROM fe_field_history fh"
                                    + " JOIN fu_record fr ON fr.id = fh.record_id"
                                    + " JOIN pt_patient p ON p.id = fr.patient_id"
                                    + " WHERE fh.record_type = 'FU_RECORD' AND p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE fr FROM fu_record fr JOIN pt_patient p ON p.id = fr.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE t FROM fu_task t JOIN pt_patient p ON p.id = t.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE e FROM fu_enrollment e JOIN pt_patient p ON p.id = e.patient_id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE ps FROM pt_patient_summary ps JOIN pt_patient p ON ps.patient_id = p.id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update(
                            "DELETE sh FROM pt_status_history sh JOIN pt_patient p ON sh.patient_id = p.id"
                                    + " WHERE p.legacy_no LIKE ?",
                            LEGACY + "-%");
                    jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                });
    }

    /** demob 清激活与布局残留，恢复随包状态（B 预置 UNPUBLISHED + A 预置 PUBLISHED；双院配置比对 IT 依赖两库一致）。 */
    private void recycleBLeftovers() {
        runInTenant(
                "demob",
                () -> {
                    AuditTableMaintenance.delete(
                            jdbc,
                            "DELETE FROM aud_log WHERE (action IN ('PLAN_DISABLE', 'PLAN_PUBLISH')"
                                    + " AND target IN ('PLAN:"
                                    + aPlanIdInB
                                    + "', 'PLAN:"
                                    + bPlanIdInB
                                    + "'))"
                                    + " OR (action = 'DASHBOARD_ROLE_LAYOUT'"
                                    + " AND target = 'ROLE:"
                                    + B_ADMIN_ROLE_ID
                                    + "')");
                    jdbc.update(
                            "DELETE FROM fu_plan_log WHERE action IN ('PLAN_DISABLE', 'PLAN_PUBLISH')"
                                    + " AND plan_code IN (?, ?)",
                            A_PLAN_CODE,
                            B_PLAN_CODE);
                    jdbc.update("DELETE FROM dash_role_layout WHERE role_id = " + B_ADMIN_ROLE_ID);
                    // 恢复随包状态：B 预置 = 未发布草稿（无发布时间 / 发布人），A 预置 = 已发布 + 自动入组开（V61 / V80 落库形态）
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

    // ---------------------------------------------------------------- 造数与工具

    /** 病区医生登记示例患者（HTTP 正口；demoa 的 W-A-CVS1 病区，V123 口径）。 */
    private long registerPatient(String token, String suffix, String name, LocalDate discharge) {
        long wardId =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT id FROM ward WHERE code = 'W-A-CVS1'", Long.class));
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", name);
        body.put("gender", "F");
        body.put("birthDate", "1957-11-03");
        body.put("wardId", wardId);
        body.put("admissionDate", discharge.minusDays(14).toString());
        body.put("surgeryDate", discharge.minusDays(7).toString());
        body.put("dischargeDate", discharge.toString());
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-C" + suffix);
        Map<?, ?> created = post(BASE + "/patients", token, body);
        assertEquals(0, codeOf(created), "登记失败（" + name + "）：" + created);
        return Long.parseLong(String.valueOf(data(created).get("id")));
    }

    /** 首页布局的可见卡片编码（GET /dashboard/layout）。 */
    private List<String> visibleCardCodes(String token) {
        Map<?, ?> layout = data(get(BASE + "/dashboard/layout", token));
        List<String> codes = new ArrayList<>();
        for (Object item : (List<?>) layout.get("cards")) {
            Map<?, ?> card = (Map<?, ?>) item;
            if (Boolean.TRUE.equals(card.get("visible"))) {
                codes.add(String.valueOf(card.get("code")));
            }
        }
        return codes;
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

    private int outboxCount(long patientId, String eventType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_outbox WHERE event_type = ?"
                        + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                Integer.class,
                eventType,
                patientId);
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

    private String hospitalToken(String tenant, String username) {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of("tenantCode", tenant, "username", username, "password", PASSWORD));
        assertEquals(0, codeOf(response), "登录失败（" + tenant + " / " + username + "）：" + response);
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
