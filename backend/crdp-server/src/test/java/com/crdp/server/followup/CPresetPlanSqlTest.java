package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.domain.PlanNodeRule;
import com.crdp.followup.task.domain.TaskCreationPlanner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * E23-T20 核对（单元）：预置计划迁移 V91 与医院 C 规格 §5「随访节点与窗口（医院已确认，Q03）」表逐项一致（C-FU-002，验收「字段顺序与纸质表一致」的计划侧）。
 *
 * <p>基准是 docs/requirements/hospital-c-spec.md §5 节点表（Q03）与 C-FU-002 的两套计划划分（「普通起搏器术后随访」 单腔 / 双腔 /
 * CRT-P / 希浦系统 / 无导线；「ICD/CRT-D 术后随访」），比对对象是随应用一起发布的迁移脚本
 * db/tenant/followup/V91__hospital_c_plan_preset.sql：不依赖数据库，CI 与本地必跑。
 *
 * <p>覆盖：
 *
 * <ul>
 *   <li>计划行：植入日期起算（IMPLANT_DATE，E21-T05 / C-FU-003）、长期（ONGOING）、auto_enroll = 1（保存植入记录后按
 *       fu_plan_mapping V86 器械类型映射自动入组）——随包状态为<b>未发布草稿</b>（UNPUBLISHED，OQ-128②：C 预置同型复制 B 口径），对医院 A
 *       / B / 其他租户惰性；
 *   <li>子计划三组：术后随访（FIXED ×1，五个术后节点）、常规随访（CYCLE 滚动：普通 1 YEAR / ICD 6 MONTH）、 ERI期随访（CYCLE 1 MONTH）；
 *   <li>节点行（Q03 表）：术后72小时/出院前 = 植入 +3 天、窗口植入日至植入后 7 天（前 3 后 4）；术后1个月 ±7 天； 术后3个月 ±14 天；术后6个月 ±14
 *       天；术后1年 ±30 天；常规随访 = 上次实际随访日期 +12 / +6 个月（基准 LAST_VISIT、窗口 ±30 / ±14 天，仅常规模式）； ERI期随访 = 上次
 *       +1 个月、±7 天（仅 ERI 模式，C-FU-006）；
 *   <li>入组前已过窗口策略 = SKIP（Q14 / C-PAT-008：历史患者建档之前节点不算逾期——与 A / B 的 BACKFILL 相反）； 提醒提前量 =
 *       窗口前（同天开启，A-FU-001 同口径）；
 *   <li>任务与表单：全部节点表单任务 + 随访程控记录表单 C_PROGRAM_FU（V90 字段字典随包）、责任人 = 责任医生；
 *   <li>fu_plan_mapping（V86）映射行指向两个计划族编码（OQ-135④ 契约：C_PACER_FU_PLAN / C_ICD_FU_PLAN）；
 *   <li>预置参数能通过计划引擎结构校验（{@link PlanNodeRule#normalize}），并用 {@link TaskCreationPlanner} 按规格虚构示例（植入
 *       2026-03-01）排期：术后72小时窗口 [03-01, 03-08]、历史建档（03-15）不补建已过节点（SKIP，Q14）、ERI 模式下常规节点不排。
 * </ul>
 */
class CPresetPlanSqlTest {

    private static final String PRESET = "db/tenant/followup/V91__hospital_c_plan_preset.sql";

    private static final String MAPPING = "db/tenant/followup/V86__fu_plan_mapping.sql";

    private static final String PACER = "C_PACER_FU_PLAN";

    private static final String ICD = "C_ICD_FU_PLAN";

    /** fu_plan_node INSERT 的列序（与 V46 建表、V61 / V80 预置一致）。 */
    private static final String[] NODE_COLUMNS = {
        "plan_id",
        "parent_id",
        "node_type",
        "name",
        "repeat_mode",
        "repeat_count",
        "cycle_value",
        "cycle_unit",
        "basis",
        "offset_value",
        "offset_unit",
        "remind_value",
        "remind_unit",
        "window_before_value",
        "window_before_unit",
        "window_after_value",
        "window_after_unit",
        "task_type",
        "owner_type",
        "form_code",
        "passed_window_action",
        "enroll_mode",
        "sort_no"
    };

    /** 一条 fu_plan_node INSERT：解析后的列值 + 子计划的 SET 变量名（节点行 = 其 parent_id 引用）。 */
    private record ParsedNode(Map<String, String> row, String var) {}

    private static String presetSql;

    private static Map<String, Map<String, String>> plans;

    private static Map<String, List<ParsedNode>> nodesByPlan;

    @BeforeAll
    static void parse() throws IOException {
        presetSql = read(PRESET);
        String[] sections = splitPlans(presetSql);
        assertEquals(2, sections.length, "V91 应包含两套计划（普通起搏器 / ICD、CRT-D）");
        plans = new LinkedHashMap<>();
        nodesByPlan = new LinkedHashMap<>();
        String[] codes = {PACER, ICD};
        for (int i = 0; i < codes.length; i++) {
            String section = sections[i];
            plans.put(codes[i], singleInsert(section, "fu_plan", codes[i]));
            nodesByPlan.put(codes[i], nodeInserts(section));
            long subplans =
                    nodesByPlan.get(codes[i]).stream()
                            .filter(n -> "SUBPLAN".equals(n.row().get("node_type")))
                            .count();
            long tasks =
                    nodesByPlan.get(codes[i]).stream()
                            .filter(n -> "NODE".equals(n.row().get("node_type")))
                            .count();
            assertEquals(3, subplans, codes[i] + " 应为 3 个子计划（术后 / 常规 / ERI 期）");
            assertEquals(7, tasks, codes[i] + " 应为 7 个节点（五个术后节点 + 常规 + ERI 期）");
        }
    }

    /** 两套计划行（C-FU-002 标题行）：植入日期起算、长期、自动入组开、随包未发布草稿（OQ-128②）。 */
    @Test
    void planRowsMatchSpec() {
        for (String code : List.of(PACER, ICD)) {
            Map<String, String> plan = plans.get(code);
            assertEquals(code, plan.get("code"), "计划族编码（跨版本不变，V86 映射契约 OQ-135④）");
            assertEquals("1", plan.get("version"), "预置为 v1");
            assertEquals("UNPUBLISHED", plan.get("status"), "随包为未发布草稿：C 侧发布启用（OQ-128②）");
            assertEquals(
                    "IMPLANT_DATE", plan.get("start_date_field"), "以植入日期起算（C-FU-003 / E21-T05）");
            assertEquals("ONGOING", plan.get("cycle_type"), "长期随访（不设结束年度）");
            assertNull(plan.get("cycle_years"));
            assertEquals(
                    "1",
                    plan.get("auto_enroll"),
                    "保存植入记录后按器械类型映射自动入组（fu_plan_mapping V86；仅 PUBLISHED 参与）");
            assertNull(plan.get("published_at"), "草稿未发布不应有发布时间");
        }
        assertEquals("普通起搏器术后随访", plans.get(PACER).get("name"), "C-FU-002 计划名（普通）");
        assertEquals("ICD/CRT-D 术后随访", plans.get(ICD).get("name"), "C-FU-002 计划名（ICD / CRT-D）");
    }

    /** 子计划三组：术后 FIXED ×1（五节点各一次）、常规 CYCLE 滚动（普通 1 YEAR / ICD 6 MONTH）、ERI 期 CYCLE 1 MONTH。 */
    @Test
    void subplansMatchSpec() {
        for (String code : List.of(PACER, ICD)) {
            Map<String, String> postop = subplan(code, "术后随访");
            Map<String, String> regular = subplan(code, "常规随访");
            Map<String, String> eri = subplan(code, "ERI期随访");
            assertEquals("FIXED", postop.get("repeat_mode"), "术后随访为固定里程碑（一次，五节点各发生一次）");
            assertEquals("1", postop.get("repeat_count"));
            assertNull(postop.get("cycle_value"));
            assertEquals("CYCLE", regular.get("repeat_mode"), "常规随访持续循环（上次随访日期滚动）");
            assertNull(regular.get("repeat_count"));
            assertEquals(
                    code.equals(PACER) ? "1" : "6",
                    regular.get("cycle_value"),
                    code + "：常规随访周期（普通每年 / ICD 每 6 个月，Q03）");
            assertEquals(
                    "MONTH".equals(regular.get("cycle_unit")) ? "MONTH" : regular.get("cycle_unit"),
                    code.equals(PACER) ? "YEAR" : "MONTH");
            assertEquals("CYCLE", eri.get("repeat_mode"), "ERI 期持续循环（C-FU-006）");
            assertEquals("1", eri.get("cycle_value"), "ERI 期默认每 1 个月（可设 1–3）");
            assertEquals("MONTH", eri.get("cycle_unit"));
        }
    }

    /** 节点行（Q03 表）：偏移 / 提醒提前量 / 窗口逐项一致；SKIP（Q14）；表单 C_PROGRAM_FU、责任医生。 */
    @Test
    void nodesMatchQ03Table() {
        for (String code : List.of(PACER, ICD)) {
            String postopVar = "@c_" + (code.equals(PACER) ? "pacer" : "icd") + "_postop_id";
            // 术后72小时/出院前：植入 +3 天；窗口 = 植入日至植入后 7 天（前 3 后 4）
            assertNode(
                    code,
                    "术后72小时/出院前",
                    postopVar,
                    "PLAN_START",
                    3,
                    "DAY",
                    3,
                    "DAY",
                    3,
                    "DAY",
                    4,
                    "DAY",
                    null,
                    10);
            assertNode(
                    code,
                    "术后1个月",
                    postopVar,
                    "PLAN_START",
                    1,
                    "MONTH",
                    7,
                    "DAY",
                    7,
                    "DAY",
                    7,
                    "DAY",
                    null,
                    20);
            assertNode(
                    code,
                    "术后3个月",
                    postopVar,
                    "PLAN_START",
                    3,
                    "MONTH",
                    14,
                    "DAY",
                    14,
                    "DAY",
                    14,
                    "DAY",
                    null,
                    30);
            assertNode(
                    code,
                    "术后6个月",
                    postopVar,
                    "PLAN_START",
                    6,
                    "MONTH",
                    14,
                    "DAY",
                    14,
                    "DAY",
                    14,
                    "DAY",
                    null,
                    40);
            assertNode(
                    code,
                    "术后1年",
                    postopVar,
                    "PLAN_START",
                    12,
                    "MONTH",
                    30,
                    "DAY",
                    30,
                    "DAY",
                    30,
                    "DAY",
                    null,
                    50);
            // 常规随访：上次实际随访日期 + 12 / 6 个月（LAST_VISIT 滚动），窗口 ±30 / ±14 天，仅常规模式
            String regularVar = "@c_" + (code.equals(PACER) ? "pacer" : "icd") + "_regular_id";
            assertNode(
                    code,
                    "常规随访",
                    regularVar,
                    "LAST_VISIT",
                    code.equals(PACER) ? 12 : 6,
                    "MONTH",
                    code.equals(PACER) ? 30 : 14,
                    "DAY",
                    code.equals(PACER) ? 30 : 14,
                    "DAY",
                    code.equals(PACER) ? 30 : 14,
                    "DAY",
                    "NORMAL",
                    10);
            // ERI期随访：上次 +1 个月、±7 天，仅 ERI 模式（C-FU-006 / OQ-138④）
            String eriVar = "@c_" + (code.equals(PACER) ? "pacer" : "icd") + "_eri_id";
            assertNode(
                    code,
                    "ERI期随访",
                    eriVar,
                    "LAST_VISIT",
                    1,
                    "MONTH",
                    7,
                    "DAY",
                    7,
                    "DAY",
                    7,
                    "DAY",
                    "ERI",
                    10);
        }
    }

    /** 提醒开始日 = 窗口开始日（A-FU-001 同口径；提前量与窗口前等价）。 */
    @Test
    void remindStartEqualsWindowStart() {
        for (String code : List.of(PACER, ICD)) {
            for (ParsedNode parsed : nodesByPlan.get(code)) {
                if (!"NODE".equals(parsed.row().get("node_type"))) {
                    continue;
                }
                Map<String, String> node = parsed.row();
                assertEquals(
                        daysOf(node.get("remind_value"), node.get("remind_unit")),
                        daysOf(node.get("window_before_value"), node.get("window_before_unit")),
                        code + "·" + node.get("name") + "：提醒开始 = 窗口开始");
            }
        }
    }

    /** 表单 code 复用 V90 字段字典随包的随访程控记录表单（OQ-130①：C_PROGRAM_FU 键契约的载体）。 */
    @Test
    void formCodeShippedByFieldDict() throws IOException {
        String dict = read("db/tenant/followup/V90__hospital_c_field_dict.sql");
        assertTrue(
                dict.contains("('C_PROGRAM_FU', '随访程控记录（医院 C）', 'FOLLOWUP'"),
                "V90 应随包发布 C_PROGRAM_FU 随访程控记录表单");
        for (String code : List.of(PACER, ICD)) {
            for (ParsedNode parsed : nodesByPlan.get(code)) {
                if ("NODE".equals(parsed.row().get("node_type"))) {
                    assertEquals(
                            "C_PROGRAM_FU",
                            parsed.row().get("form_code"),
                            code + "·" + parsed.row().get("name") + "：随访程控记录表单");
                }
            }
        }
    }

    /** V86 器械类型映射（E21-T05 预置）指向两个计划族编码（OQ-135④；映射行在族未发布时惰性）。 */
    @Test
    void deviceMappingPointsAtPresetFamilies() throws IOException {
        String mapping = read(MAPPING).replaceAll("\\s+", " ");
        for (String deviceType : List.of("SSI", "DDD", "CRT_P", "HBP_LBBP", "LEADLESS")) {
            assertTrue(
                    mapping.contains("('" + deviceType + "', '" + PACER + "', 1)"),
                    "普通起搏器类型 " + deviceType + " → " + PACER);
        }
        for (String deviceType : List.of("ICD", "CRT_D")) {
            assertTrue(
                    mapping.contains("('" + deviceType + "', '" + ICD + "', 1)"),
                    "ICD / CRT-D 类型 " + deviceType + " → " + ICD);
        }
    }

    /** 预置参数能通过计划引擎结构校验（枚举、数值与 PlanNodeRule 一致，不靠手写 SQL 旁路）。 */
    @Test
    void presetPassesPlanNodeRuleValidation() {
        for (String code : List.of(PACER, ICD)) {
            List<PlanNodeRule.NormalizedNode> normalized = PlanNodeRule.normalize(nodeInputs(code));
            assertEquals(10, normalized.size(), code + "：3 子计划 + 7 节点");
            for (PlanNodeRule.NormalizedNode node : normalized) {
                if ("NODE".equals(node.nodeType()) && "ERI期随访".equals(node.name())) {
                    assertEquals(
                            "ERI", node.enrollMode(), "ERI 期节点只在 ERI 模式生效（TaskCreationPlanner）");
                } else if ("NODE".equals(node.nodeType()) && "常规随访".equals(node.name())) {
                    assertEquals("NORMAL", node.enrollMode(), "常规循环节点不进 ERI 入组");
                }
            }
        }
    }

    /**
     * 规格 §5 虚构示例（植入 2026-03-01，DDD）：
     *
     * <ul>
     *   <li>术后72小时节点计划 03-04、窗口 [03-01, 03-08]（植入日至植入后 7 天）；
     *   <li>历史患者 2026-03-15 建档入组：已过窗口的术后72小时 / 1 个月节点<b>跳过不补建</b>（SKIP，Q14「只为
     *       今天之后开始的窗口创建任务、之前的节点不算逾期」——与 A / B 的 BACKFILL 相反）；
     *   <li>ERI 模式下常规随访节点不排、ERI 期节点照排（enroll_mode 过滤）。
     * </ul>
     */
    @Test
    void plannerSchedulesCExamples() {
        List<TaskCreationPlanner.NodeDef> defs = nodeDefs(PACER);
        LocalDate implant = LocalDate.of(2026, 3, 1);

        // 植入次日（03-02）建档入组：术后72小时窗口 [03-01, 03-08] 已开启未结束 → 当天正常创建（C-FU-004）
        TaskCreationPlanner.PlanResult fresh =
                TaskCreationPlanner.planForEnrollment(
                        defs, "NORMAL", implant, LocalDate.of(2026, 3, 2), null);
        assertEquals(1, fresh.due().size(), "只建已开启未结束的术后72小时任务");
        TaskCreationPlanner.PlannedTask first = fresh.due().get(0);
        assertEquals("NORMAL", first.createType());
        assertEquals(LocalDate.of(2026, 3, 4), first.planDate(), "计划日期 = 植入 +3 天");
        assertEquals(LocalDate.of(2026, 3, 1), first.windowStart(), "窗口开始 = 植入日（前 3）");
        assertEquals(LocalDate.of(2026, 3, 8), first.windowEnd(), "窗口结束 = 植入后 7 天（后 4）");

        // 历史患者建档（03-15，C-PAT-008）：术后72小时窗口已结束 → SKIP 不补建、不算逾期（Q14，与 A / B 的
        // BACKFILL 相反）；术后1个月窗口 [03-25, 04-08] 尚未开始 → 不预生成
        TaskCreationPlanner.PlanResult legacy =
                TaskCreationPlanner.planForEnrollment(
                        defs, "NORMAL", implant, LocalDate.of(2026, 3, 15), null);
        assertTrue(legacy.due().isEmpty(), "已过窗口节点全部跳过（SKIP，Q14）");
        assertEquals(LocalDate.of(2026, 4, 1), legacy.next().planDate(), "下一次 = 术后1个月（今天之后开始的窗口）");

        // enroll_mode 过滤（只取常规 / ERI 两个循环子计划的节点，避开与 ERI 期首次同日的术后节点）：
        // NORMAL 模式只排常规随访（首次回落起算日 = 植入 +12 个月），ERI 模式只排 ERI 期（植入 +1 个月，±7 天）
        List<TaskCreationPlanner.NodeDef> cycles =
                defs.stream()
                        .filter(
                                d ->
                                        (d.parentId() == null && d.id() >= 102)
                                                || (d.parentId() != null && d.parentId() >= 102))
                        .toList();
        assertEquals(
                2,
                cycles.stream().filter(d -> "NODE".equals(d.nodeType())).count(),
                "常规随访 + ERI期随访两个循环节点（子计划行不入排期）");
        TaskCreationPlanner.PlanResult normal =
                TaskCreationPlanner.plan(
                        cycles,
                        "NORMAL",
                        implant,
                        LocalDate.of(2026, 3, 10),
                        LocalDate.of(2026, 3, 2),
                        null);
        assertEquals(
                LocalDate.of(2027, 3, 1),
                normal.next().planDate(),
                "常规模式：常规随访首次 = 植入 +12 个月（滚动起点随 E21-T07 从上次随访起算）");
        assertEquals(LocalDate.of(2027, 1, 30), normal.next().windowStart(), "常规随访窗口前 30 天（Q03）");
        TaskCreationPlanner.PlanResult eri =
                TaskCreationPlanner.plan(
                        cycles,
                        "ERI",
                        implant,
                        LocalDate.of(2026, 3, 10),
                        LocalDate.of(2026, 3, 2),
                        null);
        assertEquals(
                LocalDate.of(2026, 4, 1),
                eri.next().planDate(),
                "ERI 模式：ERI 期首次 = 植入 +1 个月（从 eri_since 滚动随 E21-T07）");
        assertEquals(LocalDate.of(2026, 3, 25), eri.next().windowStart(), "ERI 期窗口前 7 天（Q03）");
        assertEquals(LocalDate.of(2026, 4, 8), eri.next().windowEnd(), "ERI 期窗口后 7 天（Q03）");
        assertTrue(
                eri.next().nodeId() != normal.next().nodeId(), "两模式的下一次来自不同循环节点（enroll_mode 生效）");
    }

    /** 预置全程留计划变更记录（A-FU-021 / C-FU-001）：随包草稿只写新建两条（每族一条）。 */
    @Test
    void presetChangeLogRecordsCreateOnly() {
        assertTrue(presetSql.contains("'PLAN_CREATE'"), "预置应写入新建变更记录（fu_plan_log）");
        assertFalse(
                presetSql.contains("'PLAN_PUBLISH'"), "发布变更由 C 侧激活（PlanService.publish）产生，不在迁移里");
        assertTrue(presetSql.contains(PACER + "', 'PLAN_CREATE'"), "新建记录指向普通起搏器计划族");
        assertTrue(presetSql.contains(ICD + "', 'PLAN_CREATE'"), "新建记录指向 ICD / CRT-D 计划族");
    }

    // ---------------------------------------------------------------- 迁移 SQL 解析（口径同
    // BPresetPlanSqlTest）

    private static String read(String path) throws IOException {
        try (InputStream in = CPresetPlanSqlTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "迁移脚本应在 classpath：" + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 按计划分段注释切两段（① 普通起搏器 ② ICD/CRT-D）。 */
    private static String[] splitPlans(String sql) {
        int second =
                sql.indexOf(
                        "-- ---------------------------------------------------------------- ②");
        assertTrue(second > 0, "应有两段计划分隔注释");
        return new String[] {sql.substring(0, second), sql.substring(second)};
    }

    private static Map<String, String> singleInsert(String sql, String table, String expectCode) {
        int head = sql.indexOf("INSERT INTO " + table + " ");
        assertTrue(head >= 0, "应包含 " + table + " 的 INSERT");
        int end = sql.indexOf(';', head);
        String insert = sql.substring(head, end);
        Map<String, String> row = new LinkedHashMap<>();
        List<String> columns =
                splitTopLevel(insert.substring(insert.indexOf('(') + 1, insert.indexOf(')')));
        String values =
                insert.substring(insert.indexOf("VALUES", insert.indexOf(')')) + "VALUES".length());
        values = values.substring(values.indexOf('(') + 1, values.lastIndexOf(')'));
        List<String> tuples = splitTopLevel(values);
        assertEquals(columns.size(), tuples.size(), table + " 列数与值数一致");
        for (int i = 0; i < columns.size(); i++) {
            row.put(columns.get(i).strip(), token(tuples.get(i)));
        }
        assertEquals(expectCode, row.get("code"), "段内计划族编码");
        return row;
    }

    private static List<ParsedNode> nodeInserts(String sql) {
        List<ParsedNode> rows = new ArrayList<>();
        int from = 0;
        while (true) {
            String insert;
            try {
                insert = statementOf(sql, "fu_plan_node", from);
            } catch (IllegalStateException end) {
                break;
            }
            int end = sql.indexOf(insert, from) + insert.length();
            String values = insert.substring(insert.indexOf("VALUES") + "VALUES".length());
            values = values.substring(values.indexOf('(') + 1, values.lastIndexOf(')'));
            List<String> tuples = splitTopLevel(values);
            assertEquals(NODE_COLUMNS.length, tuples.size(), "fu_plan_node 列数与值数一致");
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < NODE_COLUMNS.length; i++) {
                row.put(NODE_COLUMNS[i], token(tuples.get(i)));
            }
            int nextInsert = sql.indexOf("INSERT INTO fu_plan_node ", end);
            String tail = sql.substring(end, nextInsert < 0 ? sql.length() : nextInsert);
            String var = null;
            int setAt = tail.indexOf("SET @");
            if (setAt >= 0) {
                int varEnd = tail.indexOf('=', setAt);
                assertTrue(varEnd > 0, "SET 变量语句应完整");
                var = tail.substring(setAt + 4, varEnd).strip();
            }
            rows.add(new ParsedNode(row, var));
            from = end;
        }
        return rows;
    }

    private static String statementOf(String sql, String table, int from) {
        int head = sql.indexOf("INSERT INTO " + table + " ", from);
        if (head < 0) {
            throw new IllegalStateException("no more INSERT INTO " + table);
        }
        int end = sql.indexOf(';', head);
        assertTrue(end > 0, table + " 的 INSERT 语句应以分号结束");
        return sql.substring(head, end);
    }

    private static List<String> splitTopLevel(String raw) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++;
                    } else {
                        inString = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '\'') {
                inString = true;
            } else if (c == ',') {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static String token(String raw) {
        String trimmed = raw.strip();
        return "NULL".equals(trimmed) ? null : trimmed;
    }

    // ---------------------------------------------------------------- 行访问与断言装配

    private static Map<String, String> subplan(String planCode, String name) {
        return nodesByPlan.get(planCode).stream()
                .filter(
                        n ->
                                "SUBPLAN".equals(n.row().get("node_type"))
                                        && name.equals(n.row().get("name")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("子计划不存在：" + planCode + "·" + name))
                .row();
    }

    private static Map<String, String> node(String planCode, String name) {
        return nodesByPlan.get(planCode).stream()
                .filter(
                        n ->
                                "NODE".equals(n.row().get("node_type"))
                                        && name.equals(n.row().get("name")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("节点不存在：" + planCode + "·" + name))
                .row();
    }

    private static void assertNode(
            String planCode,
            String name,
            String parentVar,
            String basis,
            int offsetValue,
            String offsetUnit,
            int remindValue,
            String remindUnit,
            int windowBefore,
            String windowBeforeUnit,
            int windowAfter,
            String windowAfterUnit,
            String enrollMode,
            int sortNo) {
        Map<String, String> row = node(planCode, name);
        assertEquals("NODE", row.get("node_type"));
        assertEquals(parentVar, row.get("parent_id"), planCode + "·" + name + " 挂接的子计划（SET 变量）");
        assertEquals(basis, row.get("basis"), planCode + "·" + name + "：基准");
        assertEquals(
                String.valueOf(offsetValue),
                row.get("offset_value"),
                planCode + "·" + name + "：偏移数值");
        assertEquals(offsetUnit, row.get("offset_unit"));
        assertEquals(String.valueOf(remindValue), row.get("remind_value"));
        assertEquals(remindUnit, row.get("remind_unit"));
        assertEquals(
                String.valueOf(windowBefore),
                row.get("window_before_value"),
                planCode + "·" + name + "：窗口前");
        assertEquals(windowBeforeUnit, row.get("window_before_unit"));
        assertEquals(
                String.valueOf(windowAfter),
                row.get("window_after_value"),
                planCode + "·" + name + "：窗口后");
        assertEquals(windowAfterUnit, row.get("window_after_unit"));
        assertEquals("FORM", row.get("task_type"));
        assertEquals("DOCTOR", row.get("owner_type"), planCode + "·" + name + "：责任人 = 责任医生");
        assertEquals(
                "SKIP",
                row.get("passed_window_action"),
                planCode + "·" + name + "：已过窗口跳过（Q14，与 A / B 的 BACKFILL 相反）");
        assertEquals(enrollMode, row.get("enroll_mode"), planCode + "·" + name + "：启用条件");
        assertEquals(String.valueOf(sortNo), row.get("sort_no"));
    }

    private static long daysOf(String value, String unit) {
        int v = Integer.parseInt(value);
        return "WEEK".equals(unit) ? (long) v * 7 : v;
    }

    private static List<PlanNodeRule.NodeInput> nodeInputs(String planCode) {
        List<PlanNodeRule.NodeInput> inputs = new ArrayList<>();
        for (ParsedNode parsed : nodesByPlan.get(planCode)) {
            Map<String, String> row = parsed.row();
            boolean isSubplan = "SUBPLAN".equals(row.get("node_type"));
            inputs.add(
                    new PlanNodeRule.NodeInput(
                            isSubplan ? parsed.var() : "node:" + row.get("name"),
                            isSubplan ? null : row.get("parent_id"),
                            row.get("name"),
                            row.get("repeat_mode"),
                            intOrNull(row.get("repeat_count")),
                            intOrNull(row.get("cycle_value")),
                            row.get("cycle_unit"),
                            row.get("basis"),
                            intOrNull(row.get("offset_value")),
                            row.get("offset_unit"),
                            intOrNull(row.get("remind_value")),
                            row.get("remind_unit"),
                            intOrNull(row.get("window_before_value")),
                            row.get("window_before_unit"),
                            intOrNull(row.get("window_after_value")),
                            row.get("window_after_unit"),
                            row.get("task_type"),
                            row.get("owner_type"),
                            row.get("form_code"),
                            row.get("passed_window_action"),
                            row.get("enroll_mode"),
                            intOrNull(row.get("sort_no"))));
        }
        return inputs;
    }

    /** 调度器入参：子计划 id = SET 变量号（101 起），节点 id 从 1001 起按序。 */
    private static List<TaskCreationPlanner.NodeDef> nodeDefs(String planCode) {
        Map<String, Long> varIds = new LinkedHashMap<>();
        long subId = 101;
        for (ParsedNode parsed : nodesByPlan.get(planCode)) {
            if ("SUBPLAN".equals(parsed.row().get("node_type"))) {
                assertNotNull(parsed.var(), "子计划行应有 SET 变量：" + parsed.row().get("name"));
                varIds.put(parsed.var(), subId++);
            }
        }
        List<TaskCreationPlanner.NodeDef> defs = new ArrayList<>();
        long seq = 1001;
        for (ParsedNode parsed : nodesByPlan.get(planCode)) {
            Map<String, String> row = parsed.row();
            boolean isSubplan = "SUBPLAN".equals(row.get("node_type"));
            Long parentId = isSubplan ? null : varIds.get(row.get("parent_id"));
            assertNotNull(isSubplan || parentId != null, "节点挂接应可解析：" + row.get("name"));
            long id = isSubplan ? varIds.get(parsed.var()) : seq++;
            defs.add(
                    new TaskCreationPlanner.NodeDef(
                            id,
                            parentId,
                            row.get("node_type"),
                            row.get("repeat_mode"),
                            intOrNull(row.get("repeat_count")),
                            intOrNull(row.get("cycle_value")),
                            row.get("cycle_unit"),
                            row.get("basis"),
                            row.get("enroll_mode"),
                            row.get("passed_window_action"),
                            row.get("offset_value") == null
                                    ? 0
                                    : Integer.parseInt(row.get("offset_value")),
                            row.get("offset_unit"),
                            row.get("remind_value") == null
                                    ? 0
                                    : Integer.parseInt(row.get("remind_value")),
                            row.get("remind_unit"),
                            row.get("window_before_value") == null
                                    ? 0
                                    : Integer.parseInt(row.get("window_before_value")),
                            row.get("window_before_unit"),
                            row.get("window_after_value") == null
                                    ? 0
                                    : Integer.parseInt(row.get("window_after_value")),
                            row.get("window_after_unit")));
        }
        return defs;
    }

    private static long nodeIdOf(String planCode, String name) {
        long seq = 1001;
        for (ParsedNode parsed : nodesByPlan.get(planCode)) {
            if ("NODE".equals(parsed.row().get("node_type"))) {
                if (name.equals(parsed.row().get("name"))) {
                    return seq;
                }
                seq++;
            }
        }
        throw new IllegalStateException("节点不存在：" + planCode + "·" + name);
    }

    private static Map<String, TaskCreationPlanner.PlannedTask> byNodeName(
            List<TaskCreationPlanner.NodeDef> defs, List<TaskCreationPlanner.PlannedTask> tasks) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (TaskCreationPlanner.PlannedTask task : tasks) {
            for (ParsedNode parsed : nodesByPlan.get(PACER)) {
                if ("NODE".equals(parsed.row().get("node_type"))
                        && nodeIdOf(PACER, parsed.row().get("name")) == task.nodeId()) {
                    names.put(task.nodeId(), parsed.row().get("name"));
                }
            }
        }
        Map<String, TaskCreationPlanner.PlannedTask> byName = new LinkedHashMap<>();
        for (TaskCreationPlanner.PlannedTask task : tasks) {
            String name = names.get(task.nodeId());
            assertNotNull(name, "任务应能定位到节点名：" + task.nodeId());
            byName.put(name, task);
        }
        return byName;
    }

    private static Integer intOrNull(String raw) {
        return raw == null ? null : Integer.valueOf(raw);
    }
}
