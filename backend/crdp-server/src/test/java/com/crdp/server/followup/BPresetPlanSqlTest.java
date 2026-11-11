package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.domain.PlanDateCalculator;
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
 * E23-T16 核对（单元）：预置计划迁移 V80 与医院 A 规格 §14「随访计划（医院 B 预置）」表逐项一致（Q42 / Q43 / Q44，A-FU-016 / A-FU-017 的
 * B 侧预置）。
 *
 * <p>基准是 docs/requirements/hospital-a-spec.md 第 14 章（Q42 窗口与频率、Q43 完整随访记录表单、Q44 出院时建档 + 已过窗口补建补录），
 * 比对对象是随应用一起发布的迁移脚本 db/tenant/followup/V80__hospital_b_plan_preset.sql：不依赖数据库，CI 与本地必跑； 落库后的同一口径由
 * {@link BPresetPlanVerifyIT}（门控 CRDP_IT_MYSQL_PORT）对真实 MySQL 再核一遍（含 B 侧激活与登记建任务行为）。
 *
 * <p>覆盖：
 *
 * <ul>
 *   <li>计划行：以手术日期起算（SURGERY_DATE，B 与 A 的核心差异）、长期（ONGOING 不设结束年度，Q42「1 年起每年一次、长期不设结束」）、 auto_enroll
 *       = 1（出院登记自动入组口径与 A 相同，Q44 / Q35）——但随包状态为<b>未发布草稿</b>（UNPUBLISHED）：不写租户分支（AGENTS §5.7），对医院 A
 *       / 其他租户惰性（自动入组只认 PUBLISHED，followup V50），由医院 B 侧发布启用（OQ-128，runbook
 *       docs/ops/hospital-b-plan-preset.md）；
 *   <li>子计划三组（A-FU-016 三层结构「计划 > 子计划 > 任务」）：术后早期随访（FIXED ×1，含术后第 1 / 3 / 5 天三个节点）、 术后 3 个月随访 （FIXED
 *       ×1）、年度随访（CYCLE 1 YEAR 持续循环，直到死亡、失访或人工停止）；
 *   <li>节点行（Q42 表）：术后第 1 / 3 / 5 天 = 手术日期 +1/+3/+5 天、当天提醒（0）、窗口当天至次日（前 0 后 1）；术后 3 个月 = +3 个月、提前 2
 *       周提醒、窗口前后 14 天；年度节点 = +1 年（周期 1 年）、提前 1 个月提醒、窗口前后 1 个月；提醒提前量 = 窗口前（同天开启，A-FU-001 同口径，规格
 *       §14「各节点提醒开始与窗口开始是同一天」）；
 *   <li>任务与表单（Q43）：全部节点电话随访 / 随访任务 + 完整随访记录表单 A_ANNUAL_FU（分类 9 全部字段，V38 随包）、责任人 = 责任医生；
 *       入组前已过窗口补建补录（BACKFILL，Q44）；
 *   <li>预置参数能通过计划引擎结构校验（{@link PlanNodeRule#normalize}），并用 {@link TaskCreationPlanner} 按规格 §14 虚构示例
 *       （手术 2026-03-01）排期：出院 03-08 建档 → 第 1 / 3 / 5 天三条补录（窗口均已结束）；出院 03-05 → 第 1 天补录、第 3 天窗口至当天
 *       未结束当天正常创建、第 5 天 03-06 开启；年度节点第 2 次 = 手术 +2 年、窗口前后 1 个月；
 *   <li>预置留计划变更记录（A-FU-021）：随包为草稿只写 PLAN_CREATE 一条（发布条目由 B 侧激活时 PlanService 产生，操作人非空）。 全部虚构数据（示例）。
 */
class BPresetPlanSqlTest {

    private static final String PRESET = "db/tenant/followup/V80__hospital_b_plan_preset.sql";

    private static final String PLAN_CODE = "B_POSTOP_FU_PLAN";

    /** fu_plan_node INSERT 的列序（与 V46 建表、V61 A 预置一致）。 */
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

    private static Map<String, String> plan;

    private static List<ParsedNode> parsedNodes;

    private static String presetSql;

    @BeforeAll
    static void parse() {
        presetSql = read(PRESET);
        plan = singleInsert(presetSql, "fu_plan");
        parsedNodes = nodeInserts(presetSql);
        long subplans =
                parsedNodes.stream()
                        .filter(n -> "SUBPLAN".equals(n.row().get("node_type")))
                        .count();
        long tasks =
                parsedNodes.stream().filter(n -> "NODE".equals(n.row().get("node_type"))).count();
        assertEquals(3, subplans, "预置应为 3 个子计划");
        assertEquals(5, tasks, "预置应为 5 个节点（术后第 1 / 3 / 5 天、3 个月、年度电话随访）");
    }

    /** 计划行（§14 预置计划标题行）：手术日期起算、长期、自动入组开关开、随包未发布草稿（对 A 惰性，OQ-128）。 */
    @Test
    void planRowMatchesSpec() {
        assertEquals(PLAN_CODE, plan.get("code"), "预置计划族编码（跨版本不变）");
        assertEquals("心脏术后随访（医院 B）", plan.get("name"), "§14 预置计划名称");
        assertEquals("1", plan.get("version"), "预置为 v1");
        assertEquals("UNPUBLISHED", plan.get("status"), "随包为未发布草稿：B 侧发布启用，不写租户分支（OQ-128）");
        assertEquals("SURGERY_DATE", plan.get("start_date_field"), "以手术日期起算（B 与 A 的核心差异，§14）");
        assertEquals("ONGOING", plan.get("cycle_type"), "长期随访（Q42：1 年起每年一次、长期不设结束）");
        assertNull(plan.get("cycle_years"), "不设结束年度（cycle_years 为 NULL）");
        assertEquals(
                "1",
                plan.get("auto_enroll"),
                "出院登记自动入组开关开（Q44 与 A 相同；仅 PUBLISHED 参与自动入组，followup V50）");
        assertNull(plan.get("published_at"), "草稿未发布不应有发布时间（发布由 B 侧激活产生）");
    }

    /** 子计划三组：早期 FIXED ×1（3 个日节点）、3 个月 FIXED ×1、年度持续循环每年一次（Q42 / A-FU-016）。 */
    @Test
    void subplansMatchSpec() {
        Map<String, String> early = subplan("术后早期随访");
        Map<String, String> threeMonth = subplan("术后 3 个月随访");
        Map<String, String> yearly = subplan("年度随访");
        assertEquals("FIXED", early.get("repeat_mode"), "术后早期为固定里程碑（一次）");
        assertEquals("1", early.get("repeat_count"), "固定次数 1（三个日节点各发生一次）");
        assertNull(early.get("cycle_value"));
        assertEquals("FIXED", threeMonth.get("repeat_mode"), "3 个月为固定里程碑（一次，非年度节点）");
        assertEquals("1", threeMonth.get("repeat_count"));
        assertEquals("CYCLE", yearly.get("repeat_mode"), "年度子计划持续循环（A-FU-016：直到死亡、失访或人工停止）");
        assertNull(yearly.get("repeat_count"), "持续循环不设固定次数");
        assertEquals("1", yearly.get("cycle_value"), "每年一次（Q42）");
        assertEquals("YEAR", yearly.get("cycle_unit"));
    }

    /** 节点行（Q42 表）：偏移 / 提醒提前量 / 窗口逐项一致；任务 = 表单任务 + A_ANNUAL_FU（Q43）、责任医生、已过窗口补建（Q44）。 */
    @Test
    void nodesMatchQ42Table() {
        assertNode("术后第 1 天", "@b_early_id", 1, "DAY", 0, "DAY", 0, "DAY", 1, "DAY", 10);
        assertNode("术后第 3 天", "@b_early_id", 3, "DAY", 0, "DAY", 0, "DAY", 1, "DAY", 20);
        assertNode("术后第 5 天", "@b_early_id", 5, "DAY", 0, "DAY", 0, "DAY", 1, "DAY", 30);
        assertNode("术后 3 个月", "@b_3m_id", 3, "MONTH", 2, "WEEK", 14, "DAY", 14, "DAY", 10);
        // 年度节点（电话随访）：+1 年、提前 1 个月提醒、窗口前后 1 个月（Q42「1 年及以后」行，每年一次由子计划周期表达）
        assertNode("电话随访", "@b_year_id", 1, "YEAR", 1, "MONTH", 1, "MONTH", 1, "MONTH", 10);
    }

    /** 提醒开始日 = 窗口开始日（§14「各节点提醒开始与窗口开始是同一天」；2 周与 14 天等价折算）。 */
    @Test
    void remindStartEqualsWindowStart() {
        for (String name : nodeNames()) {
            Map<String, String> node = node(name);
            assertEquals(
                    daysOf(node.get("remind_value"), node.get("remind_unit")),
                    daysOf(node.get("window_before_value"), node.get("window_before_unit")),
                    name + "：提醒开始 = 窗口开始（提前量与窗口前等价折算成天）");
        }
    }

    /** 表单 code 复用医院 A 字段字典随包的完整随访记录表单（Q43：各节点都用完整表单，分类 9 全部字段，不另立简表）。 */
    @Test
    void formCodeShippedByFieldDict() {
        String dict = read("db/tenant/followup/V38__hospital_a_field_dict.sql");
        assertTrue(
                dict.contains("('A_ANNUAL_FU', '年度随访记录', 'FOLLOWUP'"),
                "V38 应随包发布 A_ANNUAL_FU 完整随访记录表单（Q43：B 各节点复用）");
        for (String name : nodeNames()) {
            assertEquals("A_ANNUAL_FU", node(name).get("form_code"), name + "：完整随访记录表单（Q43）");
        }
    }

    /** 预置参数能通过计划引擎结构校验（枚举、数值与 PlanNodeRule 一致，不靠手写 SQL 旁路）。 */
    @Test
    void presetPassesPlanNodeRuleValidation() {
        List<PlanNodeRule.NormalizedNode> normalized = PlanNodeRule.normalize(nodeInputs());
        assertEquals(8, normalized.size(), "3 子计划 + 5 节点");
        Map<String, PlanNodeRule.NormalizedNode> byName = new LinkedHashMap<>();
        for (PlanNodeRule.NormalizedNode item : normalized) {
            byName.put(item.nodeType() + "+" + item.name(), item);
        }
        PlanNodeRule.NormalizedNode day1 = byName.get("NODE+术后第 1 天");
        assertEquals("PLAN_START", day1.basis(), "基准 = 计划起算日期（手术日期）");
        assertEquals(1, day1.offsetValue());
        assertEquals("DAY", day1.offsetUnit());
        assertEquals(0, day1.remindValue(), "当天提醒（Q42：0 = 计划日期当天）");
        assertEquals(0, day1.windowBeforeValue(), "窗口前 0 天（当天开启）");
        assertEquals(1, day1.windowAfterValue(), "窗口后 1 天（当天至次日）");
        assertEquals("FORM", day1.taskType());
        assertEquals("DOCTOR", day1.ownerType(), "责任人 = 责任医生");
        assertEquals("BACKFILL", day1.passedWindowAction(), "入组前已过窗口补建补录（Q44）");
        PlanNodeRule.NormalizedNode yearly = byName.get("NODE+电话随访");
        assertEquals(1, yearly.offsetValue());
        assertEquals("YEAR", yearly.offsetUnit());
        assertEquals(1, yearly.remindValue());
        assertEquals("MONTH", yearly.remindUnit(), "年度节点提前 1 个月提醒（Q42）");
        assertEquals(1, yearly.windowBeforeValue());
        assertEquals("MONTH", yearly.windowBeforeUnit());
        assertEquals(1, yearly.windowAfterValue());
        assertEquals("MONTH", yearly.windowAfterUnit());
    }

    /** 规格 §14 虚构示例（手术 2026-03-01）：出院 03-08 → 第 1 / 3 / 5 天全部补录；出院 03-05 → 第 1 天补录、第 3 天当天正常创建。 */
    @Test
    void plannerBackfillsPassedEarlyNodesOnEnrollment() {
        List<TaskCreationPlanner.NodeDef> defs = nodeDefs();
        LocalDate surgery = LocalDate.of(2026, 3, 1);

        // 出院 2026-03-08（示例原文）：三个早期节点窗口均已结束 → 补录（BACKFILL，Q44），不预生成未来节点
        TaskCreationPlanner.PlanResult late =
                TaskCreationPlanner.planForEnrollment(
                        defs, "NORMAL", surgery, LocalDate.of(2026, 3, 8), null);
        Map<String, TaskCreationPlanner.PlannedTask> due = byNodeName(defs, late.due());
        assertEquals(3, late.due().size(), "只补建已过窗口的术后第 1 / 3 / 5 天");
        assertSchedule(
                due.get("术后第 1 天"),
                "BACKFILL",
                LocalDate.of(2026, 3, 2),
                LocalDate.of(2026, 3, 2),
                LocalDate.of(2026, 3, 3));
        assertSchedule(
                due.get("术后第 3 天"),
                "BACKFILL",
                LocalDate.of(2026, 3, 4),
                LocalDate.of(2026, 3, 4),
                LocalDate.of(2026, 3, 5));
        assertSchedule(
                due.get("术后第 5 天"),
                "BACKFILL",
                LocalDate.of(2026, 3, 6),
                LocalDate.of(2026, 3, 6),
                LocalDate.of(2026, 3, 7));
        assertEquals(
                LocalDate.of(2026, 6, 1), late.next().planDate(), "下次计划日期 = 手术 +3 个月（2026-06-01）");
        assertEquals(
                LocalDate.of(2026, 5, 18),
                late.next().openDate(),
                "3 个月节点 2026-05-18 开启（示例原文：提醒 2 周 = 窗口前 14 天）");

        // 出院 2026-03-05（示例原文）：第 1 天补录、第 3 天窗口至 03-05 未结束当天正常创建、第 5 天 03-06 开启
        TaskCreationPlanner.PlanResult straddle =
                TaskCreationPlanner.planForEnrollment(
                        defs, "NORMAL", surgery, LocalDate.of(2026, 3, 5), null);
        Map<String, TaskCreationPlanner.PlannedTask> straddleDue = byNodeName(defs, straddle.due());
        assertEquals(2, straddle.due().size(), "第 1 天补录 + 第 3 天当天正常创建");
        assertEquals("BACKFILL", straddleDue.get("术后第 1 天").createType());
        assertEquals("NORMAL", straddleDue.get("术后第 3 天").createType(), "窗口结束日 = 建档日 → 未结束，正常创建");
        assertEquals(
                LocalDate.of(2026, 3, 6),
                straddle.next().planDate(),
                "下次 = 术后第 5 天（03-06 开启，示例原文）");
    }

    /** 年度节点按年推进（Q42：1 年起每年一次、长期不设结束）：第 1 次窗口 ±1 个月、第 2 次 = 手术 +2 年。 */
    @Test
    void yearlyNodeRecursAnnually() {
        List<TaskCreationPlanner.NodeDef> defs = nodeDefs();
        long yearNodeId =
                idOf(
                        parsedNodes.stream()
                                .filter(
                                        n ->
                                                "NODE".equals(n.row().get("node_type"))
                                                        && "电话随访".equals(n.row().get("name")))
                                .findFirst()
                                .orElseThrow());
        TaskCreationPlanner.NodeDef node =
                defs.stream().filter(d -> d.id() == yearNodeId).findFirst().orElseThrow();
        // 周期挂在年度子计划（CYCLE 1 YEAR），从父行取
        long yearSubId = node.parentId();
        TaskCreationPlanner.NodeDef yearSub =
                defs.stream().filter(d -> d.id() == yearSubId).findFirst().orElseThrow();
        LocalDate surgery = LocalDate.of(2026, 3, 1);
        LocalDate second =
                PlanDateCalculator.occurrencePlanDate(
                        surgery, timingOf(node), yearSub.cycleValue(), yearSub.cycleUnit(), 2);
        assertEquals(LocalDate.of(2028, 3, 1), second, "第 2 次 = 手术 +2 年（每年一次）");
        // 第 1 次完整排期（示例原文：1 年节点计划 2027-03-01、2027-02-01 开启、窗口至 04-01）
        TaskCreationPlanner.PlanResult first =
                TaskCreationPlanner.planForEnrollment(
                        defs, "NORMAL", surgery, LocalDate.of(2027, 3, 10), null);
        TaskCreationPlanner.PlannedTask yearOne = byNodeName(defs, first.due()).get("电话随访");
        assertNotNull(yearOne, "第 1 年窗口 [02-01, 04-01]，03-10 处于窗口内且开启日已过 → 当天正常创建在 due");
        assertEquals("NORMAL", yearOne.createType(), "窗口未结束（至 04-01）→ 正常创建，不标补录");
        assertEquals(LocalDate.of(2027, 3, 1), yearOne.planDate());
        assertEquals(LocalDate.of(2027, 2, 1), yearOne.remindStart(), "提前 1 个月提醒（Q42）");
        assertEquals(LocalDate.of(2027, 2, 1), yearOne.windowStart(), "窗口前 1 个月");
        assertEquals(LocalDate.of(2027, 4, 1), yearOne.windowEnd(), "窗口后 1 个月（示例原文：窗口至 04-01）");
    }

    /** 预置全程留计划变更记录（A-FU-021）：随包草稿只写新建一条；发布条目由 B 侧激活时的 PlanService 产生（操作人非空）。 */
    @Test
    void presetChangeLogRecordsCreateOnly() {
        assertTrue(presetSql.contains("'PLAN_CREATE'"), "预置应写入新建变更记录（fu_plan_log）");
        assertFalse(
                presetSql.contains("'PLAN_PUBLISH'"),
                "随包为未发布草稿：发布变更由 B 侧激活（PlanService.publish）产生，不在迁移里");
        assertTrue(presetSql.contains(PLAN_CODE + "', 'PLAN_CREATE'"), "新建记录指向预置计划族");
    }

    // ---------------------------------------------------------------- 迁移 SQL 解析（口径同
    // APresetPlanSqlTest）

    private static String read(String path) {
        try (InputStream in = BPresetPlanSqlTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "迁移脚本应在 classpath：" + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读迁移脚本失败：" + path, e);
        }
    }

    /** 单行 INSERT 的「列 → 值」（顶层逗号切分，单引号内豁免；NULL → null）。 */
    private static Map<String, String> singleInsert(String sql, String table) {
        String insert = statementOf(sql, table, 0);
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
        return row;
    }

    /** fu_plan_node 的全部 INSERT（含每条后随 SET 变量名——子计划行用它被节点 parent_id 引用）。 */
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
            // 紧随本条 INSERT 到下一条 INSERT 之间的 SET @x = LAST_INSERT_ID() 即该行的变量（仅子计划行有）
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

    /** 从 from 起找下一条 INSERT INTO table 语句（到分号止）；找不到抛 IllegalStateException 结束循环。 */
    private static String statementOf(String sql, String table, int from) {
        int head = sql.indexOf("INSERT INTO " + table + " ", from);
        if (head < 0) {
            throw new IllegalStateException("no more INSERT INTO " + table);
        }
        int end = sql.indexOf(';', head);
        assertTrue(end > 0, table + " 的 INSERT 语句应以分号结束");
        return sql.substring(head, end);
    }

    /** 顶层逗号切分（单引号字符串内豁免，'' 为转义）。 */
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

    private static Map<String, String> subplan(String name) {
        return parsedNodes.stream()
                .filter(
                        n ->
                                "SUBPLAN".equals(n.row().get("node_type"))
                                        && name.equals(n.row().get("name")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("子计划不存在：" + name))
                .row();
    }

    private static Map<String, String> node(String name) {
        return parsedNodes.stream()
                .filter(
                        n ->
                                "NODE".equals(n.row().get("node_type"))
                                        && name.equals(n.row().get("name")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("节点不存在：" + name))
                .row();
    }

    private static List<String> nodeNames() {
        return List.of("术后第 1 天", "术后第 3 天", "术后第 5 天", "术后 3 个月", "电话随访");
    }

    private static void assertNode(
            String name,
            String parentVar,
            int offsetValue,
            String offsetUnit,
            int remindValue,
            String remindUnit,
            int windowBefore,
            String windowBeforeUnit,
            int windowAfter,
            String windowAfterUnit,
            int sortNo) {
        Map<String, String> row = node(name);
        assertEquals("NODE", row.get("node_type"));
        assertEquals(parentVar, row.get("parent_id"), name + " 挂接的子计划（SET 变量）");
        assertEquals("PLAN_START", row.get("basis"), name + "：基准 = 计划起算日期（手术日期）");
        assertEquals(String.valueOf(offsetValue), row.get("offset_value"), name + "：偏移数值");
        assertEquals(offsetUnit, row.get("offset_unit"), name + "：偏移单位");
        assertEquals(String.valueOf(remindValue), row.get("remind_value"), name + "：提醒提前量");
        assertEquals(remindUnit, row.get("remind_unit"), name + "：提醒单位");
        assertEquals(String.valueOf(windowBefore), row.get("window_before_value"), name + "：窗口前");
        assertEquals(windowBeforeUnit, row.get("window_before_unit"));
        assertEquals(String.valueOf(windowAfter), row.get("window_after_value"), name + "：窗口后");
        assertEquals(windowAfterUnit, row.get("window_after_unit"));
        assertEquals("FORM", row.get("task_type"), name + "：电话随访 / 随访任务 + 表单任务");
        assertEquals("DOCTOR", row.get("owner_type"), name + "：责任人 = 责任医生");
        assertEquals("BACKFILL", row.get("passed_window_action"), name + "：已过窗口补建补录（Q44）");
        assertEquals(String.valueOf(sortNo), row.get("sort_no"), name + "：排序");
        assertNull(row.get("enroll_mode"), name + "：无启用条件（B 不做 ERI）");
    }

    /** 提前量 / 窗口前的数值 + 单位折算成天（周 = 7 天；月单位不应出现在该等价断言里）。 */
    private static long daysOf(String value, String unit) {
        int v = Integer.parseInt(value);
        return "WEEK".equals(unit) ? (long) v * 7 : v;
    }

    /** 归一入参：子计划以 SET 变量名为 key，节点 key = 「node:名称」、parentKey = 其 parent_id 引用的变量。 */
    private static List<PlanNodeRule.NodeInput> nodeInputs() {
        List<PlanNodeRule.NodeInput> inputs = new ArrayList<>();
        for (ParsedNode parsed : parsedNodes) {
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

    /** 调度器入参：子计划 id = SET 变量号（101/102/103 按出现序），节点 id 从 1001 起按序。 */
    private static List<TaskCreationPlanner.NodeDef> nodeDefs() {
        Map<String, Long> varIds = new LinkedHashMap<>();
        long subId = 101;
        for (ParsedNode parsed : parsedNodes) {
            if ("SUBPLAN".equals(parsed.row().get("node_type"))) {
                assertNotNull(parsed.var(), "子计划行应有 SET 变量：" + parsed.row().get("name"));
                varIds.put(parsed.var(), subId++);
            }
        }
        List<TaskCreationPlanner.NodeDef> defs = new ArrayList<>();
        long seq = 1001;
        for (ParsedNode parsed : parsedNodes) {
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

    private static PlanDateCalculator.NodeTiming timingOf(TaskCreationPlanner.NodeDef node) {
        return new PlanDateCalculator.NodeTiming(
                node.offsetValue(),
                node.offsetUnit(),
                node.remindValue(),
                node.remindUnit(),
                node.windowBeforeValue(),
                node.windowBeforeUnit(),
                node.windowAfterValue(),
                node.windowAfterUnit());
    }

    /** due 任务按节点名称索引（NodeDef 无名称，这里按 id 反查解析行）。 */
    private static Map<String, TaskCreationPlanner.PlannedTask> byNodeName(
            List<TaskCreationPlanner.NodeDef> defs, List<TaskCreationPlanner.PlannedTask> tasks) {
        Map<Long, String> names = new LinkedHashMap<>();
        for (TaskCreationPlanner.NodeDef def : defs) {
            parsedNodes.stream()
                    .filter(n -> n.row().get("name") != null && idOf(n) == def.id())
                    .findFirst()
                    .ifPresent(n -> names.put(def.id(), n.row().get("name")));
        }
        Map<String, TaskCreationPlanner.PlannedTask> byName = new LinkedHashMap<>();
        for (TaskCreationPlanner.PlannedTask task : tasks) {
            String name = names.get(task.nodeId());
            assertNotNull(name, "due 任务应能定位到节点名：" + task.nodeId());
            byName.put(name, task);
        }
        return byName;
    }

    /** 与 nodeDefs() 同口径的行 id。 */
    private static long idOf(ParsedNode parsed) {
        Map<String, String> row = parsed.row();
        if ("SUBPLAN".equals(row.get("node_type"))) {
            Map<String, Long> varIds = new LinkedHashMap<>();
            long subId = 101;
            for (ParsedNode candidate : parsedNodes) {
                if ("SUBPLAN".equals(candidate.row().get("node_type"))) {
                    varIds.put(candidate.var(), subId++);
                }
            }
            return varIds.get(parsed.var());
        }
        long seq = 1001;
        for (ParsedNode candidate : parsedNodes) {
            if ("NODE".equals(candidate.row().get("node_type"))) {
                if (candidate == parsed) {
                    return seq;
                }
                seq++;
            }
        }
        throw new IllegalStateException("行不在节点集合中");
    }

    private static void assertSchedule(
            TaskCreationPlanner.PlannedTask task,
            String createType,
            LocalDate planDate,
            LocalDate windowStart,
            LocalDate windowEnd) {
        assertNotNull(task);
        assertEquals(createType, task.createType());
        assertEquals(planDate, task.planDate());
        assertEquals(windowStart, task.windowStart());
        assertEquals(windowEnd, task.windowEnd());
        assertEquals(1, task.occurrence(), "固定里程碑第 1 次");
    }

    private static Integer intOrNull(String raw) {
        return raw == null ? null : Integer.valueOf(raw);
    }
}
