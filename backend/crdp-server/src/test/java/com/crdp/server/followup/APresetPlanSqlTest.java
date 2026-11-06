package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.plan.domain.PlanNodeRule;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * E23-T02 核对（单元）：预置计划迁移 V61 与医院 A 规格「随访计划结构（A 医院预置）」逐项一致（A-FU-017）， 通知事件默认规则与规格 §7「关键字段 ·
 * 通知事件默认设置」逐项一致（A-NTF-001，V22 种子）。
 *
 * <p>基准是 docs/requirements/hospital-a-spec.md（A-FU-016/017、A-FU-001/002、Q35、§7 通知事件默认设置），
 * 比对对象是随应用一起发布的迁移脚本 db/tenant/followup/V61__hospital_a_plan_preset.sql 与
 * V22__ntf_rules.sql：不依赖数据库，CI 与本地必跑； 落库后的同一口径由 {@link APresetPlanVerifyIT}（门控
 * CRDP_IT_MYSQL_PORT）对真实 MySQL 再核一遍。
 *
 * <p>覆盖：
 *
 * <ul>
 *   <li>计划行（A-FU-017）：「冠心病术后年度随访」v1 已发布、出院日期起算、长期（cycle_type = ONGOING、cycle_years 为 NULL 不设结束年度）、
 *       登记即自动入组（Q35 / A-PAT-003）；
 *   <li>子计划行：持续循环（CYCLE）每年一次（周期 1 YEAR，直到死亡、失访或人工停止）；
 *   <li>节点行（A-FU-017）：出院日期 +1 年（PLAN_START 基准）、提前 7 天提醒、窗口前后各 7 天、电话随访 + 表单任务（A_ANNUAL_FU， V38
 *       字段字典随包）、责任人 = 责任医生、入组前已过窗口补建补录（BACKFILL，Q44）；
 *   <li>提醒开始 = 窗口开始（A-FU-001「A 医院两者都是计划日期前 7 天」→ A-NTF-001 同天合并为一条）；
 *   <li>预置参数能通过计划引擎结构校验（{@link PlanNodeRule#normalize} 原样通过，枚举与引擎一致）；
 *   <li>通知规则（A-NTF-001，V22 种子）：10 个事件的开关 / 提前天数 / 发送时间 / 渠道 / 接收人与规格 §7 表一致 （窗口预告 7 天、即将逾期 2
 *       天、已逾期站内信 + 短信且抄送病区负责人、每日汇总短信 + 站内信）；
 *   <li>预置全程留计划变更记录（A-FU-021：新建 + 发布，系统配置操作人为空）。
 * </ul>
 */
class APresetPlanSqlTest {

    private static final String PRESET = "db/tenant/followup/V61__hospital_a_plan_preset.sql";

    private static final String NTF_RULES = "db/tenant/followup/V22__ntf_rules.sql";

    private static final String FIELD_DICT = "db/tenant/followup/V38__hospital_a_field_dict.sql";

    private static Map<String, String> plan;
    private static Map<String, String> subplan;
    private static Map<String, String> node;
    private static Map<String, Map<String, String>> rules;
    private static String presetSql;

    @BeforeAll
    static void parse() {
        presetSql = read(PRESET);
        plan = singleInsert(presetSql, "fu_plan");
        Map<String, Map<String, String>> nodes = nodeInserts(presetSql);
        subplan = nodes.get("SUBPLAN");
        node = nodes.get("NODE");
        assertNotNull(subplan, "预置应含一个子计划行（SUBPLAN）");
        assertNotNull(node, "预置应含一个节点行（NODE）");
        rules = ntfRuleRows(read(NTF_RULES));
    }

    /** 计划行（A-FU-017）：名称、出院日期起算、长期不设结束年度、已发布、登记即自动入组。 */
    @Test
    void planRowMatchesSpec() {
        assertEquals("A_ANNUAL_FU_PLAN", plan.get("code"), "预置计划族编码（跨版本不变）");
        assertEquals("冠心病术后年度随访", plan.get("name"), "A-FU-017 计划名称");
        assertEquals("1", plan.get("version"), "预置为 v1");
        assertEquals("PUBLISHED", plan.get("status"), "预置即已发布（租户上线即可用）");
        assertEquals("DISCHARGE_DATE", plan.get("start_date_field"), "以出院日期起算");
        assertEquals("ONGOING", plan.get("cycle_type"), "长期随访（A-FU-002 / Q21）");
        assertNull(plan.get("cycle_years"), "不设结束年度（cycle_years 为 NULL）");
        assertEquals("1", plan.get("auto_enroll"), "出院登记后自动加入预置计划（Q35 / A-PAT-003）");
        assertNotNull(plan.get("published_at"), "已发布计划应有发布时间");
    }

    /** 子计划行：持续循环、每年一次（A-FU-016「无固定结束 / 持续循环」，直到死亡、失访或人工停止）。 */
    @Test
    void subplanIsOngoingYearlyCycle() {
        assertEquals("SUBPLAN", subplan.get("node_type"));
        assertNull(subplan.get("parent_id"), "子计划行 parent 为 NULL");
        assertEquals("CYCLE", subplan.get("repeat_mode"), "持续循环（A-FU-017：子计划为持续循环）");
        assertNull(subplan.get("repeat_count"), "持续循环不设固定次数");
        assertEquals("1", subplan.get("cycle_value"), "每年一次");
        assertEquals("YEAR", subplan.get("cycle_unit"));
    }

    /** 节点行（A-FU-017）：+1 年、提前 7 天提醒、窗口前后 7 天、电话随访 + 随访记录表单、责任医生、已过窗口补建。 */
    @Test
    void nodeIsAnnualTelephoneFollowup() {
        assertEquals("NODE", node.get("node_type"));
        assertNotNull(node.get("parent_id"), "节点挂在子计划下");
        assertEquals("电话随访", node.get("name"), "每个子计划含一项电话随访任务");
        assertEquals("PLAN_START", node.get("basis"), "基准 = 计划起算日期（出院日期）");
        assertEquals("1", node.get("offset_value"), "出院日期 +1 年");
        assertEquals("YEAR", node.get("offset_unit"));
        assertEquals("7", node.get("remind_value"), "提前 7 天提醒");
        assertEquals("DAY", node.get("remind_unit"));
        assertEquals("7", node.get("window_before_value"), "窗口前 7 天");
        assertEquals("DAY", node.get("window_before_unit"));
        assertEquals("7", node.get("window_after_value"), "窗口后 7 天");
        assertEquals("DAY", node.get("window_after_unit"));
        assertEquals("FORM", node.get("task_type"), "电话随访 + 随访记录表单任务");
        assertEquals("A_ANNUAL_FU", node.get("form_code"), "随访记录表单（V38 字段字典随包）");
        assertEquals("DOCTOR", node.get("owner_type"), "责任人 = 责任医生（A 规格计划结构图）");
        assertEquals("BACKFILL", node.get("passed_window_action"), "入组前已过窗口补建补录（Q44）");
    }

    /** 提醒开始日 = 窗口开始日（A-FU-001：A 医院两者都是计划日期前 7 天 → A-NTF-001 同天合并为一条）。 */
    @Test
    void remindStartEqualsWindowStart() {
        assertEquals(
                node.get("remind_value"),
                node.get("window_before_value"),
                "A 医院：提醒开始 = 窗口开始 = 计划日期前 7 天（同天合并，A-NTF-001）");
    }

    /** 表单 code 由医院 A 字段字典（E23-T01 / V38）随包提供，且为随访记录表单。 */
    @Test
    void formCodeShippedByFieldDict() {
        String dict = read(FIELD_DICT);
        assertTrue(
                dict.contains("('A_ANNUAL_FU', '年度随访记录', 'FOLLOWUP'"),
                "V38 应随包发布 A_ANNUAL_FU 随访记录表单");
    }

    /** 预置参数能通过计划引擎结构校验并原样通过（枚举、数值与 PlanNodeRule 一致，不靠手写 SQL 旁路）。 */
    @Test
    void presetPassesPlanNodeRuleValidation() {
        List<PlanNodeRule.NodeInput> inputs =
                List.of(
                        new PlanNodeRule.NodeInput(
                                "sub",
                                null,
                                subplan.get("name"),
                                subplan.get("repeat_mode"),
                                null,
                                intOf(subplan.get("cycle_value")),
                                subplan.get("cycle_unit"),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null),
                        new PlanNodeRule.NodeInput(
                                "task",
                                "sub",
                                node.get("name"),
                                null,
                                null,
                                null,
                                null,
                                node.get("basis"),
                                intOf(node.get("offset_value")),
                                node.get("offset_unit"),
                                intOf(node.get("remind_value")),
                                node.get("remind_unit"),
                                intOf(node.get("window_before_value")),
                                node.get("window_before_unit"),
                                intOf(node.get("window_after_value")),
                                node.get("window_after_unit"),
                                node.get("task_type"),
                                node.get("owner_type"),
                                node.get("form_code"),
                                node.get("passed_window_action"),
                                node.get("enroll_mode"),
                                intOf(node.get("sort_no"))));
        List<PlanNodeRule.NormalizedNode> normalized = PlanNodeRule.normalize(inputs);
        assertEquals(2, normalized.size(), "一层子计划 + 一个节点（A 长期年度随访的最小结构）");
        PlanNodeRule.NormalizedNode sub = normalized.get(0);
        assertEquals("SUBPLAN", sub.nodeType());
        assertEquals("CYCLE", sub.repeatMode());
        assertEquals(1, sub.cycleValue());
        assertEquals("YEAR", sub.cycleUnit());
        PlanNodeRule.NormalizedNode task = normalized.get(1);
        assertEquals("NODE", task.nodeType());
        assertEquals(1, task.offsetValue());
        assertEquals("YEAR", task.offsetUnit());
        assertEquals(7, task.remindValue());
        assertEquals("DAY", task.remindUnit());
        assertEquals(7, task.windowBeforeValue());
        assertEquals(7, task.windowAfterValue());
        assertEquals("FORM", task.taskType());
        assertEquals("DOCTOR", task.ownerType());
        assertEquals("A_ANNUAL_FU", task.formCode());
        assertEquals("BACKFILL", task.passedWindowAction());
    }

    /** 通知事件默认规则（A-NTF-001，V22 种子）与规格 §7「关键字字段 · 通知事件默认设置」逐项一致。 */
    @Test
    void notificationRulesMatchHospitalADefaults() {
        // 规格表 §7：事件 → (提前天数, 发送时间, 渠道, 接收人规则, 短信模板)；含 null 用 Arrays.asList
        Map<String, List<String>> spec =
                Map.ofEntries(
                        Map.entry(
                                "WINDOW_PREVIEW",
                                Arrays.asList("7", "08:30:00", "INBOX", "OWNER,WARD_STAFF", null)),
                        Map.entry(
                                "TASK_OPENED",
                                Arrays.asList(null, "08:30:00", "INBOX", "OWNER,WARD_STAFF", null)),
                        Map.entry(
                                "WINDOW_OPEN",
                                Arrays.asList(null, "08:30:00", "INBOX", "OWNER,WARD_STAFF", null)),
                        Map.entry(
                                "DUE_SOON",
                                Arrays.asList("2", "08:30:00", "INBOX", "OWNER,WARD_STAFF", null)),
                        Map.entry(
                                "TASK_OVERDUE",
                                Arrays.asList(
                                        null,
                                        "08:30:00",
                                        "INBOX,SMS",
                                        "OWNER,WARD_STAFF,CC_WARD_LEADER",
                                        "NOTIFY_OVERDUE")),
                        Map.entry(
                                "DAILY_DIGEST",
                                Arrays.asList(
                                        null,
                                        "08:30:00",
                                        "INBOX,SMS",
                                        "OWNER,WARD_STAFF",
                                        "NOTIFY_DAILY_SUMMARY")),
                        Map.entry(
                                "TASK_REASSIGNED",
                                Arrays.asList(null, null, "INBOX", "OWNER,WARD_STAFF", null)),
                        Map.entry(
                                "CALLBACK_DUE",
                                Arrays.asList(null, null, "INBOX", "OWNER,WARD_STAFF", null)),
                        Map.entry(
                                "UNREACHED_FOLLOWUP",
                                Arrays.asList(null, "08:30:00", "INBOX", "OWNER,WARD_STAFF", null)),
                        Map.entry(
                                "LOST_CANDIDATE",
                                Arrays.asList(
                                        null, "08:30:00", "INBOX", "OWNER,WARD_STAFF", null)));
        assertEquals(spec.keySet(), rules.keySet(), "A 规格列出的 10 个事件全部配置、无多余事件");
        for (Map.Entry<String, List<String>> expected : spec.entrySet()) {
            Map<String, String> row = rules.get(expected.getKey());
            assertNotNull(row, "事件应已配置：" + expected.getKey());
            assertEquals("1", row.get("enabled"), expected.getKey() + " 默认启用");
            assertEquals(
                    expected.getValue().get(0),
                    row.get("advance_days"),
                    expected.getKey() + " 提前天数");
            assertEquals(
                    expected.getValue().get(1), row.get("send_time"), expected.getKey() + " 发送时间");
            assertEquals(
                    expected.getValue().get(2), row.get("channels"), expected.getKey() + " 渠道");
            assertEquals(
                    expected.getValue().get(3),
                    row.get("recipients"),
                    expected.getKey() + " 接收人规则");
            assertEquals(
                    expected.getValue().get(4),
                    row.get("sms_template_code"),
                    expected.getKey() + " 短信模板");
        }
    }

    /** 预置全程留计划变更记录（A-FU-021「计划改动全记审计」：新建 + 发布，系统配置操作人为空）。 */
    @Test
    void presetChangeLogRecordsCreateAndPublish() {
        assertTrue(
                presetSql.contains("'PLAN_CREATE'") && presetSql.contains("'PLAN_PUBLISH'"),
                "预置应写入新建与发布两条变更记录（fu_plan_log）");
        assertTrue(
                presetSql.contains("A_ANNUAL_FU_PLAN', 'PLAN_CREATE'")
                        || presetSql.contains("PLAN_CREATE', '预置计划"),
                "新建记录指向预置计划族");
        assertTrue(presetSql.contains("operator_id)"), "变更记录列含操作人（预置为系统配置，值为 NULL）");
    }

    // ---------------------------------------------------------------- 迁移 SQL 解析

    private static String read(String path) {
        try (InputStream in = APresetPlanSqlTest.class.getClassLoader().getResourceAsStream(path)) {
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

    /** fu_plan_node 的两条 INSERT（SUBPLAN / NODE），按 node_type 分组；列序与 V46 建表一致。 */
    private static Map<String, Map<String, String>> nodeInserts(String sql) {
        String[] columns = {
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
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        int from = 0;
        while (true) {
            String insert;
            try {
                insert = statementOf(sql, "fu_plan_node", from);
            } catch (IllegalStateException end) {
                break;
            }
            from = sql.indexOf(insert, from) + insert.length();
            String values = insert.substring(insert.indexOf("VALUES") + "VALUES".length());
            values = values.substring(values.indexOf('(') + 1, values.lastIndexOf(')'));
            List<String> tuples = splitTopLevel(values);
            assertEquals(columns.length, tuples.size(), "fu_plan_node 列数与值数一致");
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.length; i++) {
                row.put(columns[i], token(tuples.get(i)));
            }
            rows.put(row.get("node_type"), row);
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

    /** V22 的 ntf_rule 种子行：event_type → 行（列序 = V22 INSERT 列序）。 */
    private static Map<String, Map<String, String>> ntfRuleRows(String sql) {
        String[] columns = {
            "event_type",
            "enabled",
            "advance_days",
            "send_time",
            "channels",
            "recipients",
            "sms_template_code"
        };
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        for (String line : sql.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("('") || !trimmed.endsWith("),") && !trimmed.endsWith(");")) {
                continue;
            }
            List<String> values = splitTopLevel(trimmed.substring(1, trimmed.lastIndexOf(')')));
            if (values.size() != columns.length) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.length; i++) {
                row.put(columns[i], token(values.get(i)));
            }
            rows.put(row.get("event_type"), row);
        }
        return rows;
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

    private static Integer intOf(String raw) {
        return raw == null ? null : Integer.valueOf(raw.strip());
    }
}
