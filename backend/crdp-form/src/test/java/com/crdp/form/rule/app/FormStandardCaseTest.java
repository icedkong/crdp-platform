package com.crdp.form.rule.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.crdp.form.rule.infra.mapper.RuleRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E11-T10 / A-FLD-004、A-FLD-005：表单引擎<b>条件、校验与派生标准用例</b>——三个引擎按提交流程串联后的 前后端一致性测试（验收原文：前后端同一用例集一致）。
 *
 * <p>E11-T03 / E11-T04 / E11-T05 各自的共享用例锁定<b>单引擎</b>两端一致；本测试执行综合标准用例 {@code
 * shared-cases/form-standard-cases.json}（components.md §10.5），把条件求值、隐藏清空、派生重算、提交校验按 tech-plan §6
 * 与渲染器的实际顺序组成<b>标准管线</b>，用同一份期望结果锁定组合语义：
 *
 * <ol>
 *   <li>条件求值（FormConditionEngine.visible）→ visible（字段声明序）；
 *   <li>隐藏字段残留值清空（同 FormService.stripHiddenFields：hiddenFieldKeys 中有值的键移除并记录） → clearedHidden（声明序）；
 *   <li>派生在<b>清空后</b>的数据上按拓扑重算（FormDerivedEngine.recalculate，渲染器清空后实时重算同口径） → derived（value /
 *       status / message，声明序）；
 *   <li>校验在「清空 + ok 计算值回写」的数据上执行（FormRuleEngine.validate；写入闸门口径：ok 覆盖、 invalid 且带手工值追加 errors
 *       rule=derived 在最后，与渲染器 hardErrors 组装同序）→ errors / warnings。
 * </ol>
 *
 * <p>组合顺序本身就是被测语义：清空先于派生（隐藏来源按缺失计）、派生先于校验（计算值参与跨字段比较）、 掩蔽先于跨字段（残留值不比较）。任何一端顺序不同都会与期望结果不符。全部虚构数据。
 */
class FormStandardCaseTest {

    private final FormConditionEngine conditionEngine = new FormConditionEngine();
    private final FormRuleEngine ruleEngine = new FormRuleEngine(conditionEngine);
    private final FormDerivedEngine derivedEngine = new FormDerivedEngine();

    // ---------------------------------------------------------------- 标准管线（前后端一致）

    /** 标准用例是表单引擎组合语义的唯一事实来源（components.md §10.5）：本测试与前端 form-standard.test 各执行一遍。 */
    @Test
    void executesStandardCasesIdenticallyWithFrontend() throws Exception {
        JsonNode file;
        try (InputStream in =
                getClass().getResourceAsStream("/shared-cases/form-standard-cases.json")) {
            file = new ObjectMapper().readTree(in);
        }
        int count = 0;
        for (JsonNode item : file.path("cases")) {
            String name = item.path("name").asText();
            List<VersionFieldRow> fields = caseFields(item);
            List<RuleRow> rules = caseRules(item);
            Map<String, Object> context = caseMap(item.path("context"));
            Map<String, Object> data = caseMap(item.path("data"));
            LocalDate today = caseToday(item);
            JsonNode expected = item.path("expected");

            // ① 条件求值 → visible（声明序）
            List<String> visible = new ArrayList<>();
            for (VersionFieldRow field : fields) {
                if (conditionEngine.visible(field, data, context)) {
                    visible.add(field.fieldKey());
                }
            }
            assertEquals(texts(expected.path("visible")), visible, name + " → visible");

            // ② 隐藏字段残留值清空（同 FormService.stripHiddenFields 的循环体）
            Map<String, Object> stripped = new LinkedHashMap<>(data);
            List<String> clearedHidden = new ArrayList<>();
            for (String key : conditionEngine.hiddenFieldKeys(fields, data, context)) {
                if (stripped.remove(key) != null) {
                    clearedHidden.add(key);
                }
            }
            assertEquals(
                    texts(expected.path("clearedHidden")),
                    clearedHidden,
                    name + " → clearedHidden（只含有值的隐藏键，声明序）");

            // ③ 派生在清空后的数据上重算（渲染器清空后实时重算同口径）
            Map<String, FormDerivedEngine.DerivedOutcome> outcomes =
                    derivedEngine.recalculate(fields, stripped, today);
            List<String> derivedKeys = new ArrayList<>();
            for (JsonNode node : expected.path("derived")) {
                derivedKeys.add(node.path("key").asText());
            }
            assertEquals(derivedKeys, List.copyOf(outcomes.keySet()), name + " → 派生结果键与顺序");
            for (JsonNode node : expected.path("derived")) {
                FormDerivedEngine.DerivedOutcome outcome = outcomes.get(node.path("key").asText());
                assertTrue(outcome != null, name + " → 派生字段应有结果");
                assertValue(node.path("value"), outcome.value(), name);
                assertEquals(
                        node.path("status").asText(),
                        outcome.status().code(),
                        name + " → derived status");
                if (node.path("message").isNull()) {
                    assertNull(outcome.message(), name + " → derived message 为空");
                } else {
                    assertEquals(
                            node.path("message").asText(),
                            outcome.message(),
                            name + " → derived message 逐字一致");
                }
            }

            // ④ 校验在「清空 + ok 计算值回写」的数据上执行（写入闸门同口径）
            Map<String, Object> effective = new LinkedHashMap<>(stripped);
            for (Map.Entry<String, FormDerivedEngine.DerivedOutcome> entry : outcomes.entrySet()) {
                if (entry.getValue().status() == FormDerivedEngine.DerivedStatus.OK) {
                    effective.put(entry.getKey(), entry.getValue().value());
                }
            }
            RuleResult result = ruleEngine.validate(effective, fields, rules, context);
            List<FieldIssue> errors = new ArrayList<>(result.errors());
            for (VersionFieldRow field : fields) {
                FormDerivedEngine.DerivedOutcome outcome = outcomes.get(field.fieldKey());
                if (outcome == null
                        || outcome.status() != FormDerivedEngine.DerivedStatus.INVALID) {
                    continue; // 结果无效且带手工值才拦截（FormDerivedEngine.applyToWrite 同口径）
                }
                if (!FormConditionEngine.isEmptyValue(effective.get(field.fieldKey()))) {
                    errors.add(
                            new FieldIssue(
                                    field.fieldKey(),
                                    field.categoryKey(),
                                    "derived",
                                    outcome.message()));
                }
            }
            assertIssues(expected.path("errors"), errors, name + " → errors");
            assertIssues(expected.path("warnings"), result.warnings(), name + " → warnings");
            count++;
        }
        assertEquals(12, count, "标准用例数（增删用例须两端同步）");
    }

    // ---------------------------------------------------------------- 用例装载（与共享 JSON 的 shape 对应）

    private static List<VersionFieldRow> caseFields(JsonNode item) {
        List<VersionFieldRow> fields = new ArrayList<>();
        for (JsonNode field : item.path("fields")) {
            String key = field.path("key").asText();
            fields.add(
                    new VersionFieldRow(
                            1L,
                            1L,
                            "A_ANNUAL_FU",
                            1,
                            key,
                            key,
                            field.path("label").asText(),
                            field.path("categoryKey").asText(),
                            field.path("type").asText("text"),
                            null,
                            null,
                            jsonOrNull(field.path("hardRange")),
                            jsonOrNull(field.path("softRange")),
                            null,
                            field.path("required").asBoolean(false),
                            jsonOrNull(field.path("visibleWhen")),
                            jsonOrNull(field.path("requiredWhen")),
                            jsonOrNull(field.path("derived")),
                            false,
                            field.path("storage").asText("json"),
                            null,
                            0));
        }
        return fields;
    }

    private static List<RuleRow> caseRules(JsonNode item) {
        List<RuleRow> rules = new ArrayList<>();
        int sort = 0;
        for (JsonNode rule : item.path("rules")) {
            rules.add(
                    new RuleRow(
                            1L,
                            "A_ANNUAL_FU",
                            rule.path("id").asText(),
                            rule.path("categoryKey").asText(),
                            rule.path("key").asText(),
                            rule.path("severity").asText("HARD"),
                            "COMPARE",
                            rule.path("params").toString(),
                            rule.path("message").asText(),
                            sort++));
        }
        return rules;
    }

    private static String jsonOrNull(JsonNode node) {
        return node.isObject() ? node.toString() : null;
    }

    private static LocalDate caseToday(JsonNode item) {
        JsonNode today = item.path("today");
        // 缺省 = 北京时间当天（与 FormDerivedEngine.recalculate 两参重载同口径）
        return today.isTextual()
                ? LocalDate.parse(today.asText())
                : LocalDate.now(FormDerivedEngine.ZONE);
    }

    private static List<String> texts(JsonNode expected) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : expected) {
            values.add(item.asText());
        }
        return values;
    }

    private static void assertIssues(JsonNode expected, List<FieldIssue> actual, String what) {
        List<FieldIssue> values = new ArrayList<>();
        for (JsonNode item : expected) {
            values.add(
                    new FieldIssue(
                            item.path("key").asText(),
                            item.path("categoryKey").asText(),
                            item.path("rule").asText(),
                            item.path("message").asText()));
        }
        assertEquals(values, actual, what);
    }

    private static Map<String, Object> caseMap(JsonNode node) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (node.isObject()) {
            node.fields()
                    .forEachRemaining(
                            entry -> map.put(entry.getKey(), jsonValue(entry.getValue())));
        }
        return map;
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
        Map<String, Object> map = new LinkedHashMap<>();
        node.fields()
                .forEachRemaining(entry -> map.put(entry.getKey(), jsonValue(entry.getValue())));
        return map;
    }

    private static void assertValue(JsonNode expected, Object actual, String caseName) {
        if (expected.isNull()) {
            assertNull(actual, caseName + " → value 留空");
        } else if (expected.isNumber()) {
            assertTrue(actual instanceof Number, caseName + " → value 应为数值，实际 " + actual);
            assertEquals(
                    expected.asDouble(),
                    ((Number) actual).doubleValue(),
                    1e-9,
                    caseName + " → value 数值一致");
        } else {
            assertEquals(expected.asText(), actual, caseName + " → value 文本一致");
        }
    }
}
