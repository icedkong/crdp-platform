package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.app.PlanEnrollmentService;
import com.crdp.followup.stats.app.NodeOccurrenceRefreshService;
import com.crdp.framework.tenant.TenantContext;
import com.crdp.server.AuditTableMaintenance;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * E19-T08 统计口径核对（tasks.csv 验收「手工口径与系统一致」，口径 Q38 = hospital-a-spec「每年随访率口径」/「节点随访率口径」）。
 *
 * <p>一套覆盖 Q38 全部条款的虚构数据，先<b>手工</b>按口径表逐行算出期望数字（下表），再跑系统链路（数据 → 每日刷新事实表 → yearly_rate / node_rate
 * 卡片），断言两处一致。口径行与手工算法一一对应，任何口径漂移都会在这里暴露。
 *
 * <p>计划形态（A 年度）：起算 = 出院日 2024-06-01（患者 F15 为 2010-06-01），年度节点 +1 年、窗口 ±7 天 —— 第 N 年计划日 = 起算 + N 年，第
 * 1 年窗口 2025-05-25 ~ 2025-06-08、第 2 年 2026-05-25 ~ 2026-06-08、第 3 年 2027-05-25 起（未结束）。 统计日 asOf =
 * 2027-01-01：第 1、2 年窗口已结束，第 3 年未结束；F15（起算 2010）第 1 ~ 16 年窗口全部结束（随访年度不设上限）。
 *
 * <p><b>手工口径计算表</b>（每年随访率，全院不筛选；due=应随访、f=已随访、w=窗口内完成、L=失访、D=死亡）：
 *
 * <table border="1">
 * <caption>Q38 逐条对照</caption>
 * <tr><th>患者</th><th>场景（Q38 条款）</th><th>第 1 年（手工）</th><th>第 2 年（手工）</th></tr>
 * <tr><td>F01</td><td>窗口内完成（实际随访日期 f214 = 2025-06-01 落窗口内；G2 口径，完成方式仅描述提交时点）；第 2 年待随访</td><td>due+1 f+1 w+1</td><td>due+1</td></tr>
 * <tr><td>F02</td><td>逾期完成（OVERDUE，完成日 2025-07-01）→ 计入已随访、不计窗口内（第二病区，供病区筛选小表）</td><td>due+1 f+1</td><td>due+1</td></tr>
 * <tr><td>F03</td><td>历史导入、日期在窗口外（visit 2025-08-01）→ 照常算已随访</td><td>due+1 f+1</td><td>due+1</td></tr>
 * <tr><td>F04</td><td>补录任务完成（create_type=BACKFILL，实际随访日期 2025-07-20 在窗口外）→ 计入已随访、不计窗口内（G2：按实际随访日期而非完成方式判定）</td><td>due+1 f+1</td><td>due+1</td></tr>
 * <tr><td>F05</td><td>无锚点手工补录（A-FU-014，visit 2025-05-26 落窗口内）→ 按最近计划日期归属第 1 年</td><td>due+1 f+1 w+1</td><td>due+1</td></tr>
 * <tr><td>F06</td><td>历史导入 visit = 窗口首日 2025-05-25 → 窗口内完成（含首尾两天）</td><td>due+1 f+1 w+1</td><td>due+1</td></tr>
 * <tr><td>F07</td><td>历史导入 visit = 窗口末日 2025-06-08 → 窗口内完成（含首尾两天）</td><td>due+1 f+1 w+1</td><td>due+1</td></tr>
 * <tr><td>F08</td><td>窗口开始日（05-25）前已确认失访（LOST 生效 2025-05-01）→ 此前已失访不在应随访</td><td>不计</td><td>不计</td></tr>
 * <tr><td>F09</td><td>第 1 年任务确认失访（任务 LOST，患者仍存活）→ 计分母与失访、不计分子</td><td>due+1 L+1</td><td>due+1</td></tr>
 * <tr><td>F10</td><td>暂停中（PAUSED 生效 2025-01-01）→ 暂停患者仍计入</td><td>due+1</td><td>due+1</td></tr>
 * <tr><td>F11</td><td>随访中确认死亡并提交（实际随访日期 = 死亡当日 2025-06-05 落第 1 年窗口内）→ 算已随访 + 死亡人数 + 窗口内完成；第 2 年窗口开始前已死亡不计</td><td>due+1 f+1 w+1 D+1</td><td>不计</td></tr>
 * <tr><td>F12</td><td>窗口开始日前已死亡（DEAD 生效 2025-05-01）→ 不在应随访</td><td>不计</td><td>不计</td></tr>
 * <tr><td>F13</td><td>历史未随访（Q06）草稿：计入应随访、不计已随访；提交后升级见 {@code histMissedSubmitUpgradesFollowed}</td><td>due+1（草稿）</td><td>due+1</td></tr>
 * <tr><td>F14</td><td>计划外随访记录（UNPLANNED，已提交）→ 不计入任何节点次数（A-DASH-012）</td><td>due+1（仅分母）</td><td>due+1</td></tr>
 * <tr><td>F15</td><td>随访年度不设上限：起算 2010-06-01、无任何任务 / 记录 → 第 1 ~ 16 年每年 due+1（计划推算出行，不因缺任务漏分母）</td><td>due+1</td><td>due+1</td></tr>
 * <tr><td>F16</td><td>多计划并行（OQ-106⑤/OQ-109② 复核锁定）：已从计划 X 移除（仍存活、在组计划 Y）→ 患者级时间线判应随访；每年随访率按患者去重只计 1 人，其已随访来自计划 Y（第 1 年完成，实际随访日期 2025-06-01 落窗口内）</td><td>due+1（X、Y 两行去重）f+1（Y）w+1（Y）</td><td>due+1</td></tr>
 * </table>
 *
 * <p><b>手工汇总</b>：第 1 年 due=14（F01–F07、F09–F11、F13–F16；F08/F12
 * 不计）、f=9（F01–F07、F11、F16）、w=6（F01、F05–F07、F11、F16；G2 口径：全部按实际随访日期〔f214 /
 * visit_date〕落窗判定）、L=1（F09）、D=1（F11）→ 随访率 9/14=64.3%、窗口内完成率 6/14=42.9%、失访率 1/14=7.1%；第 2 年 due=13（第
 * 1 年少 F11）、f=0；第 3 ~ 16 年每年 due=1（F15）、f=0；只列有应随访患者的年度（共 16 行）。 节点随访率（计划 X）同行结构但按入组行计数：第 1 年
 * due=14、f=8、w=5（F16 的已随访在计划 Y，不进 X）、L=1、D=1 → 8/14=57.1%、5/14=35.7%。
 *
 * <p>门控 CRDP_IT_MYSQL_PORT（CI 起 docker compose 注入端口）。自建自清（前缀 IT-E19T08 / PLAN-IT-E19T08）。全部虚构数据。
 */
@ActiveProfiles("local")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.port=0")
@EnabledIfEnvironmentVariable(named = "CRDP_IT_MYSQL_PORT", matches = "\\d+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StatsQ38CaliberIT {

    private static final String PASSWORD = "Demo@2026";
    private static final String LEGACY = "IT-E19T08";
    private static final String PLAN_PREFIX = "PLAN-IT-E19T08-";
    private static final LocalDate AS_OF = LocalDate.of(2027, 1, 1);

    @Autowired private TestRestTemplate rest;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlanEnrollmentService enrollmentService;
    @Autowired private NodeOccurrenceRefreshService refreshService;

    /** 手工表患者 F16 → 患者与计划 id（seedFixture 填充；cleanState 清空）。 */
    private final Map<String, Long> patients = new HashMap<>();

    private final Map<String, Long> plans = new HashMap<>();

    private long f13RecordId;
    private long f14RecordId;
    private long ward2;

    @BeforeEach
    void cleanState() {
        for (String tenant : new String[] {"demoa", "demob"}) {
            runInTenant(
                    tenant,
                    () -> {
                        // 单计划世界：关掉 E23-T02 预置计划的常驻自动入组（口径同 NodeOccurrenceStatsIT）
                        jdbc.update(
                                "UPDATE fu_plan SET auto_enroll = 0 WHERE code = 'A_ANNUAL_FU_PLAN'");
                        AuditTableMaintenance.delete(
                                jdbc,
                                "DELETE FROM aud_log WHERE (event_type LIKE 'PLAN_%'"
                                        + " AND (detail LIKE '%"
                                        + PLAN_PREFIX
                                        + "%'"
                                        + " OR target IN (SELECT CONCAT('PLAN:', id) FROM fu_plan"
                                        + " WHERE code LIKE '"
                                        + PLAN_PREFIX
                                        + "%')))"
                                        + " OR action = 'STATS_NODE_REFRESH'");
                        jdbc.update(
                                "DELETE FROM st_node_occurrence WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update(
                                "DELETE FROM job_tenant_run WHERE job_code = 'st-node-refresh'");
                        jdbc.update(
                                "DELETE FROM fu_record WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
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
                                PLAN_PREFIX + "%");
                        jdbc.update("DELETE FROM fu_plan WHERE code LIKE ?", PLAN_PREFIX + "%");
                        jdbc.update(
                                "DELETE FROM pt_status_history WHERE patient_id IN"
                                        + " (SELECT id FROM pt_patient WHERE legacy_no LIKE ?)",
                                LEGACY + "-%");
                        jdbc.update("DELETE FROM pt_patient WHERE legacy_no LIKE ?", LEGACY + "-%");
                    });
        }
        patients.clear();
        plans.clear();
    }

    @AfterAll
    void cleanUp() {
        cleanState();
    }

    // ---------------------------------------------------------------- 手工口径 vs 系统（验收：手工口径与系统一致）

    /** 手工总表（见类注释）与系统卡片逐格一致；两卡关系与年度不设上限一并锁定。 */
    @Test
    void handComputedQ38TableMatchesSystemCards() {
        seedFixture();
        refreshService.refreshForDate(AS_OF);

        // ---- 每年随访率（A-DASH-006，Q38 主表）：手工汇总 vs 系统
        Map<?, ?> yearly = data(get(card("yearly_rate", ""), adminToken()));
        assertEquals(AS_OF.toString(), String.valueOf(yearly.get("asOf")), "asOf = 刷新统计日");
        List<?> rows = (List<?>) yearly.get("rows");
        assertEquals(16, rows.size(), "只列有应随访患者的年度；第 3 ~ 16 年来自 F15（年度不设上限），第 17 年窗口未结束不列");
        assertRateRow(rowByYear(rows, 1), "第1年", 14, 9, 6, 1, 1, 64.3d, 42.9d, 7.1d);
        assertRateRow(rowByYear(rows, 2), "第2年", 13, 0, 0, 0, 0, 0.0d, 0.0d, 0.0d);
        for (int year = 3; year <= 16; year++) {
            assertRateRow(rowByYear(rows, year), "第" + year + "年", 1, 0, 0, 0, 0, 0.0d, 0.0d, 0.0d);
        }
        assertEquals(
                List.of(2024, 2010), yearly.get("cohortYears"), "队列候选 = 起算年份倒序（2024 与 F15 的 2010）");

        // ---- 节点随访率 · 计划 X（A-DASH-012）：按入组行计数，F16 的已随访在计划 Y 不进 X（OQ-109②）
        Map<?, ?> nodeX = data(get(card("node_rate", "&planId=" + plans.get("X")), adminToken()));
        List<?> nodeRows = (List<?>) nodeX.get("rows");
        assertEquals(16, nodeRows.size(), "计划 X 年度节点第 1 ~ 16 次各一行");
        assertRateRow(rowByYear(nodeRows, 1), "第1年", 14, 8, 5, 1, 1, 57.1d, 35.7d, 7.1d);
        assertRateRow(rowByYear(nodeRows, 2), "第2年", 13, 0, 0, 0, 0, 0.0d, 0.0d, 0.0d);
        assertRateRow(rowByYear(nodeRows, 16), "第16年", 1, 0, 0, 0, 0, 0.0d, 0.0d, 0.0d);

        // ---- 节点随访率 · 计划 Y：只有 F16，第 1 年已完成（每年随访率 f=9 里 F16 来自这里）
        Map<?, ?> nodeY = data(get(card("node_rate", "&planId=" + plans.get("Y")), adminToken()));
        List<?> yRows = (List<?>) nodeY.get("rows");
        assertEquals(2, yRows.size(), "计划 Y 只有 F16：第 1、2 年两行");
        assertRateRow(rowByYear(yRows, 1), "第1年", 1, 1, 1, 0, 0, 100.0d, 100.0d, 0.0d);
        assertRateRow(rowByYear(yRows, 2), "第2年", 1, 0, 0, 0, 0, 0.0d, 0.0d, 0.0d);

        // ---- 同一计划、同一筛选下两卡一致（A-DASH-012「与每年随访率的关系」）：单计划患者世界 = 队列 2010（仅 F15）
        Map<?, ?> yearly2010 = data(get(card("yearly_rate", "&cohortYear=2010"), adminToken()));
        Map<?, ?> nodeX2010 =
                data(
                        get(
                                card("node_rate", "&planId=" + plans.get("X") + "&cohortYear=2010"),
                                adminToken()));
        assertEquals(16, ((List<?>) yearly2010.get("rows")).size());
        for (int year = 1; year <= 16; year++) {
            Map<?, ?> yearRow = rowByYear((List<?>) yearly2010.get("rows"), year);
            Map<?, ?> nodeRow = rowByYear((List<?>) nodeX2010.get("rows"), year);
            assertEquals(
                    num(yearRow.get("dueCount")),
                    num(nodeRow.get("dueCount")),
                    "第" + year + "年应随访两处相同");
            assertEquals(
                    num(yearRow.get("followedCount")),
                    num(nodeRow.get("followedCount")),
                    "第" + year + "年已随访两处相同");
        }
    }

    /** 病区筛选（Q38「病区按患者当前病区」）：F02 / F03 落第二病区，手工小表第 1 年 due=2、f=2、w=0。 */
    @Test
    void wardFilterNarrowsToPatientsCurrentWard() {
        seedFixture();
        refreshService.refreshForDate(AS_OF);

        Map<?, ?> ward2Card = data(get(card("yearly_rate", "&wardId=" + ward2), adminToken()));
        List<?> rows = (List<?>) ward2Card.get("rows");
        assertEquals(2, rows.size(), "第二病区只有 F02 / F03，仅第 1、2 年有应随访");
        assertRateRow(rowByYear(rows, 1), "第1年", 2, 2, 0, 0, 0, 100.0d, 0.0d, 0.0d);
        assertRateRow(rowByYear(rows, 2), "第2年", 2, 0, 0, 0, 0, 0.0d, 0.0d, 0.0d);
    }

    /**
     * Q06 两态（「历史未随访：未补录前不计入已随访，之后补录的计入已随访」）：F13 的 HIST_MISSED 草稿提交后重刷， 第 1 年已随访 9 → 10、随访率 64.3% →
     * 71.4%；其余数字不变（整表重算幂等）。
     */
    @Test
    void histMissedSubmitUpgradesFollowed() {
        seedFixture();
        refreshService.refreshForDate(AS_OF);
        Map<?, ?> beforeCard = data(get(card("yearly_rate", ""), adminToken()));
        List<?> before = (List<?>) beforeCard.get("rows");
        assertRateRow(rowByYear(before, 1), "第1年", 14, 9, 6, 1, 1, 64.3d, 42.9d, 7.1d);

        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_record SET status = 'SUBMITTED' WHERE id = ?",
                                f13RecordId));
        refreshService.refreshForDate(AS_OF);

        Map<?, ?> afterCard = data(get(card("yearly_rate", ""), adminToken()));
        List<?> after = (List<?>) afterCard.get("rows");
        assertRateRow(rowByYear(after, 1), "第1年", 14, 10, 6, 1, 1, 71.4d, 42.9d, 7.1d);
        assertRateRow(rowByYear(after, 2), "第2年", 13, 0, 0, 0, 0, 0.0d, 0.0d, 0.0d);
    }

    /**
     * OQ-106⑤ 复核锁定：F16 已从计划 X 移除（fu_enrollment.status=REMOVED）但仍存活且在组计划 Y —— 按患者级状态时间线判应随访 （Q38
     * 文字即患者级「存活、未被停止随访、此前未被确认失访」），计划 X 第 1 年仍计 due=1（未随访）；计划 Y 行已完成。 每年随访率患者级去重：F16 在 X、Y 各一行
     * due，卡片只计 1 人（OQ-109②）。
     */
    @Test
    void multiPlanRemovalFollowsPatientLevelTimelineAndDedups() {
        seedFixture();
        refreshService.refreshForDate(AS_OF);

        runInTenant(
                "demoa",
                () -> {
                    List<Map<String, Object>> f16Rows =
                            jdbc.queryForList(
                                    "SELECT plan_id, due, followed FROM st_node_occurrence"
                                            + " WHERE patient_id = ? AND followup_year = 1",
                                    patients.get("F16"));
                    assertEquals(2, f16Rows.size(), "F16 第 1 年两行（计划 X + 计划 Y）");
                    for (Map<String, Object> row : f16Rows) {
                        assertEquals(
                                1, ((Number) row.get("due")).intValue(), "患者级仍存活（在组 Y）→ 两行都应随访");
                    }
                    assertTrue(
                            f16Rows.stream()
                                    .anyMatch(
                                            row ->
                                                    plans.get("X")
                                                                    .equals(
                                                                            ((Number)
                                                                                            row.get(
                                                                                                    "plan_id"))
                                                                                    .longValue())
                                                            && ((Number) row.get("followed"))
                                                                            .intValue()
                                                                    == 0),
                            "计划 X 行：已移除、无提交 → 未随访");
                    assertTrue(
                            f16Rows.stream()
                                    .anyMatch(
                                            row ->
                                                    plans.get("Y")
                                                                    .equals(
                                                                            ((Number)
                                                                                            row.get(
                                                                                                    "plan_id"))
                                                                                    .longValue())
                                                            && ((Number) row.get("followed"))
                                                                            .intValue()
                                                                    == 1),
                            "计划 Y 行：任务已完成 → 已随访");
                });

        // 每年随访率第 1 年 due=14（F16 两行去重只计 1 人）、f=9（F16 的已随访来自计划 Y）——与手工总表一致
        Map<?, ?> rateCard = data(get(card("yearly_rate", ""), adminToken()));
        List<?> rows = (List<?>) rateCard.get("rows");
        assertRateRow(rowByYear(rows, 1), "第1年", 14, 9, 6, 1, 1, 64.3d, 42.9d, 7.1d);
    }

    /** 计划外随访不计入（A-DASH-012）：F14 的 UNPLANNED 已提交记录不归属任何节点次数（事实表无引用、卡片不计数）。 */
    @Test
    void unplannedRecordNeverEntersNodeOccurrences() {
        seedFixture();
        refreshService.refreshForDate(AS_OF);

        Integer referenced =
                runInTenant(
                        "demoa",
                        () ->
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM st_node_occurrence WHERE record_id = ?",
                                        Integer.class,
                                        f14RecordId));
        assertEquals(0, referenced, "计划外记录不被事实表引用");
        Map<?, ?> rateCard = data(get(card("yearly_rate", ""), adminToken()));
        List<?> rows = (List<?>) rateCard.get("rows");
        Map<?, ?> year1 = rowByYear(rows, 1);
        assertEquals(14, num(year1.get("dueCount")), "F14 计入分母（任务待随访）");
        assertEquals(9, num(year1.get("followedCount")), "已随访不含计划外记录");
    }

    // ---------------------------------------------------------------- 数据准备（手工表 → 落库行）

    /** 按类注释手工表落库：两份年度计划 + 16 名患者（F01 ~ F16）各自场景。 */
    private void seedFixture() {
        if (!patients.isEmpty()) {
            return; // 同一用例内重复调用防御
        }
        String admin = adminToken();
        plans.put("X", createPublishedAnnualPlan(admin, PLAN_PREFIX + "X"));
        plans.put("Y", createPublishedAnnualPlan(admin, PLAN_PREFIX + "Y"));
        long nodeIdX = nodeIdOf("demoa", plans.get("X"));
        long nodeIdY = nodeIdOf("demoa", plans.get("Y"));
        long ward1 = wardIdAtOffset("demoa", 0);
        ward2 = wardIdAtOffset("demoa", 1);

        // F01 窗口内完成 + 第 2 年待随访
        long f01 = registerPatient("demoa", admin, "F01", "2024-06-01", ward1);
        enrollOnce(f01, plans.get("X"));
        insertTask(
                "demoa",
                f01,
                plans.get("X"),
                nodeIdX,
                1,
                "NORMAL",
                "COMPLETED",
                "ON_TIME",
                "2025-06-01");
        // G2：任务完成行的窗口内完成按实际随访日期（f214）判定——visit 2025-06-01 落窗口内
        linkSubmittedRecord("demoa", f01, "2025-06-01");
        insertTask("demoa", f01, plans.get("X"), nodeIdX, 2, "NORMAL", "PENDING", null, null);
        // F02 逾期完成（第二病区）
        long f02 = registerPatient("demoa", admin, "F02", "2024-06-01", ward2);
        enrollOnce(f02, plans.get("X"));
        insertTask(
                "demoa",
                f02,
                plans.get("X"),
                nodeIdX,
                1,
                "NORMAL",
                "COMPLETED",
                "OVERDUE",
                "2025-07-01");
        // F03 历史导入、窗口外日期
        long f03 = registerPatient("demoa", admin, "F03", "2024-06-01", ward2);
        enrollOnce(f03, plans.get("X"));
        insertAnchoredRecord(
                "demoa", f03, plans.get("X"), nodeIdX, 1, "HIST_IMPORT", "SUBMITTED", "2025-08-01");
        // F04 补录任务完成（create_type=BACKFILL → 已随访、不计窗口内）
        long f04 = registerPatient("demoa", admin, "F04", "2024-06-01", ward1);
        enrollOnce(f04, plans.get("X"));
        insertTask(
                "demoa",
                f04,
                plans.get("X"),
                nodeIdX,
                1,
                "BACKFILL",
                "COMPLETED",
                "OVERDUE",
                "2025-06-20");
        // G2：补录完成按实际随访日期判定——visit 2025-07-20 在窗口外 → 不计窗口内（与完成方式无关）
        linkSubmittedRecord("demoa", f04, "2025-07-20");
        // F05 无锚点手工补录（visit 落第 1 年窗口内 → 归属第 1 年并计窗口内）
        long f05 = registerPatient("demoa", admin, "F05", "2024-06-01", ward1);
        enrollOnce(f05, plans.get("X"));
        insertFloatingBackfill("demoa", f05, "2025-05-26");
        // F06 / F07 历史导入 visit = 窗口首日 / 末日（Q38 含首尾两天）
        long f06 = registerPatient("demoa", admin, "F06", "2024-06-01", ward1);
        enrollOnce(f06, plans.get("X"));
        insertAnchoredRecord(
                "demoa", f06, plans.get("X"), nodeIdX, 1, "HIST_IMPORT", "SUBMITTED", "2025-05-25");
        long f07 = registerPatient("demoa", admin, "F07", "2024-06-01", ward1);
        enrollOnce(f07, plans.get("X"));
        insertAnchoredRecord(
                "demoa", f07, plans.get("X"), nodeIdX, 1, "HIST_IMPORT", "SUBMITTED", "2025-06-08");
        // F08 窗口开始日前已失访（无任务：事实行来自计划推算，NOT_QUALIFIED）
        long f08 = registerPatient("demoa", admin, "F08", "2024-06-01", ward1);
        enrollOnce(f08, plans.get("X"));
        markStatus("demoa", f08, "LOST", LocalDate.of(2025, 5, 1));
        // F09 第 1 年任务确认失访（患者仍存活）
        long f09 = registerPatient("demoa", admin, "F09", "2024-06-01", ward1);
        enrollOnce(f09, plans.get("X"));
        insertTask("demoa", f09, plans.get("X"), nodeIdX, 1, "NORMAL", "LOST", null, null);
        // F10 暂停中仍计入
        long f10 = registerPatient("demoa", admin, "F10", "2024-06-01", ward1);
        enrollOnce(f10, plans.get("X"));
        markStatus("demoa", f10, "PAUSED", LocalDate.of(2025, 1, 1));
        insertTask("demoa", f10, plans.get("X"), nodeIdX, 1, "NORMAL", "PENDING", null, null);
        // F11 随访中确认死亡并提交（第 2 年起不计）
        long f11 = registerPatient("demoa", admin, "F11", "2024-06-01", ward1);
        enrollOnce(f11, plans.get("X"));
        insertTask(
                "demoa",
                f11,
                plans.get("X"),
                nodeIdX,
                1,
                "NORMAL",
                "COMPLETED",
                "ON_TIME",
                "2025-06-05");
        // G2：随访中确认死亡提交——实际随访日期（f214 = 死亡当日 2025-06-05）落窗口内 → 窗口内完成
        linkSubmittedRecord("demoa", f11, "2025-06-05");
        markStatus("demoa", f11, "DEAD", LocalDate.of(2025, 6, 5));
        // F12 窗口开始日前已死亡
        long f12 = registerPatient("demoa", admin, "F12", "2024-06-01", ward1);
        enrollOnce(f12, plans.get("X"));
        markStatus("demoa", f12, "DEAD", LocalDate.of(2025, 5, 1));
        // F13 历史未随访草稿（Q06 两态：提交后升级为补录已随访）
        long f13 = registerPatient("demoa", admin, "F13", "2024-06-01", ward1);
        enrollOnce(f13, plans.get("X"));
        f13RecordId =
                insertAnchoredRecord(
                        "demoa", f13, plans.get("X"), nodeIdX, 1, "HIST_MISSED", "DRAFT", null);
        // F14 计划外随访记录（不计入）
        long f14 = registerPatient("demoa", admin, "F14", "2024-06-01", ward1);
        enrollOnce(f14, plans.get("X"));
        insertTask("demoa", f14, plans.get("X"), nodeIdX, 1, "NORMAL", "PENDING", null, null);
        f14RecordId =
                runInTenant(
                        "demoa",
                        () -> {
                            jdbc.update(
                                    "INSERT INTO fu_record (patient_id, source, visit_date,"
                                            + " form_code, template_version, status)"
                                            + " VALUES (?, 'UNPLANNED', ?, 'fu_annual_v1', 1,"
                                            + " 'SUBMITTED')",
                                    f14,
                                    LocalDate.of(2025, 6, 1));
                            return jdbc.queryForObject(
                                    "SELECT MAX(id) FROM fu_record WHERE patient_id = ?",
                                    Long.class,
                                    f14);
                        });
        // F15 随访年度不设上限（起算 2010，无任务 / 记录 → 第 1 ~ 16 年全靠计划推算出行）
        long f15 = registerPatient("demoa", admin, "F15", "2010-06-01", ward1);
        enrollOnce(f15, plans.get("X"));
        // F16 多计划并行 + 从计划 X 移除（患者级仍 ACTIVE，Y 第 1 年完成）
        long f16 = registerPatient("demoa", admin, "F16", "2024-06-01", ward1);
        long xEnrollment = enrollOnce(f16, plans.get("X"));
        runInTenant(
                "demoa",
                () ->
                        jdbc.update(
                                "UPDATE fu_enrollment SET status = 'REMOVED',"
                                        + " remove_reason = '转入计划 Y（示例）' WHERE id = ?",
                                xEnrollment));
        enrollOnce(f16, plans.get("Y"));
        insertTask(
                "demoa",
                f16,
                plans.get("Y"),
                nodeIdY,
                1,
                "NORMAL",
                "COMPLETED",
                "ON_TIME",
                "2025-06-01");
        // G2：计划 Y 完工任务的实际随访日期 2025-06-01 落窗口内 → 窗口内完成
        linkSubmittedRecord("demoa", f16, "2025-06-01");

        patients.put("F01", f01);
        patients.put("F02", f02);
        patients.put("F03", f03);
        patients.put("F04", f04);
        patients.put("F05", f05);
        patients.put("F06", f06);
        patients.put("F07", f07);
        patients.put("F08", f08);
        patients.put("F09", f09);
        patients.put("F10", f10);
        patients.put("F11", f11);
        patients.put("F12", f12);
        patients.put("F13", f13);
        patients.put("F14", f14);
        patients.put("F15", f15);
        patients.put("F16", f16);
    }

    // ---------------------------------------------------------------- 辅助（口径同 DashboardRateCardsIT）

    private static String card(String cardCode, String query) {
        return "/api/hospital/followup/dashboard/cards/" + cardCode + "?" + query;
    }

    /** 按随访年度取行（yearly_rate / node_rate 的年度循环节点行 label = 第N年）。 */
    private static Map<?, ?> rowByYear(List<?> rows, int year) {
        String label = "第" + year + "年";
        for (Object item : rows) {
            Map<?, ?> row = (Map<?, ?>) item;
            if (label.equals(row.get("label"))) {
                return row;
            }
        }
        throw new AssertionError("缺少行：" + label + "，实际行：" + rows);
    }

    /** 逐格断言一行（手工数字 vs 系统数字；比率为百分数一位小数）。 */
    private static void assertRateRow(
            Map<?, ?> row,
            String label,
            long due,
            long followed,
            long inWindow,
            long lost,
            long death,
            double followRatePct,
            double inWindowRatePct,
            double lostRatePct) {
        assertEquals(label, row.get("label"));
        assertEquals(due, num(row.get("dueCount")), label + " 应随访");
        assertEquals(followed, num(row.get("followedCount")), label + " 已随访");
        assertEquals(inWindow, num(row.get("inWindowCount")), label + " 窗口内完成");
        assertEquals(lost, num(row.get("lostCount")), label + " 失访");
        assertEquals(death, num(row.get("deathCount")), label + " 死亡");
        assertEquals(followRatePct, row.get("followRatePct"), label + " 随访率（主指标）");
        assertEquals(inWindowRatePct, row.get("inWindowRatePct"), label + " 窗口内完成率（参考）");
        assertEquals(lostRatePct, row.get("lostRatePct"), label + " 失访率");
    }

    /** A 年度形态（+1 年、提前 7 天、窗口 ±7 天）。 */
    private long createPublishedAnnualPlan(String token, String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "示例 IT 年度随访计划（" + code + "）");
        body.put("startDateField", "DISCHARGE_DATE");
        body.put("cycleType", "ONGOING");
        long planId = idOf(post("/api/hospital/followup/plans", token, body));

        Map<String, Object> subplan = new HashMap<>();
        subplan.put("key", "s1");
        subplan.put("name", "年度随访（示例）");
        subplan.put("repeatMode", "CYCLE");
        subplan.put("cycleValue", 1);
        subplan.put("cycleUnit", "YEAR");
        subplan.put("sortNo", 10);
        Map<String, Object> node = new HashMap<>();
        node.put("key", "n1");
        node.put("parentKey", "s1");
        node.put("name", "电话随访（示例）");
        node.put("basis", "PLAN_START");
        node.put("offsetValue", 1);
        node.put("offsetUnit", "YEAR");
        node.put("remindValue", 7);
        node.put("remindUnit", "DAY");
        node.put("windowBeforeValue", 7);
        node.put("windowBeforeUnit", "DAY");
        node.put("windowAfterValue", 7);
        node.put("windowAfterUnit", "DAY");
        node.put("taskType", "FORM");
        node.put("ownerType", "WARD_DEFAULT");
        node.put("formCode", "fu_annual_v1");
        node.put("passedWindowAction", "BACKFILL");
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

    /** 登记患者（legacy_no 自清锚点；指定病区）。 */
    private long registerPatient(
            String tenant, String token, String suffix, String dischargeDate, long wardId) {
        Map<String, Object> body = new HashMap<>();
        body.put("medicalRecordNo", LEGACY + "-" + suffix);
        body.put("name", "钱示例（示例）");
        body.put("gender", "F");
        body.put("birthDate", "1950-06-01");
        body.put("wardId", wardId);
        LocalDate discharge = LocalDate.parse(dischargeDate);
        body.put("admissionDate", discharge.minusDays(14).toString());
        body.put("surgeryDate", discharge.minusDays(7).toString());
        body.put("dischargeDate", dischargeDate);
        body.put("dischargeStatus", 1);
        body.put("legacyNo", LEGACY + "-" + suffix + "-" + tenant);
        Map<?, ?> created = post("/api/hospital/followup/patients", token, body);
        assertEquals(0, codeOf(created), "登记患者失败：" + created);
        return Long.parseLong((String) data(created).get("id"));
    }

    /**
     * 服务层入组一次并回拨 created_at 到出院次日（清掉入组即建任务，任务 / 记录由用例直接落库保持口径可控）。
     *
     * @return enrollmentId（F16 移除计划 X 用）
     */
    private long enrollOnce(long patientId, long planId) {
        return runInTenant(
                "demoa",
                () -> {
                    long enrollmentId =
                            Long.parseLong(enrollmentService.enroll(patientId, planId, null).id());
                    jdbc.update(
                            "UPDATE fu_enrollment SET created_at ="
                                    + " (SELECT DATE_ADD(discharge_date, INTERVAL 1 DAY) FROM pt_patient"
                                    + " WHERE id = ?) WHERE id = ?",
                            patientId,
                            enrollmentId);
                    jdbc.update("DELETE FROM fu_task WHERE enrollment_id = ?", enrollmentId);
                    jdbc.update(
                            "DELETE FROM sys_outbox WHERE event_type = 'TaskOpened'"
                                    + " AND JSON_EXTRACT(payload, '$.patientId') = ?",
                            patientId);
                    return enrollmentId;
                });
    }

    /** 节点 id（给定计划的唯一 FORM 节点）。 */
    private long nodeIdOf(String tenant, long planId) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT MIN(n.id) FROM fu_plan_node n WHERE n.plan_id = ?"
                                        + " AND n.node_type = 'NODE'",
                                Long.class,
                                planId));
    }

    /** 病区 id（种子预置病区按 id 升序第 offset 个）。 */
    private long wardIdAtOffset(String tenant, int offset) {
        return runInTenant(
                tenant,
                () ->
                        jdbc.queryForObject(
                                "SELECT id FROM ward ORDER BY id LIMIT 1 OFFSET " + offset,
                                Long.class));
    }

    /** 患者状态流转：患者表 + 状态时间线各一行（统计按窗口开始日回看，V39）。 */
    private void markStatus(String tenant, long patientId, String status, LocalDate effective) {
        runInTenant(
                tenant,
                () -> {
                    jdbc.update(
                            "UPDATE pt_patient SET followup_status = ? WHERE id = ?",
                            status,
                            patientId);
                    jdbc.update(
                            "INSERT INTO pt_status_history"
                                    + " (patient_id, status, effective_date, reason, source)"
                                    + " VALUES (?, ?, ?, 'IT 口径核对用例（示例）', 'MANUAL')",
                            patientId,
                            status,
                            effective);
                });
    }

    /** 常规 / 补录任务行（occurrence 指定次数；计划日 = 起算 + N 年、窗口 ±7 天，与计划配置一致）。 */
    private void insertTask(
            String tenant,
            long patientId,
            long planId,
            long nodeId,
            int occurrence,
            String createType,
            String status,
            String completeKind,
            String completedOn) {
        LocalDate planDate =
                runInTenant(
                                tenant,
                                () ->
                                        jdbc.queryForObject(
                                                "SELECT discharge_date FROM pt_patient WHERE id = ?",
                                                LocalDate.class,
                                                patientId))
                        .plusYears(occurrence);
        runInTenant(
                tenant,
                () ->
                        jdbc.update(
                                "INSERT INTO fu_task (enrollment_id, patient_id, plan_id, node_id,"
                                        + " occurrence, task_type, form_code, owner_type, plan_date,"
                                        + " remind_start, window_start, window_end, open_date,"
                                        + " create_type, status, complete_kind, completed_at,"
                                        + " followup_seq, created_logical_date)"
                                        + " VALUES ((SELECT id FROM fu_enrollment WHERE patient_id = ?"
                                        + " AND plan_id = ?), ?, ?, ?, ?, 'FORM', 'fu_annual_v1',"
                                        + " 'WARD_DEFAULT', ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?)",
                                patientId,
                                planId,
                                patientId,
                                planId,
                                nodeId,
                                occurrence,
                                planDate,
                                planDate.minusDays(14),
                                planDate.minusDays(7),
                                planDate.plusDays(7),
                                planDate.minusDays(14),
                                createType,
                                status,
                                completeKind,
                                completedOn == null ? null : completedOn + " 09:00:00",
                                LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))));
    }

    /**
     * G2（M3 审查）：任务完成行的窗口内完成按**实际随访日期**判定——为完工任务补一条已提交 TASK 记录，随访日期在表单字段 f214（A_ANNUAL_FU
     * followup_date，刷新按模板版本解析）。
     */
    private void linkSubmittedRecord(String tenant, long patientId, String visitDate) {
        runInTenant(
                tenant,
                () -> {
                    jdbc.update(
                            "INSERT INTO fu_record (patient_id, task_id, source, form_code,"
                                    + " template_version, status, data) VALUES (?, (SELECT t.id"
                                    + " FROM fu_task t WHERE t.patient_id = ? AND t.status ="
                                    + " 'COMPLETED' ORDER BY t.id DESC LIMIT 1), 'TASK',"
                                    + " 'A_ANNUAL_FU', 1, 'SUBMITTED', ?)",
                            patientId,
                            patientId,
                            "{\"f214\":\"" + visitDate + "\"}");
                    jdbc.update(
                            "UPDATE fu_task SET record_id = (SELECT id FROM fu_record WHERE"
                                    + " patient_id = ? AND task_id IS NOT NULL ORDER BY id DESC"
                                    + " LIMIT 1) WHERE patient_id = ? AND status = 'COMPLETED'",
                            patientId,
                            patientId);
                });
    }

    /** 锚点记录（历史导入 / 历史未随访，E18-T05 锚点列；无任务）。 */
    private long insertAnchoredRecord(
            String tenant,
            long patientId,
            long planId,
            long nodeId,
            int occurrence,
            String source,
            String status,
            String visitDate) {
        return runInTenant(
                tenant,
                () -> {
                    jdbc.update(
                            "INSERT INTO fu_record (patient_id, enrollment_id, node_id, occurrence,"
                                    + " source, visit_date, form_code, template_version, status)"
                                    + " VALUES (?, (SELECT id FROM fu_enrollment WHERE patient_id = ?"
                                    + " AND plan_id = ?), ?, ?, ?, ?, 'fu_annual_v1', 1, ?)",
                            patientId,
                            patientId,
                            planId,
                            nodeId,
                            occurrence,
                            source,
                            visitDate,
                            status);
                    return jdbc.queryForObject(
                            "SELECT MAX(id) FROM fu_record WHERE patient_id = ?",
                            Long.class,
                            patientId);
                });
    }

    /** 无锚点手工补录记录（A-FU-014：source=BACKFILL、已提交、实际随访日期，无任务 / 节点锚点）。 */
    private void insertFloatingBackfill(String tenant, long patientId, String visitDate) {
        runInTenant(
                tenant,
                () ->
                        jdbc.update(
                                "INSERT INTO fu_record (patient_id, source, visit_date, form_code,"
                                        + " template_version, status)"
                                        + " VALUES (?, 'BACKFILL', ?, 'fu_annual_v1', 1, 'SUBMITTED')",
                                patientId,
                                visitDate));
    }

    private String adminToken() {
        Map<?, ?> response =
                post(
                        "/api/hospital/auth/login",
                        null,
                        Map.of(
                                "tenantCode",
                                "demoa",
                                "username",
                                "nurse.demo",
                                "password",
                                PASSWORD));
        assertEquals(0, codeOf(response), "登录失败：" + response);
        return (String) data(response).get("token");
    }

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
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

    private static <T> T data(ResponseEntity<Map> response) {
        return (T) data(response.getBody());
    }

    private static int codeOf(Map<?, ?> response) {
        return ((Number) response.get("code")).intValue();
    }

    private static long idOf(Map<?, ?> response) {
        return Long.parseLong((String) data(response).get("id"));
    }

    private static long num(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private void runInTenant(String tenant, Runnable action) {
        TenantContext.runAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action);
    }

    private <T> T runInTenant(String tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callAsTenant("demob".equals(tenant) ? 2L : 1L, tenant, action::get);
    }
}
