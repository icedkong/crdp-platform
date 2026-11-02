package com.crdp.form.rule.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.form.record.infra.mapper.VersionFieldRow;
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
 * E11-T05 / A-FLD-005、C-FLD-006：内置派生函数库单元测试（DAYS_INCLUSIVE / MINUTES_BETWEEN / HOURS_BETWEEN_1DP /
 * BMI / AGE_YEARS / SUM / YEARS_SINCE / DURATION_YM / CHANGE_PCT，tech-plan §6「派生字段」）。
 *
 * <p>验收原文：BMI、年龄等派生字段两端一致。「一致」由共享用例锁定——同一份 shared-cases/form-derived-cases.json 由本测试与前端
 * packages/rules/src/form-derived.test.ts 各执行一遍，每个派生字段的 value / status / message 逐条完全一致
 * （components.md §10.5）。写入闸门口径（A-FLD-005）：来源齐全以计算值为准、来源缺失保留手工值、结果无效不允许手工值， FormService 侧集成见 {@code
 * FormServiceTest}。全部虚构数据。
 */
class FormDerivedEngineTest {

    private final FormDerivedEngine engine = new FormDerivedEngine();

    // ---------------------------------------------------------------- 共享用例（前后端一致）

    /**
     * 共享用例文件是前后端派生一致的唯一事实来源（components.md §10.5）：本测试与前端 form-derived.test 读同一份 JSON，value（null =
     * 留空）/ status（ok / missing / invalid）/ message（留空原因）逐条一致，结果顺序 = 字段列表顺序；未知函数 / 坏定义 /
     * 成环字段不产出（防御跳过）。today 固定日期保证两端确定性。
     */
    @Test
    void executesSharedCasesIdenticallyWithFrontend() throws Exception {
        JsonNode file;
        try (InputStream in =
                getClass().getResourceAsStream("/shared-cases/form-derived-cases.json")) {
            file = new ObjectMapper().readTree(in);
        }
        List<String> names = new ArrayList<>();
        for (JsonNode item : file.path("cases")) {
            names.add(item.path("name").asText());
            Map<String, FormDerivedEngine.DerivedOutcome> outcomes =
                    engine.recalculate(caseFields(item), caseData(item), caseToday(item));
            List<String> expectedKeys = new ArrayList<>();
            for (JsonNode expected : item.path("expected")) {
                expectedKeys.add(expected.path("key").asText());
            }
            assertEquals(
                    expectedKeys,
                    List.copyOf(outcomes.keySet()),
                    item.path("name").asText() + " → 结果键与顺序（只含派生字段）");
            for (JsonNode expected : item.path("expected")) {
                FormDerivedEngine.DerivedOutcome outcome =
                        outcomes.get(expected.path("key").asText());
                assertNotNull(outcome, item.path("name").asText());
                assertValue(expected.path("value"), outcome.value(), item.path("name").asText());
                assertEquals(
                        expected.path("status").asText(),
                        outcome.status().code(),
                        item.path("name").asText() + " → status");
                if (expected.path("message").isNull()) {
                    assertNull(outcome.message(), item.path("name").asText() + " → message 为空");
                } else {
                    assertEquals(
                            expected.path("message").asText(),
                            outcome.message(),
                            item.path("name").asText() + " → message 逐字一致");
                }
            }
        }
        assertEquals(33, names.size(), "共享用例数（增删用例须两端同步）");
    }

    // ---------------------------------------------------------------- 写入闸门口径（A-FLD-005）

    /** 来源齐全 → 计算值覆盖（含残留的旧手工值）：「来源齐全后以计算值为准」。 */
    @Test
    void computedValueOverwritesStaleManualValueWhenSourcesComplete() {
        List<VersionFieldRow> fields =
                List.of(
                        number("f011", "身高"),
                        number("f013", "体重"),
                        derived(
                                "f031",
                                "BMI",
                                "basic_info",
                                "{\"fn\":\"BMI\",\"args\":[\"f013\",\"f011\"]}"));
        FormDerivedEngine.DerivedWrite write =
                engine.applyToWrite(
                        fields, new LinkedHashMap<>(Map.of("f011", 175, "f013", 70, "f031", 30)));
        assertTrue(write.errors().isEmpty(), "来源齐全不报错");
        assertEquals(22.9, ((Number) write.data().get("f031")).doubleValue(), 1e-9, "以计算值为准");
    }

    /** 来源缺失 → 保留手工值（「只有来源字段缺失时允许手工录入」，多见于历史数据）。 */
    @Test
    void manualValueIsKeptWhenSourcesMissing() {
        List<VersionFieldRow> fields =
                List.of(
                        number("f011", "身高"),
                        number("f013", "体重"),
                        derived(
                                "f031",
                                "BMI",
                                "basic_info",
                                "{\"fn\":\"BMI\",\"args\":[\"f013\",\"f011\"]}"));
        FormDerivedEngine.DerivedWrite write =
                engine.applyToWrite(fields, new LinkedHashMap<>(Map.of("f013", 70, "f031", 21.4)));
        assertTrue(write.errors().isEmpty(), "来源缺失允许手工值");
        assertEquals(21.4, ((Number) write.data().get("f031")).doubleValue(), 1e-9, "手工值保留");
    }

    /** 结果无效（如出院早于手术）→ 不允许手工录入：带值 42200 口径 errors（rule=derived，message 与提示同源）。 */
    @Test
    void invalidResultRejectsManualValue() {
        List<VersionFieldRow> fields =
                List.of(
                        date("f020", "手术日期"),
                        date("f021", "出院日期"),
                        derived(
                                "f030",
                                "术后住院天数",
                                "surgery",
                                "{\"fn\":\"DAYS_INCLUSIVE\",\"args\":[\"f020\",\"f021\"]}"));
        FormDerivedEngine.DerivedWrite rejected =
                engine.applyToWrite(
                        fields,
                        new LinkedHashMap<>(
                                Map.of("f020", "2026-03-01", "f021", "2026-02-28", "f030", 3)));
        assertEquals(
                List.of(new FieldIssue("f030", "surgery", "derived", "出院日期早于手术日期")),
                rejected.errors(),
                "结果无效不允许手工值（应先修正来源字段）");

        // 同样来源无效但不带手工值：留空放行（提示在字段旁展示）
        FormDerivedEngine.DerivedWrite empty =
                engine.applyToWrite(
                        fields,
                        new LinkedHashMap<>(Map.of("f020", "2026-03-01", "f021", "2026-02-28")));
        assertTrue(empty.errors().isEmpty(), "无效留空放行");
        assertNull(empty.data().get("f030"));
    }

    // ---------------------------------------------------------------- 口径与防御

    /** today 缺省 = 北京时间当天（AGE_YEARS 无参考字段时按今天计周岁，C-FLD-006 / Q13）。 */
    @Test
    void defaultsReferenceDateToToday() {
        LocalDate today = LocalDate.now(FormDerivedEngine.ZONE);
        List<VersionFieldRow> fields =
                List.of(
                        date("f003", "出生日期"),
                        derived(
                                "f103",
                                "年龄",
                                "followup",
                                "{\"fn\":\"AGE_YEARS\",\"args\":[\"f003\"]}"));
        Map<String, FormDerivedEngine.DerivedOutcome> outcomes =
                engine.recalculate(fields, Map.of("f003", today.minusYears(45).toString()));
        assertEquals(45L, outcomes.get("f103").value(), "今天生日已到 → 整 45 周岁");

        Map<String, FormDerivedEngine.DerivedOutcome> notYet =
                engine.recalculate(
                        fields, Map.of("f003", today.minusYears(45).plusDays(1).toString()));
        assertEquals(44L, notYet.get("f103").value(), "明天才生日 → 44 周岁");
    }

    /** 防御：无派生字段 / data 为 null → 空结果；类型不匹配的来源按缺失计（类型闸门上游已拦，这里防御跳过）。 */
    @Test
    void defendsAgainstEmptyAndMalformedInput() {
        assertTrue(
                engine.recalculate(List.of(number("f011", "身高")), null).isEmpty(), "无派生字段 → 空结果");
        Map<String, FormDerivedEngine.DerivedOutcome> malformed =
                engine.recalculate(
                        List.of(
                                number("f011", "身高"),
                                number("f013", "体重"),
                                derived(
                                        "f031",
                                        "BMI",
                                        "basic_info",
                                        "{\"fn\":\"BMI\",\"args\":[\"f013\",\"f011\"]}")),
                        Map.of("f011", 175, "f013", "七十公斤"));
        assertEquals(FormDerivedEngine.DerivedStatus.MISSING, malformed.get("f031").status());
        assertEquals("缺少体重", malformed.get("f031").message(), "不可解析来源按缺失计");
    }

    /** 派生依赖派生且被依赖方来源缺失：取其实际生效值（手工值），与存储口径一致。 */
    @Test
    void derivedDependencyUsesEffectiveValue() {
        List<VersionFieldRow> fields =
                List.of(
                        number("f110", "动脉桥数量"),
                        number("f111", "静脉桥数量"),
                        derived(
                                "f112",
                                "桥血管数量",
                                "surgery",
                                "{\"fn\":\"SUM\",\"args\":[\"f110\",\"f111\"]}"),
                        derived(
                                "f122",
                                "变化率",
                                "program",
                                "{\"fn\":\"CHANGE_PCT\",\"args\":[\"f120\",\"f112\"]}"),
                        number("f120", "本次值"));
        // f110/f111 缺 → f112 手工值 8 生效；f122 = (10 - 8) / 8 × 100 = 25.0
        Map<String, FormDerivedEngine.DerivedOutcome> outcomes =
                engine.recalculate(fields, Map.of("f120", 10, "f112", 8));
        assertEquals(FormDerivedEngine.DerivedStatus.MISSING, outcomes.get("f112").status());
        assertEquals(25.0, ((Number) outcomes.get("f122").value()).doubleValue(), 1e-9);
    }

    // ---------------------------------------------------------------- 共享用例装载

    private static List<VersionFieldRow> caseFields(JsonNode item) {
        List<VersionFieldRow> fields = new ArrayList<>();
        for (JsonNode field : item.path("fields")) {
            String derived = null;
            JsonNode def = field.path("derived");
            if (def.isObject()) {
                derived = def.toString();
            }
            fields.add(
                    new VersionFieldRow(
                            1L,
                            1L,
                            "A_BASELINE",
                            1,
                            field.path("key").asText(),
                            field.path("key").asText(),
                            field.path("label").asText(),
                            field.path("categoryKey").asText(),
                            field.path("type").asText(),
                            null,
                            null,
                            null,
                            null,
                            null,
                            false,
                            null,
                            null,
                            derived,
                            false,
                            "json",
                            null,
                            0));
        }
        return fields;
    }

    private static Map<String, Object> caseData(JsonNode item) {
        Map<String, Object> data = new LinkedHashMap<>();
        item.path("data")
                .fields()
                .forEachRemaining(entry -> data.put(entry.getKey(), unpack(entry.getValue())));
        return data;
    }

    private static LocalDate caseToday(JsonNode item) {
        JsonNode today = item.path("today");
        return today.isTextual() ? LocalDate.parse(today.asText()) : null;
    }

    /** JSON 树值 → Java 值（数值 / 文本 / null，与 fe_record.data 反序列化口径一致）。 */
    private static Object unpack(JsonNode node) {
        if (node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        return node.asText();
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

    // ---------------------------------------------------------------- 字段构造（虚构示例）

    private static VersionFieldRow number(String key, String label) {
        return plain(key, label, "number");
    }

    private static VersionFieldRow date(String key, String label) {
        return plain(key, label, "date");
    }

    private static VersionFieldRow plain(String key, String label, String type) {
        return new VersionFieldRow(
                1L,
                1L,
                "A_BASELINE",
                1,
                key,
                key,
                label,
                "basic_info",
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

    private static VersionFieldRow derived(
            String key, String label, String categoryKey, String derivedDef) {
        return new VersionFieldRow(
                1L,
                1L,
                "A_BASELINE",
                1,
                key,
                key,
                label,
                categoryKey,
                "derived",
                null,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                derivedDef,
                false,
                "json",
                null,
                0);
    }
}
