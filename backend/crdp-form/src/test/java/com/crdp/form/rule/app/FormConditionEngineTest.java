package com.crdp.form.rule.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E11-T03 / A-FLD-004、C-FLD-005：条件 DSL（条件显示与条件必填）求值单元测试。
 *
 * <p>验收原文：前后端同一套用例结果一致。锁定（shared-cases/form-condition-cases.json，components.md §10.5，由本测试与 前端
 * packages/rules/src/form-condition.test 各执行一遍）：eq / ne / in / nin / gt / gte / lt / lte / empty /
 * notEmpty 全操作符；field 与 ctx（点路径）操作数；all / any 嵌套；操作数缺失除 empty / notEmpty 外不成立（未答 ≠ 否）；eq 严格同型 （1 ≠
 * "1"）；0 / false 是已答不算空；pair_number 分量全缺为空；storage 非 json 与派生字段不做必填。全部虚构数据。
 */
class FormConditionEngineTest {

    private final FormConditionEngine engine = new FormConditionEngine();

    // ---------------------------------------------------------------- 共享用例（前后端一致）

    /** 共享用例是前后端条件求值一致的唯一事实来源（components.md §10.5）：visible 与 missingRequired 逐条一致。 */
    @Test
    void executesSharedCasesIdenticallyWithFrontend() throws Exception {
        JsonNode file;
        try (InputStream in =
                getClass().getResourceAsStream("/shared-cases/form-condition-cases.json")) {
            file = new ObjectMapper().readTree(in);
        }
        int count = 0;
        for (JsonNode item : file.path("cases")) {
            List<VersionFieldRow> fields = caseFields(item);
            Map<String, Object> data = caseMap(item.path("data"));
            Map<String, Object> context = caseMap(item.path("context"));

            List<String> visible = new ArrayList<>();
            List<FieldIssue> missing = new ArrayList<>();
            for (VersionFieldRow field : fields) {
                if (engine.visible(field, data, context)) {
                    visible.add(field.fieldKey());
                }
                if ("json".equals(field.storageType())
                        && !"derived".equals(field.type())
                        && engine.visible(field, data, context)
                        && engine.requiredNow(field, data, context)
                        && FormConditionEngine.isEmptyValue(data.get(field.fieldKey()))) {
                    missing.add(
                            new FieldIssue(
                                    field.fieldKey(), field.categoryKey(), "required", "必填"));
                }
            }
            assertEquals(
                    texts(item.path("expectedVisible")),
                    visible,
                    item.path("name").asText() + " → visible 与共享用例一致");
            assertEquals(
                    issues(item.path("expectedMissingRequired")),
                    missing,
                    item.path("name").asText() + " → missingRequired 与共享用例一致（含文案）");
            count++;
        }
        assertEquals(27, count, "共享用例数（增删用例须两端同步）");
    }

    // ---------------------------------------------------------------- 防御与边界（共享用例之外的锁定）

    /** 条件为空 / 坏 JSON / 残缺叶子恒真：不隐藏、不加必填（配置错误不炸保存，OQ-65）。 */
    @Test
    void malformedConditionsFallBackToVisibleAndNotRequired() {
        Map<String, Object> data = Map.of("f010", 0);
        assertTrue(engine.matches(null, data, Map.of()), "无条件恒真");
        assertTrue(engine.matches("", data, Map.of()), "空串恒真");
        assertTrue(engine.matches("not-json", data, Map.of()), "坏 JSON 恒真");
        assertTrue(engine.matches("{\"all\":[{\"field\":\"f010\"}]}", data, Map.of()), "缺 op 恒真");
        assertTrue(
                engine.matches(
                        "{\"all\":[{\"field\":\"f010\",\"ctx\":\"device.type\",\"op\":\"eq\",\"value\":1}]}",
                        data,
                        Map.of()),
                "field 与 ctx 并存恒真");
        assertTrue(
                engine.matches("{\"all\":[{\"op\":\"eq\",\"value\":1}]}", data, Map.of()),
                "无操作数恒真");
        assertTrue(
                engine.matches(
                        "{\"all\":[{\"field\":\"f010\",\"op\":\"like\",\"value\":1}]}",
                        data,
                        Map.of()),
                "不认识的操作符恒真");
    }

    /** {all:[]} 恒真、{any:[]} 恒假；data / context 为 null 不抛错（操作数缺失 → 不成立）。 */
    @Test
    void emptyArraysAndNullInputs() {
        assertTrue(engine.matches("{\"all\":[]}", Map.of(), Map.of()), "空 all 恒真");
        assertTrue(!engine.matches("{\"any\":[]}", Map.of(), Map.of()), "空 any 恒假（无一项成立）");
        assertTrue(
                !engine.matches(
                        "{\"all\":[{\"field\":\"f010\",\"op\":\"eq\",\"value\":1}]}", null, null),
                "data 为 null：操作数缺失不成立（不抛错）");
        assertTrue(
                engine.matches(
                        "{\"all\":[{\"ctx\":\"device.type\",\"op\":\"empty\"}]}", Map.of(), null),
                "context 为 null：empty 直面缺失 → 成立");
    }

    /** ctx 点路径断链返回 null（不成立）；data 键缺失同样不成立。 */
    @Test
    void brokenCtxPathMeansMissing() {
        Map<String, Object> context = Map.of("device", Map.of("type", "ICD"));
        assertTrue(
                engine.matches(
                        "{\"any\":[{\"ctx\":\"device.type\",\"op\":\"eq\",\"value\":\"ICD\"}]}",
                        Map.of(),
                        context),
                "点路径可取值");
        assertTrue(
                !engine.matches(
                        "{\"any\":[{\"ctx\":\"device.mode\",\"op\":\"eq\",\"value\":\"ICD\"}]}",
                        Map.of(),
                        context),
                "断链路径 → 缺失 → 不成立");
        assertTrue(
                !engine.matches(
                        "{\"any\":[{\"ctx\":\"device\",\"op\":\"eq\",\"value\":\"ICD\"}]}",
                        Map.of(),
                        context),
                "取到的是对象而非标量：类型不匹配不成立");
    }

    /** hiddenFieldKeys：按声明序返回被隐藏字段；requiredNow：required 与 requiredWhen 任一成立即必填（显示前提）。 */
    @Test
    void hiddenKeysAndEffectiveRequired() {
        VersionFieldRow trigger = field("f010", null, null, false);
        VersionFieldRow deathDate =
                field(
                        "f216",
                        "{\"all\":[{\"field\":\"f010\",\"op\":\"eq\",\"value\":1}]}",
                        null,
                        false);
        VersionFieldRow deathDateRequiredWhen =
                field(
                        "f216",
                        "{\"all\":[{\"field\":\"f010\",\"op\":\"eq\",\"value\":1}]}",
                        "{\"all\":[{\"field\":\"f010\",\"op\":\"eq\",\"value\":1}]}",
                        false);
        VersionFieldRow alwaysRequired = field("f001", null, null, true);

        assertEquals(
                List.of("f216"),
                engine.hiddenFieldKeys(List.of(trigger, deathDate), Map.of("f010", 0), Map.of()));
        assertEquals(
                List.of(),
                engine.hiddenFieldKeys(List.of(trigger, deathDate), Map.of("f010", 1), Map.of()),
                "条件成立：不隐藏");

        assertTrue(!engine.requiredNow(deathDate, Map.of("f010", 0), Map.of()), "隐藏字段永不必填");
        assertTrue(
                engine.requiredNow(deathDateRequiredWhen, Map.of("f010", 1), Map.of()),
                "requiredWhen 成立 → 必填");
        assertTrue(
                !engine.requiredNow(deathDateRequiredWhen, Map.of("f010", 0), Map.of()),
                "requiredWhen 不成立且 required=false → 不必填");
        assertTrue(engine.requiredNow(alwaysRequired, Map.of(), Map.of()), "基础必填 required=true");
    }

    // ---------------------------------------------------------------- 辅助（全部虚构）

    private static VersionFieldRow field(
            String key, String visibleWhen, String requiredWhen, boolean required) {
        return new VersionFieldRow(
                1L,
                1L,
                "A_ANNUAL_FU",
                1,
                key,
                key + "_code",
                "字段「" + key + "」（示例）",
                "endpoint",
                "text",
                null,
                null,
                null,
                null,
                null,
                required,
                visibleWhen,
                requiredWhen,
                null,
                false,
                "json",
                null,
                0);
    }

    private static List<VersionFieldRow> caseFields(JsonNode item) {
        List<VersionFieldRow> fields = new ArrayList<>();
        for (JsonNode field : item.path("fields")) {
            fields.add(
                    new VersionFieldRow(
                            1L,
                            1L,
                            "A_ANNUAL_FU",
                            1,
                            field.path("key").asText(),
                            field.path("key").asText() + "_code",
                            "字段「" + field.path("key").asText() + "」（示例）",
                            field.path("categoryKey").asText(),
                            field.path("type").asText("text"),
                            null,
                            null,
                            null,
                            null,
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

    private static List<String> texts(JsonNode expected) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : expected) {
            values.add(item.asText());
        }
        return values;
    }

    private static List<FieldIssue> issues(JsonNode expected) {
        List<FieldIssue> values = new ArrayList<>();
        for (JsonNode item : expected) {
            values.add(
                    new FieldIssue(
                            item.path("key").asText(),
                            item.path("categoryKey").asText(),
                            item.path("rule").asText(),
                            item.path("message").asText()));
        }
        return values;
    }

    private static Map<String, Object> caseMap(JsonNode node) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
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
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        node.fields()
                .forEachRemaining(entry -> map.put(entry.getKey(), jsonValue(entry.getValue())));
        return map;
    }
}
