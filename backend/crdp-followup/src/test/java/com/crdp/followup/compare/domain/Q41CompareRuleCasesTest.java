package com.crdp.followup.compare.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.compare.domain.CompareEvaluator.EvalPrevious;
import com.crdp.followup.compare.domain.CompareEvaluator.EvalRule;
import com.crdp.followup.compare.domain.CompareEvaluator.Hit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * E17-T06 / A-FU-027：医院 A Q41 预置对比标红规则的正反用例（后端执行器；tasks.csv 验收原文： 每条规则正反用例通过）。
 *
 * <p>用例文件 {@code backend/crdp-followup/src/test/resources/shared-cases/compare-cases-q41.json} 收录
 * 随应用发布的 Q41 全量 17 条规则（followup V67 两条 + V70 十五条，hospital-a-spec 第 5 章「录入时对比提示规则（医院已确认 Q41）」
 * 默认值）与每条规则的正例（命中）/ 反例（不命中）： 逐条规则按 ruleCodes 引用预置定义（不复写阈值），命中与 {@link
 * CompareEvaluatorSharedCasesTest} 同口径逐项断言 （ruleId / key / categoryKey / message / previousValue /
 * change / changeText）。同一份文件由前端 {@code packages/rules/src/form-compare-q41.test.ts}
 * 再执行一遍（components.md §10.5）； 文件中的规则定义与迁移 SQL 的绑定由 crdp-server 的 {@code
 * ACompareRulePresetSqlTest#q41SharedCaseFileMatchesPresetRows} 锁定 —— 三者改一处即失败， 保证用例永远跑在真实预置规则上。
 *
 * <p>用例口径（Q41 表逐行 + 求值边界）：EF 下降 ≥ 10 个百分点（含边界 10、第 1 年回落基线 f102、上一次为空跳过）、 LVDd 增加 ≥ 5 mm（含边界 5、回落基线
 * f103）、新发死亡（本次 = 是，第 1 年 BASELINE 也生效）、 新发再住院（上一次未填 / 日期晚于上一次随访；旧事重填不算）、 再血运重建与其余 4
 * 项终点事件（上一次为「无」即新发、本次为「无 / 未填」不标红）、 三个瓣膜返流加重（升高 ≥ 2 级或达到中量 / 大量；只升 1 级未达中量、返流减轻不标红）、
 * 新发心室血栓（由否变是；上一次已是、基线回落缺值不臆断新发）、 超出提示范围三项（EF ＜ 40、LVDd ＞ 60、LA ＞ 45 严格边界不命中、空值不命中）， 以及 17
 * 条齐上的全表单正例（命中按 sort_no 排序输出）与全表单反例（一条都不命中）。
 */
class Q41CompareRuleCasesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int PRESET_RULE_COUNT = 17;

    private record ExpectedHit(
            String ruleId,
            String key,
            String categoryKey,
            String message,
            JsonNode previousValue,
            JsonNode change,
            String changeText) {}

    private record Q41Case(
            String name,
            List<EvalRule> rules,
            Map<String, Object> current,
            EvalPrevious previous,
            List<ExpectedHit> expectedHits) {}

    /** 全部用例通过：命中条数与逐项内容一致（多余 / 缺失 / 顺序都算失败）。 */
    @Test
    void q41CasesPass() throws Exception {
        Suite suite = load();
        assertFalse(suite.cases().isEmpty(), "Q41 用例文件应有用例");
        for (Q41Case item : suite.cases()) {
            List<Hit> hits =
                    CompareEvaluator.evaluate(
                            item.rules(), item.current(), item.previous(), suite.ordinals());
            assertEquals(
                    item.expectedHits().size(),
                    hits.size(),
                    item.name() + " → 命中条数（实际：" + hits + "）");
            for (int i = 0; i < item.expectedHits().size(); i++) {
                assertHit(item.name(), item.expectedHits().get(i), hits.get(i));
            }
        }
    }

    /** 验收「每条规则正反用例通过」的结构保证：17 条预置规则每条至少 1 个正例（命中）与 1 个反例（参与求值但不命中）。 */
    @Test
    void everyPresetRuleHasPositiveAndNegativeCase() throws Exception {
        Suite suite = load();
        assertEquals(PRESET_RULE_COUNT, suite.rulesByCode().size(), "Q41 预置规则应为 17 条");
        Map<String, Integer> positive = new LinkedHashMap<>();
        Map<String, Integer> negative = new LinkedHashMap<>();
        for (Q41Case item : suite.cases()) {
            Set<String> hitIds = new HashSet<>();
            for (ExpectedHit hit : item.expectedHits()) {
                hitIds.add(hit.ruleId());
            }
            for (EvalRule rule : item.rules()) {
                String code = rule.ruleCode();
                if (hitIds.contains(code)) {
                    positive.merge(code, 1, Integer::sum);
                } else {
                    negative.merge(code, 1, Integer::sum);
                }
            }
        }
        for (String code : suite.rulesByCode().keySet()) {
            assertTrue(positive.getOrDefault(code, 0) >= 1, code + " 应至少有 1 个正例（命中）");
            assertTrue(negative.getOrDefault(code, 0) >= 1, code + " 应至少有 1 个反例（参与求值但不命中）");
        }
    }

    private static void assertHit(String caseName, ExpectedHit expected, Hit actual) {
        String at = caseName + " → " + expected.ruleId();
        assertEquals(expected.ruleId(), actual.ruleId(), at + " ruleId");
        assertEquals(expected.key(), actual.key(), at + " key");
        assertEquals(expected.categoryKey(), actual.categoryKey(), at + " categoryKey");
        assertEquals(expected.message(), actual.message(), at + " message");
        assertEquals(expected.changeText(), actual.changeText(), at + " changeText");
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

    // ---------------------------------------------------------------- 装载（ruleCodes 引用预置定义）

    private record Suite(
            Map<String, EvalRule> rulesByCode,
            Map<String, Map<Integer, Integer>> ordinals,
            List<Q41Case> cases) {}

    private static Suite load() throws Exception {
        Map<String, EvalRule> rulesByCode = new LinkedHashMap<>();
        Map<String, Map<Integer, Integer>> ordinals = new LinkedHashMap<>();
        List<Q41Case> cases = new ArrayList<>();
        try (InputStream input =
                Q41CompareRuleCasesTest.class.getResourceAsStream(
                        "/shared-cases/compare-cases-q41.json")) {
            assert input != null : "Q41 用例文件缺失：shared-cases/compare-cases-q41.json";
            JsonNode root = JSON.readTree(input);
            for (JsonNode rule : root.path("rules")) {
                EvalRule parsed = toRule(rule);
                rulesByCode.put(parsed.ruleCode(), parsed);
            }
            for (Map.Entry<String, JsonNode> field : root.path("ordinals").properties()) {
                Map<Integer, Integer> codes = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> code : field.getValue().properties()) {
                    codes.put(Integer.valueOf(code.getKey()), code.getValue().asInt());
                }
                ordinals.put(field.getKey(), codes);
            }
            for (JsonNode item : root.path("cases")) {
                List<EvalRule> caseRules = new ArrayList<>();
                for (JsonNode ref : item.path("ruleCodes")) {
                    EvalRule rule = rulesByCode.get(ref.asText());
                    assertNotNull(rule, "用例引用了未预置的规则：" + ref.asText());
                    caseRules.add(rule);
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
                        new Q41Case(
                                item.path("name").asText(),
                                caseRules,
                                JSON.convertValue(item.path("current"), LinkedHashMap.class),
                                previous.isMissingNode() || previous.isNull()
                                        ? null
                                        : new EvalPrevious(
                                                previous.path("kind").asText(null),
                                                previous.path("date").asText(null),
                                                JSON.convertValue(
                                                        previous.path("values"),
                                                        LinkedHashMap.class)),
                                expected));
            }
        }
        return new Suite(rulesByCode, ordinals, cases);
    }

    @SuppressWarnings("unchecked")
    private static EvalRule toRule(JsonNode rule) {
        return new EvalRule(
                rule.path("ruleCode").asText(),
                rule.path("categoryKey").asText(),
                rule.path("targetField").asText(),
                rule.path("ruleType").asText(),
                JSON.convertValue(rule.path("params"), LinkedHashMap.class),
                rule.path("baselineField").isTextual() ? rule.path("baselineField").asText() : null,
                rule.path("unit").isTextual() ? rule.path("unit").asText() : null,
                rule.path("message").asText());
    }
}
