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
 * E21-T20 / C-FU-010、C-FU-011（Q02）：医院 C 预置对比标红规则的正反用例（后端执行器）。
 *
 * <p>用例文件 {@code backend/crdp-followup/src/test/resources/shared-cases/compare-cases-c.json} 收录
 * 随应用发布的医院 C 全量 12 条规则（followup V82，hospital-c-spec 第 5 章「异常对比规则（医院已确认，阈值可配置，Q02）」 默认值：起搏阈值升高 ＞ 50%
 * 且脉宽不同注明仅供参考、电极阻抗波动 ＞ 50%〔另配默认停用的绝对范围 200–2000 Ω〕、 感知明显下降 ＞ 50%、电池接近更换〔ERI 状态非正常或预计电池寿命 ≤ 1
 * 年〕）与每条规则的正例（命中）/ 反例（不命中）： 逐条规则按 ruleCodes 引用预置定义（不复写阈值），命中与 {@link
 * CompareEvaluatorSharedCasesTest} 同口径逐项断言 （ruleId / key / categoryKey / message / previousValue /
 * change / changeText）。同一份文件由前端 {@code packages/rules/src/form-compare-c.test.ts}
 * 再执行一遍（components.md §10.5）；文件中的规则定义与 V82 迁移的绑定由 crdp-server 的 {@code
 * CCompareRulePresetSqlTest#cSharedCaseFileMatchesPresetRows} 锁定——三者改一处即失败，保证用例永远跑在真实预置规则上。
 *
 * <p>用例口径（异常对比规则表逐行 + 求值边界）：阈值升高严格 ＞ 50%（恰 50% 不命中）、脉宽不同追加 「；脉宽不同，仅供参考」（components.md
 * §10.4；脉宽上一次为空无法判断不追加）、阻抗 ANY 双向（升高与降低各一正例）、 绝对范围 200–2000 Ω 严格边界（恰 200 / 2000
 * 不命中；规则默认停用但求值语义照测）、感知只看下降、 ERI 状态 VALUE_HIT 编码 1=接近ERI / 2=已达ERI(EOI)（0=正常与空值不命中）、预计电池寿命 ≤ 1 年含边界
 * 1、 没有上一次时对比类规则全部跳过（C 规格 §5「没有上一次时不比较」）而固定阈值规则照常命中， 以及 8 条默认启用规则齐上的 全表单正反例（命中按 sort_no
 * 排序输出）。只在表单内标红提示，不发通知（Q02）。
 */
class CCompareRuleCasesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int PRESET_RULE_COUNT = 12;

    private record ExpectedHit(
            String ruleId,
            String key,
            String categoryKey,
            String message,
            JsonNode previousValue,
            JsonNode change,
            String changeText) {}

    private record CCase(
            String name,
            List<EvalRule> rules,
            Map<String, Object> current,
            EvalPrevious previous,
            List<ExpectedHit> expectedHits) {}

    /** 全部用例通过：命中条数与逐项内容一致（多余 / 缺失 / 顺序都算失败）。 */
    @Test
    void cCasesPass() throws Exception {
        Suite suite = load();
        assertFalse(suite.cases().isEmpty(), "C 用例文件应有用例");
        for (CCase item : suite.cases()) {
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

    /** 「每条规则正反用例通过」的结构保证：12 条预置规则每条至少 1 个正例（命中）与 1 个反例（参与求值但不命中）。 */
    @Test
    void everyPresetRuleHasPositiveAndNegativeCase() throws Exception {
        Suite suite = load();
        assertEquals(PRESET_RULE_COUNT, suite.rulesByCode().size(), "C 预置规则应为 12 条");
        Map<String, Integer> positive = new LinkedHashMap<>();
        Map<String, Integer> negative = new LinkedHashMap<>();
        for (CCase item : suite.cases()) {
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
            List<CCase> cases) {}

    private static Suite load() throws Exception {
        Map<String, EvalRule> rulesByCode = new LinkedHashMap<>();
        Map<String, Map<Integer, Integer>> ordinals = new LinkedHashMap<>();
        List<CCase> cases = new ArrayList<>();
        try (InputStream input =
                CCompareRuleCasesTest.class.getResourceAsStream(
                        "/shared-cases/compare-cases-c.json")) {
            assert input != null : "C 用例文件缺失：shared-cases/compare-cases-c.json";
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
                        new CCase(
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
