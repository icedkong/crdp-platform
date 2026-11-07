package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.crdp.followup.compare.domain.CompareRuleType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * E17-T05 核对（单元）：对比标红规则预置迁移（followup V67 两条 + V70 全量补齐）与医院 A 规格 第 5 章「录入时对比提示规则（医院已确认
 * Q41）」逐项一致（A-FU-027；tasks.csv 验收原文：Q41 规则全部可开关）。
 *
 * <p>基准是 docs/requirements/hospital-a-spec.md（§5 Q41 表 9 行：射血分数下降、新发死亡、新发再住院、 新发再血运重建、其他新发事件 5
 * 项、左室扩大、瓣膜返流加重、新发心室血栓、超出提示范围 3 项； 附录 A Q41 行 1400），比对对象是随应用一起发布的迁移脚本
 * db/tenant/followup/V67__fu_compare_rule.sql（E17-T01 已落 ef_drop / lvdd_rise 两条）与
 * V70__hospital_a_compare_rule_preset.sql （本任务补齐其余 15 条）：不依赖数据库，CI 与本地必跑；落库后的开关链路由 {@link
 * CompareRulesIT}（门控 CRDP_IT_MYSQL_PORT）经 HTTP 再核一遍。
 *
 * <p>覆盖：
 *
 * <ul>
 *   <li>Q41 表 9 行全部有对应规则行（表内一行拆成多条单字段规则的：其他新发事件 5 项、瓣膜返流加重 3 个瓣膜、 超出提示范围 3 项），共 17 条，(form_code,
 *       rule_code) 唯一（uk 约束口径）；
 *   <li>每条的 rule_type / params 默认值与 Q41 一致（EF 下降 ≥ 10 个百分点、LVDd 增加 ≥ 5 mm、 返流加重 ≥ 2 级或达到中量 / 大量、EF
 *       ＜ 40%、LVDd ＞ 60 mm、LA ＞ 45 mm），params 能通过 {@link CompareRuleType#validateParams} 键集校验—— 这是
 *       PUT 开关 + 调阈值的入参口径，全部通过 = 每条规则都可用统一入口开关（「Q41 规则全部可开关」）；
 *   <li>字段引用与 V38 字段字典（E23-T01）交叉核对：target / dateField / baseline 均存在、分类锚点一致、 类型与选项集匹配（序数升级用
 *       a_mr_reflux 且 reachCodes ∈ 中量 / 大量、由否变是用 a_yesno、数值规则字段为 number 且 单位与阈值口径一致）；
 *   <li>默认全部启用：两份迁移的 INSERT 不写 status 列（表默认 ENABLED），停用只能由租户管理员操作（E17-T01 的 PUT）。
 * </ul>
 */
class ACompareRulePresetSqlTest {

    private static final String RULE_TABLE = "db/tenant/followup/V67__fu_compare_rule.sql";

    private static final String PRESET =
            "db/tenant/followup/V70__hospital_a_compare_rule_preset.sql";

    private static final String FIELD_DICT = "db/tenant/followup/V38__hospital_a_field_dict.sql";

    /** V67 + V70 的 fu_compare_rule INSERT 列序（两份迁移一致；不含 status → 默认 ENABLED）。 */
    private static final String[] RULE_COLUMNS = {
        "form_code",
        "rule_code",
        "name",
        "category_key",
        "target_field",
        "rule_type",
        "params",
        "baseline_form_code",
        "baseline_field",
        "message",
        "sort_no"
    };

    private static Map<String, Map<String, Object>> rulesByCode;
    private static Map<String, FieldRow> fields;
    private static Map<String, OptionRow> options;

    /** V38 fe_field 行（form_code + field_key 唯一）。 */
    private record FieldRow(
            String form, String key, String category, String type, String unit, String optionSet) {}

    /** V38 fe_option 行（set_code + option_code 唯一）。 */
    private record OptionRow(String set, String code, String label, String ordinal) {}

    @BeforeAll
    static void parse() {
        rulesByCode = new LinkedHashMap<>();
        for (String path : List.of(RULE_TABLE, PRESET)) {
            for (List<Object> row : insertRows(read(path), "fu_compare_rule")) {
                Map<String, Object> rule = new LinkedHashMap<>();
                for (int i = 0; i < RULE_COLUMNS.length; i++) {
                    rule.put(RULE_COLUMNS[i], row.get(i));
                }
                String code = (String) rule.get("rule_code");
                assertTrue(
                        !rulesByCode.containsKey(code),
                        "(form_code, rule_code) 唯一，重复预置：" + code + "（" + path + "）");
                rulesByCode.put(code, rule);
            }
        }
        fields = fieldRows(read(FIELD_DICT));
        options = optionRows(read(FIELD_DICT));
    }

    // ---------------------------------------------------------------- Q41 全量与默认值

    /**
     * Q41 表 9 行 → 17 条规则行（其他新发事件按字段拆 5 条、瓣膜返流加重 3 个瓣膜各一条、超出提示范围 3 项各一条）； rule_code → (name,
     * category, target, rule_type, params, baseline, message, sort)。
     */
    @Test
    void presetMatchesQ41SpecTable() {
        Map<String, Object[]> spec = new LinkedHashMap<>();
        // —— 射血分数下降（V67，E17-T01）——
        spec.put(
                "ef_drop",
                new Object[] {
                    "射血分数下降",
                    "fu_echo",
                    "f248",
                    "DIFF",
                    Map.of("direction", "DROP", "threshold", 10),
                    "A_BASELINE",
                    "f102",
                    "EF 比上次（或出院前）下降 ≥ 10 个百分点",
                    10
                });
        // —— 新发死亡 ——
        spec.put(
                "new_death",
                new Object[] {
                    "新发死亡",
                    "fu_contact",
                    "f215",
                    "FLIP_TO_YES",
                    Map.of(),
                    null,
                    null,
                    "本次「是否死亡」= 是",
                    15
                });
        // —— 新发再住院 ——
        spec.put(
                "new_readmission",
                new Object[] {
                    "新发再住院",
                    "fu_event",
                    "f223",
                    "NEW_EVENT",
                    Map.of("dateField", "f224"),
                    null,
                    null,
                    "新发再住院：本次填写了再住院类型，且日期晚于上一次随访（或上一次未填）",
                    25
                });
        // —— 新发再血运重建 ——
        spec.put(
                "new_revascularization",
                new Object[] {
                    "新发再血运重建",
                    "fu_event",
                    "f225",
                    "NEW_EVENT",
                    Map.of("dateField", "f226"),
                    null,
                    null,
                    "新发再血运重建：本次再次血运重建 ≠ 无，且日期晚于上一次随访（或上一次为无）",
                    30
                });
        // —— 其他新发事件（Q41 一行 5 个字段，各成一条：#219 / #221 / #227 / #230 / #233）——
        spec.put(
                "new_postop_mi",
                new Object[] {
                    "新发术后心肌梗死",
                    "fu_event",
                    "f219",
                    "NEW_EVENT",
                    Map.of("dateField", "f220"),
                    null,
                    null,
                    "新发术后心肌梗死：本次为是，且日期晚于上一次随访（或上一次为否）",
                    35
                });
        spec.put(
                "new_postop_cvd",
                new Object[] {
                    "新发术后脑血管疾病",
                    "fu_event",
                    "f221",
                    "NEW_EVENT",
                    Map.of("dateField", "f222"),
                    null,
                    null,
                    "新发术后脑血管疾病：本次非无，且日期晚于上一次随访（或上一次为无）",
                    36
                });
        spec.put(
                "new_revalve",
                new Object[] {
                    "新发再次瓣膜手术",
                    "fu_event",
                    "f227",
                    "NEW_EVENT",
                    Map.of("dateField", "f229"),
                    null,
                    null,
                    "新发再次瓣膜手术：本次非无，且日期晚于上一次随访（或上一次为无）",
                    37
                });
        spec.put(
                "new_arr_reop",
                new Object[] {
                    "新发因心律失常再次手术",
                    "fu_event",
                    "f230",
                    "NEW_EVENT",
                    Map.of("dateField", "f231"),
                    null,
                    null,
                    "新发因心律失常再次手术：本次为是，且日期晚于上一次随访（或上一次为否）",
                    38
                });
        spec.put(
                "new_postop_bleed",
                new Object[] {
                    "新发术后出血",
                    "fu_event",
                    "f233",
                    "NEW_EVENT",
                    Map.of("dateField", "f234"),
                    null,
                    null,
                    "新发术后出血：本次非无，且日期晚于上一次随访（或上一次为无）",
                    39
                });
        // —— 左室扩大（V67，E17-T01）——
        spec.put(
                "lvdd_rise",
                new Object[] {
                    "左室扩大",
                    "fu_echo",
                    "f249",
                    "DIFF",
                    Map.of("direction", "RISE", "threshold", 5),
                    "A_BASELINE",
                    "f103",
                    "LVDd 比上次（或出院前）增加 ≥ 5 mm",
                    20
                });
        // —— 瓣膜返流加重（Q41 一行 3 个瓣膜：#239 / #241 / #243；达到中量 / 大量即标红）——
        for (Object[] valve :
                new Object[][] {
                    {"mr_reflux_worsen", "二尖瓣返流加重", "f239", 50},
                    {"ar_reflux_worsen", "主动脉瓣返流加重", "f241", 51},
                    {"tr_reflux_worsen", "三尖瓣返流加重", "f243", 52}
                }) {
            spec.put(
                    (String) valve[0],
                    new Object[] {
                        valve[1],
                        "fu_echo",
                        valve[2],
                        "ORDINAL_RISE",
                        Map.of("grades", 2, "reachCodes", List.of("3", "4")),
                        null,
                        null,
                        "返流程度比上次升高 ≥ 2 级，或达到中量 / 大量",
                        valve[3]
                    });
        }
        // —— 新发心室血栓（基线回落 #113）——
        spec.put(
                "new_thrombus",
                new Object[] {
                    "新发心室血栓",
                    "fu_echo",
                    "f258",
                    "FLIP_TO_YES",
                    Map.of(),
                    "A_BASELINE",
                    "f113",
                    "心室血栓由否变为是（对比上一次随访或出院前）",
                    60
                });
        // —— 超出提示范围（Q41 默认三项：EF ＜ 40%、LVDd ＞ 60 mm、LA ＞ 45 mm）——
        spec.put(
                "ef_below_40",
                new Object[] {
                    "EF 低于 40",
                    "fu_echo",
                    "f248",
                    "OUT_OF_RANGE",
                    Map.of("op", "LT", "value", 40),
                    null,
                    null,
                    "EF ＜ 40%（超出提示范围）",
                    70
                });
        spec.put(
                "lvdd_above_60",
                new Object[] {
                    "LVDd 大于 60",
                    "fu_echo",
                    "f249",
                    "OUT_OF_RANGE",
                    Map.of("op", "GT", "value", 60),
                    null,
                    null,
                    "LVDd ＞ 60 mm（超出提示范围）",
                    71
                });
        spec.put(
                "la_above_45",
                new Object[] {
                    "LA 大于 45",
                    "fu_echo",
                    "f245",
                    "OUT_OF_RANGE",
                    Map.of("op", "GT", "value", 45),
                    null,
                    null,
                    "LA ＞ 45 mm（超出提示范围）",
                    72
                });

        assertEquals(spec.keySet(), rulesByCode.keySet(), "Q41 表 9 行拆为 17 条规则，无缺失无多余");
        for (Map.Entry<String, Object[]> expected : spec.entrySet()) {
            Map<String, Object> row = rulesByCode.get(expected.getKey());
            assertNotNull(row, "规则应已预置：" + expected.getKey());
            Object[] want = expected.getValue();
            assertEquals("A_ANNUAL_FU", row.get("form_code"), expected.getKey() + " 表单");
            assertEquals(want[0], row.get("name"), expected.getKey() + " 规则名");
            assertEquals(want[1], row.get("category_key"), expected.getKey() + " 分类锚点");
            assertEquals(want[2], row.get("target_field"), expected.getKey() + " 对比字段");
            assertEquals(want[3], row.get("rule_type"), expected.getKey() + " 规则类型");
            assertEquals(want[4], row.get("params"), expected.getKey() + " 默认阈值参数");
            assertEquals(want[5], row.get("baseline_form_code"), expected.getKey() + " 基线表单");
            assertEquals(want[6], row.get("baseline_field"), expected.getKey() + " 基线回落字段");
            assertEquals(want[7], row.get("message"), expected.getKey() + " 标红提示文案");
            assertEquals(
                    ((Number) want[8]).intValue(),
                    number(row.get("sort_no")),
                    expected.getKey() + " 排序");
        }
    }

    /** 全部 17 条默认启用：迁移 INSERT 不写 status 列（表默认 ENABLED），开关只能由租户管理员 PUT。 */
    @Test
    void presetRowsDefaultEnabled() {
        for (String path : List.of(RULE_TABLE, PRESET)) {
            String sql = read(path);
            String insert = statementOf(sql, "fu_compare_rule", 0);
            String columns =
                    insert.substring(
                            insert.indexOf('(') + 1, matchingParen(insert, insert.indexOf('(')));
            assertEquals(
                    List.of(RULE_COLUMNS),
                    splitTopLevel(columns).stream().map(String::strip).collect(Collectors.toList()),
                    path + " INSERT 列序不含 status（默认 ENABLED）");
        }
    }

    /**
     * 「Q41 规则全部可开关」的入参口径：每条预置 params 能通过 {@link CompareRuleType#validateParams} 键集校验 —— PUT
     * {enabled, params} 对每条规则都成立（E17-T01 的统一开关入口）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void presetParamsPassRuleTypeValidation() {
        for (Map.Entry<String, Map<String, Object>> entry : rulesByCode.entrySet()) {
            Map<String, Object> row = entry.getValue();
            CompareRuleType type =
                    CompareRuleType.fromCode((String) row.get("rule_type"))
                            .orElseThrow(
                                    () ->
                                            new AssertionError(
                                                    entry.getKey()
                                                            + " 未知规则类型 "
                                                            + row.get("rule_type")));
            Map<String, Object> params =
                    type.validateParams((Map<String, Object>) row.get("params"));
            assertEquals(row.get("params"), params, entry.getKey() + " 校验应原样通过（键集与阈值合法）");
        }
    }

    /**
     * E17-T06（Q41 每条规则正反用例）的绑定核对：共享用例文件 {@code
     * crdp-followup/src/test/resources/shared-cases/compare-cases-q41.json} 里的 17 条规则定义与 本测试解析的 V67
     * + V70 预置行逐项一致（ruleCode / categoryKey / targetField / ruleType / params / baselineField /
     * message）。 用例文件在 crdp-followup 测试资源（跨模块，走仓库文件定位，同 {@link AFieldDictAppendix#locateSpec()}
     * 的向上找根）， 由后端 {@code Q41CompareRuleCasesTest} 与前端 {@code form-compare-q41.test.ts}
     * 各执行一遍；本核对保证两端正反用例 跑的是真实预置规则——迁移调阈值不同步用例文件即失败。
     */
    @Test
    void q41SharedCaseFileMatchesPresetRows() throws IOException {
        JsonNode rules =
                new ObjectMapper()
                        .readTree(Files.newInputStream(locateQ41CaseFile()))
                        .path("rules");
        assertTrue(rules.isArray() && !rules.isEmpty(), "Q41 用例文件应带 rules 数组");
        Map<String, JsonNode> caseRules = new LinkedHashMap<>();
        for (JsonNode rule : rules) {
            String code = rule.path("ruleCode").asText();
            assertTrue(!caseRules.containsKey(code), "Q41 用例文件规则重复：" + code);
            caseRules.put(code, rule);
        }
        assertEquals(
                rulesByCode.keySet(),
                caseRules.keySet(),
                "Q41 用例文件的规则集应与 V67 + V70 预置完全一致（缺失即用例没跟上预置）");
        for (Map.Entry<String, JsonNode> entry : caseRules.entrySet()) {
            Map<String, Object> row = rulesByCode.get(entry.getKey());
            JsonNode rule = entry.getValue();
            String at = entry.getKey();
            assertEquals(row.get("category_key"), rule.path("categoryKey").asText(), at + " 分类锚点");
            assertEquals(row.get("target_field"), rule.path("targetField").asText(), at + " 对比字段");
            assertEquals(row.get("rule_type"), rule.path("ruleType").asText(), at + " 规则类型");
            assertEquals(
                    row.get("params"),
                    new ObjectMapper().convertValue(rule.path("params"), LinkedHashMap.class),
                    at + " 阈值参数（调阈值须同步用例文件）");
            assertEquals(
                    row.get("baseline_field") == null ? null : row.get("baseline_field").toString(),
                    rule.path("baselineField").isTextual()
                            ? rule.path("baselineField").asText()
                            : null,
                    at + " 基线回落字段");
            assertEquals(row.get("message"), rule.path("message").asText(), at + " 标红提示文案");
        }
    }

    /** 从模块目录向上找到 Q41 共享用例文件（crdp-followup 测试资源，跨模块读仓库文件）。 */
    private static Path locateQ41CaseFile() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate =
                    dir.resolve(
                            "backend/crdp-followup/src/test/resources/shared-cases/compare-cases-q41.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "未找到 backend/crdp-followup/src/test/resources/shared-cases/compare-cases-q41.json"
                        + "（应在仓库内运行）");
    }

    // ---------------------------------------------------------------- 与 V38 字段字典交叉核对

    /** 对比 / 基线 / 日期字段都在 V38 字段字典中，且分类锚点一致（标红挂靠分组用）。 */
    @Test
    void referencedFieldsExistInFieldDictWithSameCategory() {
        for (Map.Entry<String, Map<String, Object>> entry : rulesByCode.entrySet()) {
            Map<String, Object> row = entry.getValue();
            String target = (String) row.get("target_field");
            FieldRow field = fields.get("A_ANNUAL_FU|" + target);
            assertNotNull(field, entry.getKey() + " 对比字段应在 V38：" + target);
            assertEquals(
                    row.get("category_key"),
                    field.category(),
                    entry.getKey() + " 分类锚点应与字段字典一致（" + target + "）");
            Object dateField = ((Map<?, ?>) row.get("params")).get("dateField");
            if (dateField != null) {
                FieldRow date = fields.get("A_ANNUAL_FU|" + dateField);
                assertNotNull(date, entry.getKey() + " 事件日期字段应在 V38：" + dateField);
                assertEquals("date", date.type(), entry.getKey() + " 日期字段类型（" + dateField + "）");
            }
            if (row.get("baseline_field") != null) {
                String baseline = (String) row.get("baseline_field");
                FieldRow base = fields.get(row.get("baseline_form_code") + "|" + baseline);
                assertNotNull(base, entry.getKey() + " 基线回落字段应在 V38：" + baseline);
            }
        }
    }

    /** 数值类规则（DIFF / OUT_OF_RANGE）字段为 number，单位与阈值口径一致（%→个百分点、mm）。 */
    @Test
    void numericRulesUseNumberFieldsWithUnits() {
        Map<String, String> expectedUnits = Map.of("f248", "%", "f249", "mm", "f245", "mm");
        for (String code :
                List.of("ef_drop", "lvdd_rise", "ef_below_40", "lvdd_above_60", "la_above_45")) {
            FieldRow field = fields.get("A_ANNUAL_FU|" + rulesByCode.get(code).get("target_field"));
            assertEquals("number", field.type(), code + " 数值规则字段类型");
            assertEquals(expectedUnits.get(field.key()), field.unit(), code + " 单位与阈值口径一致");
        }
    }

    /** 序数升级：三个瓣膜程度字段共用 a_mr_reflux（带序数），reachCodes = 中量(3) / 大量(4) 且序数存在。 */
    @Test
    @SuppressWarnings("unchecked")
    void ordinalRulesUseRefluxOptions() {
        for (String code : List.of("mr_reflux_worsen", "ar_reflux_worsen", "tr_reflux_worsen")) {
            Map<String, Object> row = rulesByCode.get(code);
            FieldRow field = fields.get("A_ANNUAL_FU|" + row.get("target_field"));
            assertEquals("a_mr_reflux", field.optionSet(), code + " 返流程度选项集（V38 序数口径）");
            assertEquals("select", field.type(), code + " 下拉字段");
            List<String> reach = (List<String>) ((Map<?, ?>) row.get("params")).get("reachCodes");
            assertEquals(List.of("3", "4"), reach, code + " 达到即标红 = 中量 / 大量");
            OptionRow moderate = options.get("a_mr_reflux|3");
            OptionRow severe = options.get("a_mr_reflux|4");
            assertEquals("中量", moderate.label());
            assertEquals("3", moderate.ordinal(), "中量序数（升高级数按序数差）");
            assertEquals("大量", severe.label());
            assertEquals("4", severe.ordinal());
        }
    }

    /** 由否变是 / 新发事件：事件字段为是否类（a_yesno）或含「无 = 0」编码的选项集（求值「非空且非无」口径）。 */
    @Test
    void flipAndEventRulesUseYesNoOrZeroNoneOptions() {
        // new_readmission 的 a_readmit_type 无「无」编码（V38：1–6，空 = 未再住院，求值「非空」即命中）
        Set<String> noZeroCode = Set.of("a_readmit_type");
        Map<String, String> optionSets =
                Map.ofEntries(
                        Map.entry("new_death", "a_yesno"),
                        Map.entry("new_thrombus", "a_yesno"),
                        Map.entry("new_readmission", "a_readmit_type"),
                        Map.entry("new_revascularization", "a_revasc"),
                        Map.entry("new_postop_mi", "a_yesno"),
                        Map.entry("new_postop_cvd", "a_post_cvd"),
                        Map.entry("new_revalve", "a_revalve"),
                        Map.entry("new_arr_reop", "a_yesno"),
                        Map.entry("new_postop_bleed", "a_bleed_type"));
        for (Map.Entry<String, String> expected : optionSets.entrySet()) {
            FieldRow field =
                    fields.get(
                            "A_ANNUAL_FU|"
                                    + rulesByCode.get(expected.getKey()).get("target_field"));
            assertEquals(expected.getValue(), field.optionSet(), expected.getKey() + " 事件字段选项集");
            String set = expected.getValue();
            if (noZeroCode.contains(set)) {
                for (int code = 1; code <= 6; code++) {
                    assertNotNull(options.get(set + "|" + code), set + " 含编码 " + code);
                }
                continue;
            }
            if ("a_yesno".equals(set)) {
                assertNotNull(options.get("a_yesno|1"), "是否类含编码 1 = 是");
                assertNotNull(options.get("a_yesno|0"), "是否类含编码 0 = 否（由否变是口径）");
            } else {
                assertNotNull(
                        options.get(set + "|0"), expected.getKey() + " 选项集含编码 0 = 无（「非无」新发口径）");
            }
        }
    }

    /** 基线回落字段口径：EF / LVDd 回落出院前数值字段、心室血栓回落出院前是否类（第 1 年对比基线，Q41）。 */
    @Test
    void baselineFallbackFieldsMatchEchoSection() {
        FieldRow ef = fields.get("A_BASELINE|f102");
        assertEquals("number", ef.type());
        assertEquals("%", ef.unit());
        assertEquals("echo", ef.category(), "出院前最后一次EF 属基线超声分类");
        FieldRow lvdd = fields.get("A_BASELINE|f103");
        assertEquals("number", lvdd.type());
        assertEquals("mm", lvdd.unit());
        FieldRow thrombus = fields.get("A_BASELINE|f113");
        assertEquals("radio", thrombus.type());
        assertEquals("a_yesno", thrombus.optionSet());
    }

    // ---------------------------------------------------------------- 迁移 SQL 解析

    private static String read(String path) {
        try (InputStream in =
                ACompareRulePresetSqlTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "迁移脚本应在 classpath：" + path);
            return stripLineComments(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读迁移脚本失败：" + path, e);
        }
    }

    /** 去掉 -- 行注释（字符串字面量内的「--」保留；V70 在 VALUES 区带逐条注释）。 */
    private static String stripLineComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        boolean inString = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                out.append(c);
                if (c == '\'' && !(i + 1 < sql.length() && sql.charAt(i + 1) == '\'')) {
                    inString = false;
                }
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                while (i < sql.length() && sql.charAt(i) != '\n') {
                    i++;
                }
                out.append('\n');
            } else {
                if (c == '\'') {
                    inString = true;
                }
                out.append(c);
            }
        }
        return out.toString();
    }

    /** 指定表全部 INSERT 的行元组（列序按 RULE_COLUMNS；值：字符串原样、数字转 Number、NULL 为 null、JSON 函数解析）。 */
    private static List<List<Object>> insertRows(String sql, String table) {
        List<List<Object>> rows = new ArrayList<>();
        int from = 0;
        while (true) {
            String insert;
            try {
                insert = statementOf(sql, table, from);
            } catch (IllegalStateException end) {
                return rows;
            }
            from = sql.indexOf(insert, from) + insert.length();
            int open = insert.indexOf('(');
            int close = matchingParen(insert, open);
            List<String> columns = splitTopLevel(insert.substring(open + 1, close));
            assertEquals(
                    List.of(RULE_COLUMNS),
                    columns.stream().map(String::strip).collect(Collectors.toList()),
                    table + " INSERT 列序与 V67 一致");
            int valuesAt = insert.indexOf("VALUES", close);
            assertTrue(valuesAt > 0, table + " INSERT 应有 VALUES");
            int cursor = valuesAt + "VALUES".length();
            while (cursor < insert.length()) {
                while (cursor < insert.length()
                        && (Character.isWhitespace(insert.charAt(cursor))
                                || insert.charAt(cursor) == ',')) {
                    cursor++;
                }
                if (cursor >= insert.length() || insert.charAt(cursor) != '(') {
                    break;
                }
                int tupleEnd = matchingParen(insert, cursor);
                List<String> tokens = splitTopLevel(insert.substring(cursor + 1, tupleEnd));
                assertEquals(RULE_COLUMNS.length, tokens.size(), table + " 列数与值数一致");
                List<Object> row = new ArrayList<>();
                for (String token : tokens) {
                    row.add(value(token.strip()));
                }
                rows.add(row);
                cursor = tupleEnd + 1;
            }
        }
    }

    /** V38 fe_field 行：'form|field_key' → 行（只取核对用列：分类、类型、单位、选项集）。 */
    private static Map<String, FieldRow> fieldRows(String sql) {
        // fe_field 列序：form_code, field_key, field_code, label, category_key, type, unit, …
        Map<String, FieldRow> rows = new LinkedHashMap<>();
        for (List<Object> tuple : tuplesOf(sql, "fe_field")) {
            String form = (String) tuple.get(0);
            String key = (String) tuple.get(1);
            rows.put(
                    form + "|" + key,
                    new FieldRow(
                            form,
                            key,
                            (String) tuple.get(4),
                            (String) tuple.get(5),
                            (String) tuple.get(6),
                            (String) tuple.get(10)));
        }
        return rows;
    }

    /** V38 fe_option 行：'set|code' → 行（label、ordinal）。 */
    private static Map<String, OptionRow> optionRows(String sql) {
        // fe_option 列序：set_code, option_code, label, ordinal, sort_no
        Map<String, OptionRow> rows = new LinkedHashMap<>();
        for (List<Object> tuple : tuplesOf(sql, "fe_option")) {
            rows.put(
                    tuple.get(0) + "|" + tuple.get(1),
                    new OptionRow(
                            (String) tuple.get(0),
                            String.valueOf(tuple.get(1)),
                            (String) tuple.get(2),
                            tuple.get(3) == null ? null : String.valueOf(tuple.get(3))));
        }
        return rows;
    }

    /** 指定表全部 INSERT 的行元组（原始 token → 值），列序按各自建表语句（见调用处注释）。 */
    private static List<List<Object>> tuplesOf(String sql, String table) {
        List<List<Object>> rows = new ArrayList<>();
        int from = 0;
        while (true) {
            String insert;
            try {
                insert = statementOf(sql, table, from);
            } catch (IllegalStateException end) {
                return rows;
            }
            from = sql.indexOf(insert, from) + insert.length();
            int valuesAt = insert.indexOf("VALUES");
            assertTrue(valuesAt > 0, table + " INSERT 应有 VALUES");
            int cursor = valuesAt + "VALUES".length();
            while (cursor < insert.length()) {
                while (cursor < insert.length()
                        && (Character.isWhitespace(insert.charAt(cursor))
                                || insert.charAt(cursor) == ',')) {
                    cursor++;
                }
                if (cursor >= insert.length() || insert.charAt(cursor) != '(') {
                    break;
                }
                int tupleEnd = matchingParen(insert, cursor);
                List<Object> row = new ArrayList<>();
                for (String token : splitTopLevel(insert.substring(cursor + 1, tupleEnd))) {
                    row.add(value(token.strip()));
                }
                rows.add(row);
                cursor = tupleEnd + 1;
            }
        }
    }

    /** 从 from 起找下一条 INSERT INTO table 语句（到分号止）；找不到抛 IllegalStateException 结束循环。 */
    private static String statementOf(String sql, String table, int from) {
        int head = from;
        while (true) {
            head = sql.indexOf("INSERT INTO " + table, head);
            if (head < 0) {
                throw new IllegalStateException("no more INSERT INTO " + table);
            }
            int afterTable = head + "INSERT INTO ".length() + table.length();
            char next = afterTable < sql.length() ? sql.charAt(afterTable) : ';';
            if (!Character.isLetterOrDigit(next) && next != '_') {
                break; // 表名完整结束（换行 / 空格 / '(' 均可），排除同前缀表
            }
            head = afterTable;
        }
        int end = sql.indexOf(';', head);
        assertTrue(end > 0, table + " 的 INSERT 语句应以分号结束");
        return sql.substring(head, end);
    }

    /** 顶层逗号切分（引号保留为 SQL 字面量原文、'' 为转义、括号深度内豁免——JSON_OBJECT / JSON_ARRAY / 函数调用）。 */
    private static List<String> splitTopLevel(String raw) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\'' && !(i + 1 < raw.length() && raw.charAt(i + 1) == '\'')) {
                    inString = false; // 结束引号（'' 转义对原样保留，由 value 还原）
                }
            } else if (c == '\'') {
                inString = true;
                current.append(c);
            } else if (c == '(') {
                depth++;
                current.append(c);
            } else if (c == ')') {
                depth--;
                current.append(c);
            } else if (c == ',' && depth == 0) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    /** openAt 处 '(' 的配对右括号下标（单引号内豁免）。 */
    private static int matchingParen(String sql, int openAt) {
        int depth = 0;
        boolean inString = false;
        for (int i = openAt; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        i++;
                    } else {
                        inString = false;
                    }
                }
            } else if (c == '\'') {
                inString = true;
            } else if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new IllegalArgumentException(
                "括号不配对：" + sql.substring(openAt, Math.min(sql.length(), openAt + 40)));
    }

    /**
     * SQL 值 token → Java 值：NULL → null；'{}' → 空参数对象；'…' → 字符串；数字 → Integer/Long/Double；JSON 函数解析。
     */
    private static Object value(String token) {
        if ("NULL".equals(token)) {
            return null;
        }
        if ("{}".equals(token) || "'{}'".equals(token)) {
            // FLIP_TO_YES 无阈值键：迁移写字符串字面量 '{}'（MySQL JSON_OBJECT() 零参报错），JSON 列收为 {}
            return new LinkedHashMap<String, Object>();
        }
        if (token.startsWith("'")) {
            return token.substring(1, token.length() - 1).replace("''", "'");
        }
        if (token.startsWith("JSON_OBJECT(")) {
            return parseJsonObject(token);
        }
        if (token.startsWith("JSON_ARRAY(")) {
            return parseJsonArray(token);
        }
        if (token.matches("-?\\d+")) {
            long parsed = Long.parseLong(token);
            // 整数字面量统一 Integer（与 CompareRuleType.requireInteger / 期望 Map.of 同型；
            // 不能用三元：Integer/Long 两支会被数值提升统一成 Long）
            if (parsed >= Integer.MIN_VALUE && parsed <= Integer.MAX_VALUE) {
                return Integer.valueOf((int) parsed);
            }
            return parsed;
        }
        if (token.matches("-?\\d+\\.\\d+")) {
            return Double.parseDouble(token);
        }
        throw new IllegalArgumentException("无法解析的 SQL 值：" + token);
    }

    private static Map<String, Object> parseJsonObject(String call) {
        List<String> args =
                splitTopLevel(call.substring(call.indexOf('(') + 1, call.lastIndexOf(')')));
        Map<String, Object> json = new LinkedHashMap<>();
        for (int i = 0; i + 1 < args.size(); i += 2) {
            String key = (String) value(args.get(i).strip());
            json.put(key, value(args.get(i + 1).strip()));
        }
        return json;
    }

    private static List<Object> parseJsonArray(String call) {
        List<String> args =
                splitTopLevel(call.substring(call.indexOf('(') + 1, call.lastIndexOf(')')));
        List<Object> json = new ArrayList<>();
        for (String arg : args) {
            json.add(value(arg.strip()));
        }
        return json;
    }

    private static int number(Object raw) {
        return raw instanceof Number number
                ? number.intValue()
                : Integer.parseInt(String.valueOf(raw));
    }
}
