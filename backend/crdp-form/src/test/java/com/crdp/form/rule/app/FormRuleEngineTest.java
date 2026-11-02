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
 * E11-T04 / A-FLD-003、A-FLD-006、C-FLD-007：范围与跨字段校验引擎单元测试。
 *
 * <p>验收原文：前后端校验一致，超范围拦截。锁定：超硬范围不可保存（errors）、软范围提示可保存（warnings，硬命中不叠加 软提示）、pair_number
 * 两分量各查范围、派生与空值跳过；COMPARE 规则 lt / lte 语义（日期、日期时间、数值；lt 不含相等）、一侧为空不触发、 HARD / SOFT
 * 分流；文案与前端逐字一致——由<b>共享用例</b>（shared-cases/form-validation-cases.json，components.md §10.5）双端
 * 各跑一遍保证。全部虚构数据。
 */
class FormRuleEngineTest {

    private final FormRuleEngine engine = new FormRuleEngine();

    // ---------------------------------------------------------------- 共享用例（前后端一致）

    /**
     * 共享用例文件是前后端校验一致的唯一事实来源（components.md §10.5）：本测试与前端 packages/rules/src/form-validation.test.ts
     * 读同一份 JSON，errors / warnings 逐条（key、categoryKey、 rule、message）一致。
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
            RuleResult result = engine.validate(caseData(item), caseFields(item), caseRules(item));
            assertIssues(item.path("expectedErrors"), result.errors(), "errors");
            assertIssues(item.path("expectedWarnings"), result.warnings(), "warnings");
        }
        assertEquals(19, names.size(), "共享用例数（增删用例须两端同步）");
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

    /** JSON 树 → 引擎输入值（数值 / 字符串 / 对象），口径同 fe_record.data 反序列化。 */
    private static Object jsonValue(JsonNode node) {
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isTextual()) {
            return node.asText();
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
                            field.path("type").asText(),
                            null,
                            null,
                            rangeJson(field.path("hardRange")),
                            rangeJson(field.path("softRange")),
                            null,
                            false,
                            null,
                            null,
                            null,
                            false,
                            "json",
                            null,
                            0));
        }
        return fields;
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
