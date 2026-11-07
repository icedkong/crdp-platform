package com.crdp.followup.compare.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.compare.domain.CompareEvaluator.EvalPrevious;
import com.crdp.followup.compare.domain.CompareEvaluator.EvalRule;
import com.crdp.followup.compare.domain.CompareEvaluator.Hit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * E17-T03 / A-FU-027：对比标红求值共享用例（后端执行器）。
 *
 * <p>同一份 backend/crdp-followup/src/test/resources/shared-cases/compare-cases.json（components.md
 * §10.5「对比（E17）沿用同一目录」）由本类与前端 packages/rules/src/form-compare.test.ts 各执行一遍， 命中逐条（ruleId / key /
 * categoryKey / message / previousValue / change / changeText）一致。 用例取自需求验收口径（hospital-a-spec 第 5 章
 * Q41：EF 56 → 45 下降 11 → 标红；上一次为空跳过； 返流加重 ≥ 2 级或达到中量 / 大量；新发事件日期晚于上一次随访……）与 tech-plan §11 的 C 侧类型
 * （变化率、取值命中）。数值比较按 double（两端 JSON 解析与 IEEE 运算一致）。
 */
class CompareEvaluatorSharedCasesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 一条期望命中（previousValue / change 数值按 double 比较，其余逐字）。 */
    private record ExpectedHit(
            String ruleId,
            String key,
            String categoryKey,
            String message,
            JsonNode previousValue,
            JsonNode change,
            String changeText) {}

    private record SharedCase(
            String name,
            List<Map<String, Object>> rules,
            Map<String, Object> current,
            EvalPrevious previous,
            Map<String, Map<Integer, Integer>> ordinals,
            List<ExpectedHit> expectedHits) {}

    @Test
    void sharedCasesPass() throws Exception {
        List<SharedCase> cases = load();
        assertTrue(cases.size() >= 20, "共享用例应覆盖七类规则与跳过口径（当前 " + cases.size() + " 条）");
        for (SharedCase item : cases) {
            List<Hit> hits =
                    CompareEvaluator.evaluate(
                            toRules(item.rules()),
                            item.current(),
                            item.previous(),
                            item.ordinals());
            assertEquals(
                    item.expectedHits().size(),
                    hits.size(),
                    item.name() + " → 命中条数（多余：" + hits + "）");
            for (int i = 0; i < item.expectedHits().size(); i++) {
                assertHit(item.name(), item.expectedHits().get(i), hits.get(i));
            }
        }
    }

    private static void assertHit(String caseName, ExpectedHit expected, Hit actual) {
        String at = caseName + " → " + expected.ruleId();
        assertEquals(expected.ruleId(), actual.ruleId(), at + " ruleId");
        assertEquals(expected.key(), actual.key(), at + " key");
        assertEquals(expected.categoryKey(), actual.categoryKey(), at + " categoryKey");
        assertEquals(expected.message(), actual.message(), at + " message");
        assertEquals(expected.changeText(), actual.changeText(), at + " changeText（前后端变化量文案逐字一致）");
        assertValueEquals(at + " previousValue", expected.previousValue(), actual.previousValue());
        Double change = actual.change();
        if (expected.change() == null || expected.change().isNull()) {
            assertEquals(null, change, at + " change 应为空");
        } else {
            assertEquals(
                    expected.change().asDouble(), change == null ? null : change, at + " change");
        }
    }

    /** 数值按 double 比较（JSON 56 与 Java 56.0 是同一个数）；null 与非数值按原样。 */
    private static void assertValueEquals(String at, JsonNode expected, Object actual) {
        if (expected == null || expected.isNull()) {
            assertEquals(null, actual, at);
        } else if (expected.isNumber()) {
            assertTrue(actual instanceof Number, at + "应为数值：" + actual);
            assertEquals(expected.asDouble(), ((Number) actual).doubleValue(), at);
        } else if (expected.isBoolean()) {
            assertEquals(expected.asBoolean(), actual, at);
        } else {
            assertEquals(expected.asText(), actual, at);
        }
    }

    // ---------------------------------------------------------------- 装载（字段一一对应）

    private static List<SharedCase> load() throws Exception {
        List<SharedCase> cases = new ArrayList<>();
        try (InputStream input =
                CompareEvaluatorSharedCasesTest.class.getResourceAsStream(
                        "/shared-cases/compare-cases.json")) {
            assert input != null : "共享用例文件缺失：shared-cases/compare-cases.json";
            for (JsonNode item : JSON.readTree(input).path("cases")) {
                List<Map<String, Object>> rules = new ArrayList<>();
                for (JsonNode rule : item.path("rules")) {
                    rules.add(JSON.convertValue(rule, LinkedHashMap.class));
                }
                Map<String, Map<Integer, Integer>> ordinals = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> field : item.path("ordinals").properties()) {
                    Map<Integer, Integer> codes = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonNode> code : field.getValue().properties()) {
                        codes.put(Integer.valueOf(code.getKey()), code.getValue().asInt());
                    }
                    ordinals.put(field.getKey(), codes);
                }
                JsonNode previous = item.path("previous");
                List<ExpectedHit> expected = new ArrayList<>();
                for (JsonNode hit : item.path("expectedHits")) {
                    expected.add(
                            new ExpectedHit(
                                    hit.path("ruleId").asText(),
                                    hit.path("key").asText(),
                                    hit.path("categoryKey").asText(),
                                    hit.path("message").asText(),
                                    hit.path("previousValue"),
                                    hit.path("change"),
                                    hit.path("changeText").isTextual()
                                            ? hit.path("changeText").asText()
                                            : null));
                }
                cases.add(
                        new SharedCase(
                                item.path("name").asText(),
                                rules,
                                JSON.convertValue(item.path("current"), LinkedHashMap.class),
                                previous.isMissingNode() || previous.isNull()
                                        ? null
                                        : new EvalPrevious(
                                                previous.path("kind").asText(null),
                                                previous.path("date").asText(null),
                                                JSON.convertValue(
                                                        previous.path("values"),
                                                        LinkedHashMap.class)),
                                ordinals,
                                expected));
            }
        }
        return cases;
    }

    private static List<EvalRule> toRules(List<Map<String, Object>> rows) {
        List<EvalRule> rules = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) row.getOrDefault("params", Map.of());
            rules.add(
                    new EvalRule(
                            (String) row.get("ruleCode"),
                            (String) row.get("categoryKey"),
                            (String) row.get("targetField"),
                            (String) row.get("ruleType"),
                            params,
                            (String) row.get("baselineField"),
                            (String) row.get("unit"),
                            (String) row.get("message")));
        }
        return rules;
    }
}
