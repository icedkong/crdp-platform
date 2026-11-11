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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * E21-T20 核对（单元）：医院 C 对比标红规则预置迁移（followup V82）与 hospital-c-spec 第 5 章「异常对比规则（医院已确认，
 * 阈值可配置，Q02）」表逐项一致（C-FU-010、C-FU-011；tasks.csv 验收原文：C 阈值可配置，表单内标红）。
 *
 * <p>基准是 docs/requirements/hospital-c-spec.md（§5 异常对比规则表 4 行：起搏阈值升高 ＞ 50% 脉宽不同注明仅供参考、 电极阻抗波动 ＞
 * 50%（可另开绝对范围 200–2000 Ω，默认关闭）、感知明显下降 ＞ 50%、电池接近更换（ERI 状态非正常或预计电池寿命 ≤ 1 年）； 附录 A
 * 随访组字段），比对对象是随应用一起发布的 db/tenant/followup/V82__hospital_c_compare_rule_preset.sql：不依赖数据库， CI
 * 与本地必跑；落库后的开关链路由 CompareRulesIT（门控 CRDP_IT_MYSQL_PORT）经 HTTP 再核一遍。
 *
 * <p>覆盖：
 *
 * <ul>
 *   <li>异常对比规则表 4 行全部有对应规则行（阻抗绝对范围按腔室 × 边界拆 4 条、默认停用），共 12 条，(form_code, rule_code) 唯一；
 *   <li>每条的 rule_type / params 默认值与表一致（升高 / 双向 / 下降各 > 50%、绝对范围 200–2000 Ω 严格边界、ERI 编码 1 / 2、寿命 ≤ 1
 *       年），params 能通过 {@link CompareRuleType#validateParams} 键集校验——这是 PUT 开关 + 调阈值的入参口径， 全部通过 =
 *       每条规则都可用统一入口开关（「C 阈值可配置」）；
 *   <li>脉宽参考注：两条阈值升高规则的 noteField 指向同腔室脉宽键、noteText 固定「脉宽不同，仅供参考」（C-FU-010）；
 *   <li>字段键为 E21-T20 定下的 C_PROGRAM_FU 稳定键契约（附录 A 随访组 #n → f3nn，OQ-130）：target ⊆ 契约键集、VALUE_HIT
 *       codes ⊆ c_eri_status 编码（0=正常 / 1=接近ERI / 2=已达ERI(EOI)）、分类锚点 ∈ 程控四分组； fe_field 字典随 E23-T20
 *       录入，届时须用同一批键（本测试先锁契约防漂移）；
 *   <li>默认启用 8 条（INSERT 不写 status 列，表默认 ENABLED）+ 默认停用 4 条（显式 status = 'DISABLED'），
 *       停用只能由租户管理员开启（E17-T01 的 PUT）；
 *   <li>共享用例文件 {@code crdp-followup/src/test/resources/shared-cases/compare-cases-c.json} 的 12
 *       条规则定义与本测试解析的 V82 预置行逐项一致（由后端 {@code CCompareRuleCasesTest} 与前端 {@code
 *       form-compare-c.test.ts} 各执行一遍； 本核对保证两端正反用例跑的是真实预置规则——迁移调阈值不同步用例文件即失败）。
 * </ul>
 */
class CCompareRulePresetSqlTest {

    private static final String PRESET =
            "db/tenant/followup/V82__hospital_c_compare_rule_preset.sql";

    /** V82 第一条 INSERT 的列序（不含 status → 默认 ENABLED，与 V67 / V70 同款）。 */
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

    /** V82 第二条 INSERT 的列序（绝对范围默认停用，显式带 status）。 */
    private static final String[] RULE_COLUMNS_WITH_STATUS = {
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
        "sort_no",
        "status"
    };

    /** C_PROGRAM_FU 稳定键契约（OQ-130，附录 A 随访组 #n → f3nn）：本预置引用的全部字段。 */
    private static final Set<String> CONTRACT_FIELDS =
            Set.of("f308", "f309", "f310", "f311", "f313", "f314", "f315", "f316", "f323", "f324");

    /** 程控四分组锚点契约（OQ-130：起搏阈值与脉宽 / 感知 / 阻抗 / 电池）。 */
    private static final Set<String> CONTRACT_CATEGORIES =
            Set.of("threshold", "sensing", "impedance", "battery");

    /** c_eri_status 选项编码契约（OQ-130：0=正常 / 1=接近ERI / 2=已达ERI(EOI)）。 */
    private static final Set<String> ERI_CODES = Set.of("0", "1", "2");

    private static Map<String, Map<String, Object>> rulesByCode;

    @BeforeAll
    static void parse() {
        rulesByCode = new LinkedHashMap<>();
        String sql = read(PRESET);
        for (List<Object> row : insertRows(sql, "fu_compare_rule")) {
            String code = (String) row.get(1);
            assertTrue(!rulesByCode.containsKey(code), "(form_code, rule_code) 唯一，重复预置：" + code);
            rulesByCode.put(code, rowToMap(row));
        }
    }

    // ---------------------------------------------------------------- 异常对比规则表全量与默认值

    /**
     * 异常对比规则表 4 行 → 12 条规则行；rule_code → (name, category, target, rule_type, params, message, sort,
     * status)。
     */
    @Test
    void presetMatchesHospitalCSpecTable() {
        Map<String, Object[]> spec = new LinkedHashMap<>();
        // —— 起搏阈值升高（比上次升高＞50%；脉宽不同时注明仅供参考）——
        spec.put(
                "ra_threshold_rise",
                new Object[] {
                    "心房起搏阈值升高",
                    "threshold",
                    "f308",
                    "CHANGE_PCT",
                    Map.of(
                            "direction",
                            "RISE",
                            "percent",
                            50,
                            "noteField",
                            "f309",
                            "noteText",
                            "脉宽不同，仅供参考"),
                    "心房起搏阈值比上次升高超过 50%",
                    10,
                    "ENABLED"
                });
        spec.put(
                "rv_threshold_rise",
                new Object[] {
                    "心室起搏阈值升高",
                    "threshold",
                    "f313",
                    "CHANGE_PCT",
                    Map.of(
                            "direction",
                            "RISE",
                            "percent",
                            50,
                            "noteField",
                            "f314",
                            "noteText",
                            "脉宽不同，仅供参考"),
                    "心室起搏阈值比上次升高超过 50%",
                    11,
                    "ENABLED"
                });
        // —— 电极阻抗波动（比上次升高或降低＞50%）——
        spec.put(
                "ra_impedance_shift",
                new Object[] {
                    "心房阻抗波动",
                    "impedance",
                    "f311",
                    "CHANGE_PCT",
                    Map.of("direction", "ANY", "percent", 50),
                    "心房阻抗比上次升高或降低超过 50%",
                    20,
                    "ENABLED"
                });
        spec.put(
                "rv_impedance_shift",
                new Object[] {
                    "心室阻抗波动",
                    "impedance",
                    "f316",
                    "CHANGE_PCT",
                    Map.of("direction", "ANY", "percent", 50),
                    "心室阻抗比上次升高或降低超过 50%",
                    21,
                    "ENABLED"
                });
        // —— 阻抗绝对范围 200–2000 Ω（可另开，默认关闭：每腔室 × 边界各一条）——
        for (Object[] bound :
                new Object[][] {
                    {
                        "ra_impedance_below_200",
                        "心房阻抗低于 200",
                        "f311",
                        "LT",
                        200,
                        "心房阻抗 ＜ 200 Ω（超出提示范围）",
                        22
                    },
                    {
                        "ra_impedance_above_2000",
                        "心房阻抗高于 2000",
                        "f311",
                        "GT",
                        2000,
                        "心房阻抗 ＞ 2000 Ω（超出提示范围）",
                        23
                    },
                    {
                        "rv_impedance_below_200",
                        "心室阻抗低于 200",
                        "f316",
                        "LT",
                        200,
                        "心室阻抗 ＜ 200 Ω（超出提示范围）",
                        24
                    },
                    {
                        "rv_impedance_above_2000",
                        "心室阻抗高于 2000",
                        "f316",
                        "GT",
                        2000,
                        "心室阻抗 ＞ 2000 Ω（超出提示范围）",
                        25
                    }
                }) {
            spec.put(
                    (String) bound[0],
                    new Object[] {
                        bound[1],
                        "impedance",
                        bound[2],
                        "OUT_OF_RANGE",
                        Map.of("op", bound[3], "value", bound[4]),
                        bound[5],
                        bound[6],
                        "DISABLED"
                    });
        }
        // —— 感知明显下降（比上次下降＞50%）——
        spec.put(
                "ra_sensing_drop",
                new Object[] {
                    "心房感知下降",
                    "sensing",
                    "f310",
                    "CHANGE_PCT",
                    Map.of("direction", "DROP", "percent", 50),
                    "心房感知 P 波比上次下降超过 50%",
                    30,
                    "ENABLED"
                });
        spec.put(
                "rv_sensing_drop",
                new Object[] {
                    "心室感知下降",
                    "sensing",
                    "f315",
                    "CHANGE_PCT",
                    Map.of("direction", "DROP", "percent", 50),
                    "心室感知 R 波比上次下降超过 50%",
                    31,
                    "ENABLED"
                });
        // —— 电池接近更换（ERI 状态非正常；预计电池寿命 ≤ 1 年）——
        spec.put(
                "eri_status_abnormal",
                new Object[] {
                    "ERI 状态异常",
                    "battery",
                    "f323",
                    "VALUE_HIT",
                    Map.of("codes", List.of("1", "2")),
                    "ERI 状态为接近ERI或已达ERI(EOI)",
                    40,
                    "ENABLED"
                });
        spec.put(
                "battery_life_low",
                new Object[] {
                    "预计电池寿命偏低",
                    "battery",
                    "f324",
                    "VALUE_HIT",
                    Map.of("op", "LTE", "value", 1),
                    "预计电池寿命 ≤ 1 年",
                    41,
                    "ENABLED"
                });

        assertEquals(spec.keySet(), rulesByCode.keySet(), "异常对比规则表 4 行拆为 12 条规则，无缺失无多余");
        for (Map.Entry<String, Object[]> expected : spec.entrySet()) {
            Map<String, Object> row = rulesByCode.get(expected.getKey());
            assertNotNull(row, "规则应已预置：" + expected.getKey());
            Object[] want = expected.getValue();
            String at = expected.getKey();
            assertEquals("C_PROGRAM_FU", row.get("form_code"), at + " 表单");
            assertEquals(want[0], row.get("name"), at + " 规则名");
            assertEquals(want[1], row.get("category_key"), at + " 分类锚点");
            assertEquals(want[2], row.get("target_field"), at + " 对比字段");
            assertEquals(want[3], row.get("rule_type"), at + " 规则类型");
            assertEquals(want[4], row.get("params"), at + " 默认阈值参数");
            assertEquals(null, row.get("baseline_form_code"), at + " C 无基线回落");
            assertEquals(null, row.get("baseline_field"), at + " C 无基线回落");
            assertEquals(want[5], row.get("message"), at + " 标红提示文案");
            assertEquals(((Number) want[6]).intValue(), number(row.get("sort_no")), at + " 排序");
            assertEquals(want[7], row.get("status"), at + " 默认开关");
        }
    }

    /** 「C 阈值可配置」的入参口径：每条预置 params 能通过 {@link CompareRuleType#validateParams} 键集校验（PUT 全部成立）。 */
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

    /** 脉宽参考注（C-FU-010「脉宽不同时注明仅供参考」）：两条阈值升高规则的 noteField = 同腔室脉宽键、noteText 固定文案。 */
    @Test
    @SuppressWarnings("unchecked")
    void thresholdRulesCarryPulseWidthNote() {
        Map<String, String> noteFieldByRule =
                Map.of("ra_threshold_rise", "f309", "rv_threshold_rise", "f314");
        for (Map.Entry<String, String> expected : noteFieldByRule.entrySet()) {
            Map<String, Object> params =
                    (Map<String, Object>) rulesByCode.get(expected.getKey()).get("params");
            assertEquals(
                    expected.getValue(),
                    params.get("noteField"),
                    expected.getKey() + " 参考注字段 = 同腔室脉宽");
            assertEquals("脉宽不同，仅供参考", params.get("noteText"), expected.getKey() + " 参考注文案");
        }
    }

    /** 字段键 / 选项编码 / 分类锚点全部在 E21-T20 定下的 C_PROGRAM_FU 契约内（OQ-130；E23-T20 字典须用同一批键）。 */
    @Test
    @SuppressWarnings("unchecked")
    void referencesStayWithinStableKeyContract() {
        Set<String> referenced = new LinkedHashSet<>();
        for (Map<String, Object> row : rulesByCode.values()) {
            referenced.add((String) row.get("target_field"));
            assertTrue(
                    CONTRACT_CATEGORIES.contains(row.get("category_key")),
                    "分类锚点应在程控四分组契约内：" + row.get("category_key"));
            Map<String, Object> params = (Map<String, Object>) row.get("params");
            if (params.get("noteField") instanceof String noteField) {
                referenced.add(noteField);
            }
            if (params.get("codes") instanceof List<?> codes) {
                for (Object code : codes) {
                    assertTrue(
                            ERI_CODES.contains(String.valueOf(code)),
                            "ERI 取值命中编码应在 c_eri_status 契约内：" + code);
                }
            }
        }
        assertTrue(CONTRACT_FIELDS.containsAll(referenced), "引用字段应在稳定键契约内：" + referenced);
    }

    /**
     * E21-T20 正反用例的绑定核对：共享用例文件 {@code
     * crdp-followup/src/test/resources/shared-cases/compare-cases-c.json} 里的 12 条规则定义与本测试解析的 V82
     * 预置行逐项一致 （ruleCode / categoryKey / targetField / ruleType / params / message）。用例文件在
     * crdp-followup 测试资源（跨模块，走仓库文件定位）， 由后端 {@code CCompareRuleCasesTest} 与前端 {@code
     * form-compare-c.test.ts} 各执行一遍；本核对保证两端正反用例跑的是真实预置规则。
     */
    @Test
    void cSharedCaseFileMatchesPresetRows() throws IOException {
        JsonNode root =
                new ObjectMapper().readTree(Files.newInputStream(locateCCaseFile())).path("rules");
        assertTrue(root.isArray() && !root.isEmpty(), "C 用例文件应带 rules 数组");
        assertEquals("C_PROGRAM_FU", rulesByCode.values().iterator().next().get("form_code"));
        Map<String, JsonNode> caseRules = new LinkedHashMap<>();
        for (JsonNode rule : root) {
            String code = rule.path("ruleCode").asText();
            assertTrue(!caseRules.containsKey(code), "C 用例文件规则重复：" + code);
            caseRules.put(code, rule);
        }
        assertEquals(
                rulesByCode.keySet(), caseRules.keySet(), "C 用例文件的规则集应与 V82 预置完全一致（缺失即用例没跟上预置）");
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
            assertEquals(row.get("message"), rule.path("message").asText(), at + " 标红提示文案");
        }
    }

    /** 从模块目录向上找到 C 共享用例文件（crdp-followup 测试资源，跨模块读仓库文件）。 */
    private static Path locateCCaseFile() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate =
                    dir.resolve(
                            "backend/crdp-followup/src/test/resources/shared-cases/compare-cases-c.json");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "未找到 backend/crdp-followup/src/test/resources/shared-cases/compare-cases-c.json"
                        + "（应在仓库内运行）");
    }

    // ---------------------------------------------------------------- 迁移 SQL 解析（列序兼容两种 INSERT）

    private static String read(String path) {
        try (InputStream in =
                CCompareRulePresetSqlTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "迁移脚本应在 classpath：" + path);
            return stripLineComments(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("读迁移脚本失败：" + path, e);
        }
    }

    /** 去掉 -- 行注释（字符串字面量内的「--」保留；V82 在 VALUES 区带逐条注释）。 */
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

    /** V82 全部 INSERT 的行元组（列序兼容 11 列与 12 列两种；status 列缺省补 'ENABLED'）。 */
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
            List<String> names = columns.stream().map(String::strip).collect(Collectors.toList());
            assertTrue(
                    List.of(RULE_COLUMNS).equals(names)
                            || List.of(RULE_COLUMNS_WITH_STATUS).equals(names),
                    table + " INSERT 列序应为 11 列（默认 ENABLED）或 12 列（显式 status）");
            boolean withStatus = names.size() == RULE_COLUMNS_WITH_STATUS.length;
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
                int expectedTokens =
                        withStatus ? RULE_COLUMNS_WITH_STATUS.length : RULE_COLUMNS.length;
                assertEquals(expectedTokens, tokens.size(), table + " 列数与值数一致");
                List<Object> row = new ArrayList<>();
                for (String token : tokens) {
                    row.add(value(token.strip()));
                }
                if (!withStatus) {
                    row.add("ENABLED"); // 表默认（V67 建表），与显式写 'ENABLED' 同义
                }
                rows.add(row);
                cursor = tupleEnd + 1;
            }
        }
    }

    /** 行元组 → 具名列 Map（12 列含 status）。 */
    private static Map<String, Object> rowToMap(List<Object> row) {
        Map<String, Object> rule = new LinkedHashMap<>();
        for (int i = 0; i < RULE_COLUMNS_WITH_STATUS.length; i++) {
            rule.put(RULE_COLUMNS_WITH_STATUS[i], row.get(i));
        }
        return rule;
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
            // 整数字面量统一 Integer（与 CompareRuleType.requireInteger / 期望 Map.of 同型）
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
