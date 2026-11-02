package com.crdp.form.rule.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.crdp.form.rule.infra.mapper.RuleRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E11-T04 / A-FLD-003、A-FLD-006、C-FLD-007：范围与跨字段校验引擎单元测试；E11-T03 / A-FLD-004、C-FLD-005
 * 追加条件必填与隐藏字段掩蔽。
 *
 * <p>验收原文：前后端校验一致，超范围拦截。锁定：超硬范围不可保存（errors）、软范围提示可保存（warnings，硬命中不叠加 软提示）、pair_number
 * 两分量各查范围、派生与空值跳过；COMPARE 规则 lt / lte 语义（日期、日期时间、数值；lt 不含相等）、一侧为空不触发、 HARD / SOFT 分流；E11-T03：有效必填缺失
 * errors（rule=required，文案「必填」）、隐藏字段残留值不查范围 / 跨字段、必填只在显示中字段上生效；
 * 文案与前端逐字一致——由<b>共享用例</b>（shared-cases/form-validation-cases.json，components.md §10.5）双端
 * 各跑一遍保证。全部虚构数据。
 */
class FormRuleEngineTest {

    private final FormConditionEngine conditionEngine = new FormConditionEngine();
    private final FormRuleEngine engine = new FormRuleEngine(conditionEngine);

    // ---------------------------------------------------------------- 共享用例（前后端一致）

    /**
     * 共享用例文件是前后端校验一致的唯一事实来源（components.md §10.5）：本测试与前端 packages/rules/src/form-validation.test.ts
     * 读同一份 JSON，errors / warnings 逐条（key、categoryKey、 rule、message）一致。用例可带条件字段（required /
     * visibleWhen / requiredWhen）与条件上下文（context，E11-T03）。
     */
    @Test
    void executesSharedCasesIdenticallyWithFrontend() throws Exception {
        JsonNode file;
        try (InputStream in =
                getClass().getResourceAsStream("/shared-cases/form-validation-cases.json")) {
            file = new ObjectMapper().readTree(in);
        }
        List<String> names = new ArrayList<>();
        for (JsonNode item : file.path("cases")) {
            names.add(item.path("name").asText());
            RuleResult result =
                    engine.validate(
                            caseData(item),
                            caseFields(item),
                            caseRules(item),
                            caseMap(item.path("context")));
            assertIssues(item.path("expectedErrors"), result.errors(), "errors");
            assertIssues(item.path("expectedWarnings"), result.warnings(), "warnings");
        }
        assertEquals(22, names.size(), "共享用例数（增删用例须两端同步）");
    }

    // ---------------------------------------------------------------- E11-T03：条件必填与隐藏掩蔽

    /** 有效必填 = 显示 &&（required || requiredWhen）；缺失 errors 带固定码 required 与文案「必填」。 */
    @Test
    void requiresVisibleFieldsOnlyWhenConditionHolds() {
        VersionFieldRow death = conditionField("f216", "f215", false);
        // 是否死亡=是：死亡日期显示 + requiredWhen 成立 + 值缺失 → 必填错误
        List<FieldIssue> errors =
                engine.validate(Map.of("f215", 1), List.of(trigger(), death), List.of(), Map.of())
                        .errors();
        assertEquals(List.of(new FieldIssue("f216", "endpoint", "required", "必填")), errors);

        // 是否死亡=是 且已填：无错误
        assertTrue(
                engine.validate(
                                Map.of("f215", 1, "f216", "2024-01-02"),
                                List.of(trigger(), death),
                                List.of(),
                                Map.of())
                        .ok());

        // 是否死亡=否：隐藏 → 残留值不报必填
        assertTrue(
                engine.validate(
                                Map.of("f215", 0, "f216", "2024-01-02"),
                                List.of(trigger(), death),
                                List.of(),
                                Map.of())
                        .ok(),
                "隐藏字段不报必填（值由提交流程清空）");
    }

    /** 隐藏字段的残留值不查范围、不参与跨字段比较（提交时按已清空口径校验，tech-plan §6）。 */
    @Test
    void masksHiddenValuesFromRangeAndCrossFieldChecks() {
        VersionFieldRow amount =
                new VersionFieldRow(
                        1L,
                        1L,
                        "A_BASELINE",
                        1,
                        "f012",
                        "cigarettes_per_day",
                        "每日吸烟支数（示例）",
                        "basic_info",
                        "number",
                        "支",
                        0,
                        "{\"min\":1,\"max\":100}",
                        null,
                        null,
                        false,
                        "{\"all\":[{\"field\":\"f010\",\"op\":\"eq\",\"value\":1}]}",
                        "{\"all\":[{\"field\":\"f010\",\"op\":\"eq\",\"value\":1}]}",
                        null,
                        false,
                        "json",
                        null,
                        45);
        // f010=0：f012 隐藏，残留 300 超硬范围不拦截、也不作跨字段比较值
        assertTrue(
                engine.validate(
                                Map.of("f010", 0, "f012", 300),
                                List.of(amount, smoking()),
                                List.of())
                        .ok(),
                "隐藏残留值不查范围");

        // 同样数据但 f010=1：f012 显示 → 超硬范围拦截
        List<FieldIssue> errors =
                engine.validate(
                                Map.of("f010", 1, "f012", 300),
                                List.of(amount, smoking()),
                                List.of())
                        .errors();
        assertEquals(
                List.of(new FieldIssue("f012", "basic_info", "hardRange", "取值应在 1–100 之间")),
                errors);
    }

    /** ctx 上下文参与必填（C-FLD-005：ICD 项目只在 device.type 为 ICD / CRT-D 时必填）。 */
    @Test
    void contextDrivesConditionalRequired() {
        VersionFieldRow icdItem =
                new VersionFieldRow(
                        1L,
                        1L,
                        "C_PROGRAM_FU",
                        1,
                        "f700",
                        "icd_discharge",
                        "ICD 放电次数（示例）",
                        "icd",
                        "number",
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                        "{\"all\":[{\"ctx\":\"device.type\",\"op\":\"in\",\"value\":[\"ICD\",\"CRT-D\"]}]}",
                        "{\"all\":[{\"ctx\":\"device.type\",\"op\":\"in\",\"value\":[\"ICD\",\"CRT-D\"]}]}",
                        null,
                        false,
                        "json",
                        null,
                        10);
        assertTrue(
                !engine.validate(
                                Map.of(),
                                List.of(icdItem),
                                List.of(),
                                Map.of("device", Map.of("type", "ICD")))
                        .ok(),
                "ICD 器械：ICD 项目必填缺失");
        assertTrue(
                engine.validate(
                                Map.of(),
                                List.of(icdItem),
                                List.of(),
                                Map.of("device", Map.of("type", "VVIR")))
                        .ok(),
                "非 ICD 器械：不显示也不必填");
    }

    // ---------------------------------------------------------------- 写入闸门（草稿）只查硬范围

    @Test
    void rangeErrorsOnlyCarryHardViolations() {
        List<VersionFieldRow> fields = List.of(height());
        // 软范围外（120 < 130）但硬范围内：写入闸门放行（提示在提交校验里返回）
        assertTrue(engine.rangeErrors(Map.of("f011", 120), fields).isEmpty(), "软范围不拦保存");
        // 超硬范围：errors 带 fieldErrors 口径（key / categoryKey / rule / message）
        List<FieldIssue> errors = engine.rangeErrors(Map.of("f011", 300), fields);
        assertEquals(1, errors.size());
        assertEquals(
                new FieldIssue("f011", "basic_info", "hardRange", "取值应在 50–250 之间"), errors.get(0));
        // 空值与未知键跳过
        assertTrue(engine.rangeErrors(Map.of(), fields).isEmpty());
        assertTrue(engine.rangeErrors(null, fields).isEmpty());
    }

    // ---------------------------------------------------------------- 跨字段：不可比不触发（防御）

    @Test
    void skipsIncomparableOrMalformedRules() {
        // 数值 × 日期不可比：不触发（配置错误在字段字典评审与共享用例里拦，不炸保存）
        List<FieldIssue> none =
                engine.validate(
                                Map.of("f011", 170, "f020", "2024-05-06"),
                                List.of(height(), field("f020", "date")),
                                List.of(rule("mixed", "HARD", "f011", "lt", "f020", "示例规则")))
                        .errors();
        assertTrue(none.isEmpty(), "数值与日期不可比：规则不触发");

        // params 残缺 / op 不认识：防御跳过
        assertTrue(
                engine.validate(
                                Map.of("f011", 170),
                                List.of(height()),
                                List.of(
                                        rule("bad_op", "HARD", "f011", "gt", "f020", "示例规则"),
                                        malformedParams()))
                        .errors()
                        .isEmpty(),
                "坏规则配置不炸保存");

        // 非日期字符串（文本字段）不比较
        assertTrue(
                engine.validate(
                                Map.of("f090", "文字（示例）", "f091", "2024-05-06"),
                                List.of(field("f090", "text"), field("f091", "date")),
                                List.of(rule("text_rule", "HARD", "f091", "lte", "f090", "示例规则")))
                        .errors()
                        .isEmpty(),
                "普通文本不参与跨字段比较");
    }

    // ---------------------------------------------------------------- 辅助（全部虚构）

    /** 是否死亡（触发字段，radio 0=否 / 1=是，示例）。 */
    private static VersionFieldRow trigger() {
        return new VersionFieldRow(
                10L,
                1L,
                "A_ANNUAL_FU",
                1,
                "f215",
                "death_flag",
                "是否死亡",
                "endpoint",
                "radio",
                null,
                null,
                null,
                null,
                "yesno",
                true,
                null,
                null,
                null,
                false,
                "json",
                null,
                10);
    }

    /** 死亡日期（条件字段：f215=1 才显示，示例）；baseRequired 控制基础 required 列。 */
    private static VersionFieldRow conditionField(
            String key, String triggerKey, boolean baseRequired) {
        String condition =
                "{\"all\":[{\"field\":\"" + triggerKey + "\",\"op\":\"eq\",\"value\":1}]}";
        return new VersionFieldRow(
                11L,
                1L,
                "A_ANNUAL_FU",
                1,
                key,
                key + "_code",
                "死亡日期（示例）",
                "endpoint",
                "date",
                null,
                null,
                null,
                null,
                null,
                baseRequired,
                condition,
                condition,
                null,
                false,
                "json",
                null,
                20);
    }

    /** 吸烟史（radio 0=否 / 1=是，示例）。 */
    private static VersionFieldRow smoking() {
        return new VersionFieldRow(
                12L,
                1L,
                "A_BASELINE",
                1,
                "f010",
                "smoking_history",
                "吸烟史",
                "basic_info",
                "radio",
                null,
                null,
                null,
                null,
                "yesno",
                true,
                null,
                null,
                null,
                false,
                "json",
                null,
                40);
    }

    private static VersionFieldRow height() {
        return new VersionFieldRow(
                2L,
                1L,
                "A_BASELINE",
                1,
                "f011",
                "height_cm",
                "身高",
                "basic_info",
                "number",
                "cm",
                1,
                "{\"min\":50,\"max\":250}",
                "{\"min\":130,\"max\":210}",
                null,
                false,
                null,
                null,
                null,
                false,
                "json",
                null,
                50);
    }

    private static VersionFieldRow field(String key, String type) {
        return new VersionFieldRow(
                3L,
                1L,
                "A_BASELINE",
                1,
                key,
                key + "_code",
                "字段「" + key + "」（示例）",
                "surgery",
                type,
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                false,
                "json",
                null,
                0);
    }

    private static RuleRow rule(
            String id, String severity, String left, String op, String right, String message) {
        return new RuleRow(
                1L,
                "A_BASELINE",
                id,
                "surgery",
                right,
                severity,
                "COMPARE",
                "{\"leftField\":\""
                        + left
                        + "\",\"op\":\""
                        + op
                        + "\",\"rightField\":\""
                        + right
                        + "\"}",
                message,
                10);
    }

    private static RuleRow malformedParams() {
        return new RuleRow(
                2L,
                "A_BASELINE",
                "broken",
                "surgery",
                "f011",
                "HARD",
                "COMPARE",
                "{\"op\":\"lt\"}",
                "残缺参数（示例）",
                20);
    }

    private static void assertIssues(JsonNode expected, List<FieldIssue> actual, String kind) {
        List<FieldIssue> wanted = new ArrayList<>();
        for (JsonNode item : expected) {
            wanted.add(
                    new FieldIssue(
                            item.path("key").asText(),
                            item.path("categoryKey").asText(),
                            item.path("rule").asText(),
                            item.path("message").asText()));
        }
        assertEquals(wanted, actual, kind + " 与共享用例一致（含文案，前后端逐字相同）");
    }

    private static Map<String, Object> caseData(JsonNode item) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        item.path("data")
                .fields()
                .forEachRemaining(entry -> data.put(entry.getKey(), jsonValue(entry.getValue())));
        return data;
    }

    /** JSON 树 → 引擎输入值（数值 / 字符串 / 布尔 / 数组 / 对象），口径同 fe_record.data 反序列化。 */
    private static Object jsonValue(JsonNode node) {
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(jsonValue(item)));
            return list;
        }
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        node.fields()
                .forEachRemaining(entry -> map.put(entry.getKey(), jsonValue(entry.getValue())));
        return map;
    }

    private static List<VersionFieldRow> caseFields(JsonNode item) {
        List<VersionFieldRow> fields = new ArrayList<>();
        for (JsonNode field : item.path("fields")) {
            fields.add(
                    new VersionFieldRow(
                            1L,
                            1L,
                            "A_BASELINE",
                            1,
                            field.path("key").asText(),
                            field.path("key").asText() + "_code",
                            "字段「" + field.path("key").asText() + "」（示例）",
                            field.path("categoryKey").asText(),
                            field.path("type").asText("text"),
                            null,
                            null,
                            rangeJson(field.path("hardRange")),
                            rangeJson(field.path("softRange")),
                            null,
                            field.path("required").asBoolean(false),
                            conditionJson(field.path("visibleWhen")),
                            conditionJson(field.path("requiredWhen")),
                            null,
                            false,
                            field.path("storage").asText("json"),
                            null,
                            0));
        }
        return fields;
    }

    private static String conditionJson(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.toString();
    }

    /** 用例的条件上下文（可省略；E11-T03 ctx 叶子取值来源）。 */
    private static Map<String, Object> caseMap(JsonNode node) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        if (node.isObject()) {
            node.fields()
                    .forEachRemaining(
                            entry -> map.put(entry.getKey(), jsonValue(entry.getValue())));
        }
        return map;
    }

    private static String rangeJson(JsonNode range) {
        return range.isMissingNode() || range.isNull() ? null : range.toString();
    }

    private static List<RuleRow> caseRules(JsonNode item) {
        List<RuleRow> rules = new ArrayList<>();
        for (JsonNode rule : item.path("rules")) {
            rules.add(
                    new RuleRow(
                            1L,
                            "A_BASELINE",
                            rule.path("id").asText(),
                            rule.path("categoryKey").asText(),
                            rule.path("key").asText(),
                            rule.path("severity").asText(),
                            "COMPARE",
                            rule.path("params").toString(),
                            rule.path("message").asText(),
                            rule.path("sortNo").asInt(0)));
        }
        return rules;
    }
}
