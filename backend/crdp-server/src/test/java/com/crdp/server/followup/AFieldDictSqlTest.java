package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * E23-T01 核对（单元）：迁移 V38 的医院 A 字段字典与规格附录 A「266 个字段」逐项一致（tasks.csv 验收原文）。
 *
 * <p>基准是 docs/requirements/hospital-a-spec.md 附录 A（{@link AFieldDictAppendix} 解析），比对对象是随应用一起发布的
 * 迁移脚本 db/tenant/followup/V38__hospital_a_field_dict.sql：不依赖数据库，CI 与本地必跑； 落库后的同一口径由
 * AFieldDictVerifyIT（门控 CRDP_IT_MYSQL_PORT）对真实 MySQL 再核一遍。
 *
 * <p>覆盖：266 行 ↔ 266 个稳定键 f001–f266（外加 A-FLD-013 出生日期 f267）一对一；显示名 / 字段编码 / 类型 / 单位 / 范围 / 必填 /
 * 选项编码与显示名逐项一致；敏感四字段（姓名、病案号、电话1、电话2）；#217 死亡随访时间按 Q22 合并停用； 9 个分类与随访 4 分组的字段数与规格 §3「9
 * 个分类概览」一致；类型分布与规格 §3「字段类型」表一致；派生公式与「派生字段计算规则」表一致； A-FLD-006 规则链（引擎可表达的 JSON 存储字段对）与演示种子清理。
 */
class AFieldDictSqlTest {

    private static final String MIGRATION = "db/tenant/followup/V38__hospital_a_field_dict.sql";

    /** 附录 类型 → fe_field.type（components.md §10.1）。 */
    private static final Map<String, String> TYPE_BY_APPENDIX =
            Map.ofEntries(
                    Map.entry("是否", "radio"),
                    Map.entry("单选", "select"),
                    Map.entry("数值", "number"),
                    Map.entry("整数", "number"),
                    Map.entry("数值×2", "pair_number"),
                    Map.entry("日期", "date"),
                    Map.entry("日期时间", "datetime"),
                    Map.entry("文本", "text"),
                    Map.entry("文本（敏感）", "text"),
                    Map.entry("引用", "select"),
                    Map.entry("派生·整数", "derived"),
                    Map.entry("派生·数值", "derived"));

    /** 附录序号区间 → 分类锚点键（fe_form.categories；基本信息 1–12 …… 随访四组 213–266）。 */
    private static String categoryOf(int no) {
        if (no <= 12) return "basic";
        if (no <= 49) return "case";
        if (no <= 67) return "lab";
        if (no <= 116) return "echo";
        if (no <= 134) return "angio";
        if (no <= 176) return "surgery";
        if (no <= 192) return "periop";
        if (no <= 212) return "discharge_med";
        if (no <= 218) return "fu_contact";
        if (no <= 235) return "fu_event";
        if (no <= 258) return "fu_echo";
        return "fu_med";
    }

    private static List<AFieldDictAppendix.Row> appendix;
    private static Map<String, Map<String, String>> fields;
    private static Map<String, Map<Integer, String>> optionSets;
    private static Map<String, Map<String, String>> rules;
    private static String sql;

    @BeforeAll
    static void parse() {
        appendix = AFieldDictAppendix.rows();
        sql = readMigration();
        fields = parseFieldRows(sql);
        optionSets = parseOptionRows(sql);
        rules = parseRuleRows(sql);
    }

    // ---------------------------------------------------------------- 一对一映射

    /**
     * 266 行附录 ↔ f001–f266 + 出生日期 f267（A-FLD-013）：键、表单、显示名逐项一致；字段编码为 ASCII（附录原表列名是中文，按 OQ-72 以拼音 /
     * 英文编码、原表列名完整保留在帮助说明）。
     */
    @Test
    void everyAppendixRowMapsToOneFieldWithSameLabelAndCode() {
        for (AFieldDictAppendix.Row row : appendix) {
            String key = "f" + String.format("%03d", row.no());
            Map<String, String> field = fields.get(key);
            assertNotNull(field, "附录 #" + row.no() + " 应有稳定键 " + key);
            assertEquals(
                    row.no() <= 212 ? "A_BASELINE" : "A_ANNUAL_FU",
                    field.get("form_code"),
                    "#" + row.no() + " 所属表单（1–212 基线、213–266 年度随访）");
            assertEquals(row.label(), field.get("label"), "#" + row.no() + " 显示名");
            assertTrue(
                    field.get("field_code").matches("[A-Za-z0-9_]{1,64}"),
                    "#"
                            + row.no()
                            + " 字段编码为 ASCII（E11-T09 导入工具口径，OQ-72）："
                            + field.get("field_code"));
            assertTrue(
                    field.get("help_text") != null
                            && field.get("help_text").contains("原表列名「" + row.code() + "」"),
                    "#" + row.no() + " 原表列名「" + row.code() + "」保留在帮助说明（核对锚点）");
            assertEquals(categoryOf(row.no()), field.get("category_key"), "#" + row.no() + " 分类");
        }
        Map<String, String> birth = fields.get("f267");
        assertNotNull(birth, "A-FLD-013 出生日期（f267）应在字典中");
        assertEquals("birth_date", birth.get("field_code"));
        assertEquals("date", birth.get("type"));
        assertEquals("basic", birth.get("category_key"));
        assertEquals("column", birth.get("storage_type"), "出生日期是患者主档实体列（pt_patient.birth_date）");
        assertEquals(267, fields.size(), "266 + 出生日期 = 267 行");
        assertEquals(
                267,
                fields.values().stream().map(field -> field.get("field_code")).distinct().count(),
                "字段编码唯一（表单内唯一，A-FLD-001）");
    }

    /**
     * 类型逐行一致，且分布与规格 §3「字段类型」表相同（是否 94 / 单选 43 / 数值 77 / 整数 4 / 数值×2 3 / 日期 18 / 日期时间 4 / 文本 11 /
     * 文本（敏感）4 / 引用 2 / 派生 6）。
     */
    @Test
    void typesAndDistributionMatchAppendix() {
        Map<String, Integer> appendixCounts = new LinkedHashMap<>();
        Map<String, Integer> dictCounts = new LinkedHashMap<>();
        for (AFieldDictAppendix.Row row : appendix) {
            String expected = TYPE_BY_APPENDIX.get(row.appendixType());
            assertNotNull(expected, "未知附录类型：" + row.appendixType());
            String key = "f" + String.format("%03d", row.no());
            assertEquals(expected, fields.get(key).get("type"), "#" + row.no() + " 类型");
            appendixCounts.merge(row.appendixType(), 1, Integer::sum);
            dictCounts.merge(fields.get(key).get("type"), 1, Integer::sum);
        }
        assertEquals(
                Map.ofEntries(
                        Map.entry("是否", 94),
                        Map.entry("单选", 43),
                        Map.entry("数值", 77),
                        Map.entry("整数", 4),
                        Map.entry("数值×2", 3),
                        Map.entry("日期", 18),
                        Map.entry("日期时间", 4),
                        Map.entry("文本", 11),
                        Map.entry("文本（敏感）", 4),
                        Map.entry("引用", 2),
                        Map.entry("派生·整数", 3),
                        Map.entry("派生·数值", 3)),
                appendixCounts,
                "附录类型分布 = 规格 §3「字段类型」表");
        assertEquals(
                Map.of(
                        "radio",
                        94,
                        "select",
                        45,
                        "number",
                        81,
                        "pair_number",
                        3,
                        "date",
                        18,
                        "datetime",
                        4,
                        "text",
                        15,
                        "derived",
                        6),
                dictCounts);
    }

    /** 分类字段数与规格 §3「9 个分类概览」一致；随访四组（A-FLD-007）6 / 17 / 23 / 8。 */
    @Test
    void categoryCountsMatchSpecOverview() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AFieldDictAppendix.Row row : appendix) {
            counts.merge(categoryOf(row.no()), 1, Integer::sum);
        }
        assertEquals(
                Map.ofEntries(
                        Map.entry("basic", 12),
                        Map.entry("case", 37),
                        Map.entry("lab", 18),
                        Map.entry("echo", 49),
                        Map.entry("angio", 18),
                        Map.entry("surgery", 42),
                        Map.entry("periop", 16),
                        Map.entry("discharge_med", 20),
                        Map.entry("fu_contact", 6),
                        Map.entry("fu_event", 17),
                        Map.entry("fu_echo", 23),
                        Map.entry("fu_med", 8)),
                counts);
        // 表单与分类声明：基线 8 分类、随访 4 分组（A-FLD-007）
        assertTrue(sql.contains("'A_ANNUAL_FU'") && sql.contains("FOLLOWUP"), "年度随访表单");
        for (String key :
                List.of(
                        "basic",
                        "case",
                        "lab",
                        "echo",
                        "angio",
                        "surgery",
                        "periop",
                        "discharge_med",
                        "fu_contact",
                        "fu_event",
                        "fu_echo",
                        "fu_med")) {
            assertTrue(
                    sql.contains("'key', '" + key + "'"),
                    "分类锚点键 " + key + " 应在 fe_form.categories 声明");
        }
    }

    // ---------------------------------------------------------------- 属性核对

    /** 单位与范围（附录「单位 X；范围 X–Y」→ unit / hard_range；身高软范围 130–210 承接 V28 口径满足 A-FLD-003 验收）。 */
    @Test
    void unitsAndRangesMatchAppendix() {
        for (AFieldDictAppendix.Row row : appendix) {
            Map<String, String> field = fields.get("f" + String.format("%03d", row.no()));
            String expectedUnit = row.unit();
            if ("数值×2".equals(row.appendixType())) {
                expectedUnit = "mm"; // 拆为长、宽两个数值（A 规格 §3：页面显示「长 × 宽」）
            }
            assertEquals(
                    expectedUnit == null ? null : expectedUnit,
                    field.get("unit"),
                    "#" + row.no() + " 单位");
            double[] range = row.range();
            if (range == null) {
                assertNull(field.get("hard_range"), "#" + row.no() + " 无范围则不设硬范围");
            } else {
                assertEquals(
                        "{\"min\":" + trimNum(range[0]) + ",\"max\":" + trimNum(range[1]) + "}",
                        field.get("hard_range").replace(" ", ""),
                        "#" + row.no() + " 硬范围（附录「范围」）");
            }
            if ("整数".equals(row.appendixType())) {
                assertEquals("0", field.get("decimals"), "#" + row.no() + " 整数小数位 0");
            }
        }
        assertEquals(
                "{\"min\":130,\"max\":210}",
                fields.get("f013").get("soft_range").replace(" ", ""),
                "身高软范围 130–210（A-FLD-003 验收：215 黄色提示可保存、300 超硬范围 100–220 不可保存）");
    }

    /** 选项编码与显示名逐项一致（A-FLD-002「数据库存编码、页面显示显示名」）；引用字典三字段不设固定选项。 */
    @Test
    void optionsMatchAppendixCodesAndLabels() {
        for (AFieldDictAppendix.Row row : appendix) {
            Map<String, String> field = fields.get("f" + String.format("%03d", row.no()));
            Map<Integer, String> expected = row.options();
            if (expected.isEmpty()) {
                assertNull(
                        field.get("option_set_code"),
                        "#" + row.no() + " 引用租户字典（患者类型 / 术者 / 病区），无固定选项集");
                continue;
            }
            String setCode = field.get("option_set_code");
            assertNotNull(setCode, "#" + row.no() + " 应挂选项集");
            Map<Integer, String> actual = optionSets.get(setCode);
            assertNotNull(actual, "选项集 " + setCode + " 应有选项");
            assertEquals(expected, actual, "#" + row.no() + " 选项编码与显示名");
        }
        // 瓣膜返流量按序数比较（「返流加重 ≥ 2 级」），序数 = 编码
        assertTrue(sql.contains("'a_mr_reflux', 0, '无', 0"), "返流量选项集带序数（无=0、微量=1、少量=2、中量=3、大量=4）");
    }

    /** 敏感四字段（姓名、病案号、电话1、电话2）加密存储；主档实体列字段（手术 / 入出院日期、性别、病区、术者、患者类型、出院状态、出生日期）走 column。 */
    @Test
    void sensitiveAndStorageMatchSpec() {
        for (int no : new int[] {2, 3, 9, 10}) {
            Map<String, String> field = fields.get("f" + String.format("%03d", no));
            assertEquals("1", field.get("sensitive_flag"), "#" + no + " 敏感标记");
            assertEquals("encrypted", field.get("storage_type"), "#" + no + " 加密列存储");
        }
        for (String key :
                List.of("f001", "f004", "f007", "f008", "f012", "f016", "f017", "f191", "f267")) {
            assertEquals(
                    "column",
                    fields.get(key).get("storage_type"),
                    key + " 患者主档 / 系统绑定列（tech-plan §6 混合存储）");
        }
        for (Map<String, String> field : fields.values()) {
            if (!"1".equals(field.get("sensitive_flag"))) {
                assertEquals("0", field.get("sensitive_flag"), "敏感标记只有 0 / 1");
            }
        }
    }

    /** 必填口径：附录「必填」10 项；“…时必填”为条件必填（required_when），基础 required 不置位。 */
    @Test
    void requiredFlagsMatchAppendix() {
        List<Integer> requiredNos =
                appendix.stream()
                        .filter(AFieldDictAppendix.Row::required)
                        .map(AFieldDictAppendix.Row::no)
                        .toList();
        assertEquals(List.of(1, 2, 3, 4, 7, 16, 17, 191, 214, 215), requiredNos, "附录基础必填项");
        for (AFieldDictAppendix.Row row : appendix) {
            Map<String, String> field = fields.get("f" + String.format("%03d", row.no()));
            assertEquals(row.required() ? "1" : "0", field.get("required"), "#" + row.no() + " 必填");
        }
    }

    /** 派生六字段与规格 §3「派生字段计算规则」表一致（公式已由用户确认）。 */
    @Test
    void derivedDefinitionsMatchSpec() {
        assertEquals(
                "{\"fn\":\"AGE_YEARS\",\"args\":[\"f267\",\"f001\"]}",
                fields.get("f005").get("derived_def").replace(" ", ""),
                "手术年龄 = 手术日期 − 出生日期（周岁）");
        assertEquals(
                "{\"fn\":\"BMI\",\"args\":[\"f014\",\"f013\"]}",
                fields.get("f015").get("derived_def").replace(" ", ""),
                "BMI = 体重 ÷ (身高 ÷ 100)²");
        assertEquals(
                "{\"fn\":\"DAYS_INCLUSIVE\",\"args\":[\"f001\",\"f017\"]}",
                fields.get("f018").get("derived_def").replace(" ", ""),
                "术后住院天数 = 出院 − 手术 + 1（含当天）");
        assertEquals(
                "{\"fn\":\"MINUTES_BETWEEN\",\"args\":[\"f138\",\"f139\"]}",
                fields.get("f140").get("derived_def").replace(" ", ""),
                "手术时长 = 结束 − 开始（分钟，向下取整）");
        assertEquals(
                "{\"fn\":\"SUM\",\"args\":[\"f150\",\"f151\"]}",
                fields.get("f149").get("derived_def").replace(" ", ""),
                "桥血管数量 = 动脉桥 + 静脉桥");
        assertEquals(
                "{\"fn\":\"HOURS_BETWEEN_1DP\",\"args\":[\"f187\",\"f188\"]}",
                fields.get("f189").get("derived_def").replace(" ", ""),
                "ICU 时间 = 出ICU − 进ICU（小时，1 位小数）");
    }

    /** #217 死亡随访时间按 Q22 与 #216 合并为同一字段：登记在册但停用（不进模板下发与校验），说明写明合并口径。 */
    @Test
    void deathFollowupTimeMergedAsDisabled() {
        Map<String, String> merged = fields.get("f217");
        assertEquals("DISABLED", merged.get("status"), "#217 停用（与 #216 合并）");
        assertTrue(merged.get("help_text").contains("合并"), "帮助说明写明合并");
        assertEquals("ENABLED", fields.get("f216").get("status"));
        assertEquals("date", fields.get("f216").get("type"));
        long enabled =
                fields.values().stream()
                        .filter(field -> "ENABLED".equals(field.get("status")))
                        .count();
        assertEquals(266, enabled, "266 启用（267 − 停用的 #217）");
    }

    /** 条件显示与条件必填（A-FLD-004）：死亡、复查超声、体外循环、再住院类型的联动。 */
    @Test
    void conditionTreesMatchSpec() {
        String deathVisible = cond("f215", "eq", 1);
        assertEquals(deathVisible, fields.get("f216").get("visible_when"), "是否死亡=是 → 死亡日期显示");
        assertEquals(deathVisible, fields.get("f218").get("required_when"), "死亡时必填死亡原因");
        assertEquals(
                cond("f236", "eq", 1), fields.get("f237").get("visible_when"), "复查超声=是 → 超声检查时间显示");
        assertEquals(
                cond("f236", "eq", 1), fields.get("f257").get("visible_when"), "复查超声=是 → 超声指标全部显示");
        assertEquals(
                cond("f141", "eq", 1), fields.get("f142").get("visible_when"), "体外循环=有 → 体外循环时间显示");
        assertEquals(
                cond("f141", "eq", 1),
                fields.get("f143").get("visible_when"),
                "体外循环=有 → 主动脉阻断时间显示");
        assertEquals(
                "{\"any\":[{\"field\":\"f223\",\"op\":\"notEmpty\"}]}",
                fields.get("f224").get("visible_when").replace(" ", ""),
                "选了再住院类型才显示再住院日期（A-FLD-007 验收）");
        assertEquals(
                "{\"any\":[{\"field\":\"f219\",\"op\":\"eq\",\"value\":1}]}",
                fields.get("f220").get("required_when").replace(" ", ""),
                "前项为「是」时必填");
        assertEquals(
                "{\"any\":[{\"field\":\"f233\",\"op\":\"ne\",\"value\":0}]}",
                fields.get("f234").get("required_when").replace(" ", ""),
                "术后出血类型 ≠ 无时必填发生时间");
    }

    /** A-FLD-006 规则链（OQ-64①）：引擎可表达的两条（JSON 存储字段对）；主档日期链随患者域编辑校验（E13-T02 / E15）。 */
    @Test
    void crossFieldRulesConfigured() {
        assertEquals(2, rules.size(), "只配引擎可执行的规则：" + rules.keySet());
        assertEquals("f139", rules.get("surgery_start_lt_end").get("target_field"));
        assertEquals("lt", rules.get("surgery_start_lt_end").get("params.op"));
        assertTrue(rules.get("surgery_start_lt_end").get("params.leftField").contains("f138"));
        assertEquals("f188", rules.get("icu_in_lt_out").get("target_field"));
        assertEquals("HARD", rules.get("icu_in_lt_out").get("severity"));
    }

    /** 演示种子（V28 / V31 / V33 / V36）被真实字典替换：清空演示字段 / 选项集 / 规则 / 记录与版本，重建 v1 快照。 */
    @Test
    void demoSeedReplacedAndSnapshotsRebuilt() {
        assertTrue(sql.contains("DELETE FROM fe_field WHERE form_code = 'A_BASELINE'"), "清演示字段");
        assertTrue(sql.contains("'yesno'"), "清演示选项集");
        assertTrue(sql.contains("DELETE FROM fe_rule WHERE form_code = 'A_BASELINE'"), "清演示规则");
        assertTrue(sql.contains("DELETE FROM fe_record WHERE form_code = 'A_BASELINE'"), "清演示记录");
        assertTrue(sql.contains("INSERT INTO fe_form_version_field"), "真实字典重建 v1 快照（V29 同款回填）");
        assertEquals(
                2,
                countOccurrences(sql, "SELECT COUNT(*) FROM fe_field f WHERE f.form_code"),
                "两个表单各重建一条 v1 版本行");
    }

    // ---------------------------------------------------------------- 迁移 SQL 解析

    private static String readMigration() {
        try (InputStream in =
                AFieldDictSqlTest.class.getClassLoader().getResourceAsStream(MIGRATION)) {
            assertNotNull(in, "迁移脚本应在 classpath：" + MIGRATION);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读迁移脚本失败：" + MIGRATION, e);
        }
    }

    /** 解析 fe_field 的 VALUES 行（生成器约定：一行一字段，列序固定；JSON 以字符串字面量书写）。 */
    private static Map<String, Map<String, String>> parseFieldRows(String sql) {
        String[] columns = {
            "form_code",
            "field_key",
            "field_code",
            "label",
            "category_key",
            "type",
            "unit",
            "decimals",
            "hard_range",
            "soft_range",
            "option_set_code",
            "required",
            "visible_when",
            "required_when",
            "derived_def",
            "sensitive_flag",
            "storage_type",
            "help_text",
            "sort_no",
            "status"
        };
        Map<String, Map<String, String>> rows = new LinkedHashMap<>();
        for (String line : sql.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("('A_") || !trimmed.endsWith("),") && !trimmed.endsWith(");")) {
                continue;
            }
            List<String> values = splitValues(bodyOf(trimmed));
            if (values.size() != columns.length) {
                continue;
            }
            Map<String, String> row = new HashMap<>();
            for (int i = 0; i < columns.length; i++) {
                row.put(columns[i], values.get(i));
            }
            rows.put(row.get("field_key"), row);
        }
        return rows;
    }

    /** 解析 fe_option 的 VALUES 行：set_code, option_code, label, ordinal, sort_no。 */
    private static Map<String, Map<Integer, String>> parseOptionRows(String sql) {
        Map<String, Map<Integer, String>> sets = new LinkedHashMap<>();
        for (String line : sql.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("('a_")) {
                continue;
            }
            List<String> values = splitValues(bodyOf(trimmed));
            if (values.size() < 5) {
                continue;
            }
            sets.computeIfAbsent(values.get(0), key -> new LinkedHashMap<>())
                    .put(Integer.parseInt(values.get(1)), values.get(2));
        }
        return sets;
    }

    /**
     * 解析 fe_rule 的 VALUES 行：form_code, rule_id, category_key, target_field, severity, rule_type,
     * params, message, sort_no。
     */
    private static Map<String, Map<String, String>> parseRuleRows(String sql) {
        Map<String, Map<String, String>> parsed = new LinkedHashMap<>();
        for (String line : sql.lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("('A_BASELINE', '") || !trimmed.contains("COMPARE")) {
                continue;
            }
            List<String> values = splitValues(bodyOf(trimmed));
            Map<String, String> rule = new HashMap<>();
            rule.put("rule_id", values.get(1));
            rule.put("target_field", values.get(3));
            rule.put("severity", values.get(4));
            String params = values.get(6);
            rule.put("params.op", params.replaceAll(".*\"op\":\"([a-z]+)\".*", "$1"));
            rule.put("params.leftField", params.replaceAll(".*\"leftField\":\"(f\\d+)\".*", "$1"));
            parsed.put(rule.get("rule_id"), rule);
        }
        return parsed;
    }

    /** 去掉行首 "(" 与行尾 ")," / ");"，返回括号内的值串。 */
    private static String bodyOf(String trimmed) {
        int open = trimmed.indexOf('(');
        int close = trimmed.lastIndexOf(')');
        return trimmed.substring(open + 1, close);
    }

    /** 顶层逗号切分（单引号字符串内豁免，'' 为转义）；NULL / 数字 / 裸词原样返回，字符串去引号。 */
    private static List<String> splitValues(String raw) {
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
                values.add(token(current.toString()));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(token(current.toString()));
        return values;
    }

    private static String token(String raw) {
        String trimmed = raw.strip();
        return "NULL".equals(trimmed) ? null : trimmed;
    }

    private static String cond(String field, String op, int value) {
        return "{\"any\":[{\"field\":\""
                + field
                + "\",\"op\":\""
                + op
                + "\",\"value\":"
                + value
                + "}]}";
    }

    private static String trimNum(double value) {
        return value == Math.floor(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
