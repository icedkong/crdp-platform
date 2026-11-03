package com.crdp.form.rule.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.crdp.form.record.infra.mapper.VersionFieldRow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E11-T11 / A-PAT-021、C-PAT-018（口径 A-PAT-019）：表单引擎<b>已填 / 应填进度</b>的前后端一致性测试（验收原文：前后端结果一致）。
 *
 * <p>执行共享用例 {@code shared-cases/form-progress-cases.json}（components.md §10.5）：同一份文件由本测试与前端 {@code
 * packages/rules/src/form-progress.test.ts} 各执行一遍，每个分类的 key / label / filled / total 与输出顺序
 * 完全一致才通过。口径（form.md §1.3）：应填 = 显示中且有效必填（required || requiredWhen）且 storage=json 且 非派生的字段；已填 =
 * 其中值非空的字段（空值口径与条件 DSL 同，0 与 false 是已答）；条件不显示的字段不计入 分母（A-PAT-019）。分类按 fe_form_version.categories 的
 * sortNo 升序输出；字段引用未声明分类时按首现序兜底 追加（label=key），与 FormService.buildSchema 的防御口径一致。全部虚构数据。
 */
class FormProgressCaseTest {

    private final FormConditionEngine conditionEngine = new FormConditionEngine();
    private final FormProgressEngine progressEngine = new FormProgressEngine(conditionEngine);

    /** 共享用例是进度口径的唯一事实来源（components.md §10.5）：本测试与前端 form-progress.test 各执行一遍。 */
    @Test
    void executesProgressCasesIdenticallyWithFrontend() throws Exception {
        JsonNode file;
        try (InputStream in =
                getClass().getResourceAsStream("/shared-cases/form-progress-cases.json")) {
            file = new ObjectMapper().readTree(in);
        }
        int count = 0;
        for (JsonNode item : file.path("cases")) {
            String name = item.path("name").asText();
            List<VersionFieldRow> fields = caseFields(item);
            List<FormProgressEngine.Category> categories = caseCategories(item);
            Map<String, Object> data = caseMap(item.path("data"));
            Map<String, Object> context = caseMap(item.path("context"));

            List<FormProgressEngine.CategoryProgress> actual =
                    progressEngine.progress(fields, categories, data, context);
            List<FormProgressEngine.CategoryProgress> expected = new ArrayList<>();
            for (JsonNode node : item.path("expected")) {
                expected.add(
                        new FormProgressEngine.CategoryProgress(
                                node.path("key").asText(),
                                node.path("label").asText(),
                                node.path("filled").asInt(),
                                node.path("total").asInt()));
            }
            assertEquals(expected, actual, name + " → 分类进度（key / label / filled / total 与顺序）");
            count++;
        }
        assertEquals(8, count, "进度用例数（增删用例须两端同步）");
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
                            null,
                            null,
                            null,
                            field.path("required").asBoolean(false),
                            jsonOrNull(field.path("visibleWhen")),
                            jsonOrNull(field.path("requiredWhen")),
                            null,
                            false,
                            field.path("storage").asText("json"),
                            null,
                            0));
        }
        return fields;
    }

    /** 分类声明（fe_form_version.categories 同构 [{key,label,sortNo}]）；label 空 / 缺回落 key。 */
    private static List<FormProgressEngine.Category> caseCategories(JsonNode item) {
        List<FormProgressEngine.Category> categories = new ArrayList<>();
        for (JsonNode node : item.path("categories")) {
            if (!node.hasNonNull("key")) {
                continue;
            }
            String key = node.get("key").asText();
            String label = node.path("label").asText(null);
            categories.add(
                    new FormProgressEngine.Category(
                            key,
                            label == null || label.isBlank() ? key : label,
                            node.path("sortNo").asInt(0)));
        }
        return categories;
    }

    private static String jsonOrNull(JsonNode node) {
        return node.isObject() ? node.toString() : null;
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
}
