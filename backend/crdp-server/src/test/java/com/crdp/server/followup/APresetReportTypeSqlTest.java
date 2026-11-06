package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * E16-T01 核对（单元）：预置迁移 V62 与医院 A 规格「关键字段 · 预置资料类型（A 医院）」逐项一致 （验收原文：A 预置 10 类资料类型可配置）。
 *
 * <p>基准是 docs/requirements/hospital-a-spec.md（A-ATT-003 预置 10 类、A-ATT-011 名称字典示例、 A-ATT-012
 * 检验指标模板示例）与 docs/api/report.md §2（通话记录 typeCode = CALL）， 比对对象是随应用一起发布的迁移脚本
 * db/tenant/followup/V62__rpt_type_name_indicator.sql： 不依赖数据库，CI 与本地必跑；落库后的同一口径由 {@code
 * ReportConfigIT}（门控 CRDP_IT_MYSQL_PORT）经 HTTP 再核一遍（10 类在配置接口可见、类型可增改停删）。
 */
class APresetReportTypeSqlTest {

    private static final String PRESET = "db/tenant/followup/V62__rpt_type_name_indicator.sql";

    private static final Pattern TUPLE = Pattern.compile("\\(([^()]*(?:'[^']*'[^()]*)*)\\)");

    private static Map<String, List<String>> typeRows;
    private static List<List<String>> feForms;
    private static List<List<String>> feFields;
    private static List<List<String>> tplRows;
    private static List<List<String>> itemRows;
    private static List<List<String>> nameRows;
    private static String presetSql;

    @BeforeAll
    static void parse() throws IOException {
        presetSql = read(PRESET);
        typeRows = tuples(presetSql, "INSERT INTO rpt_type");
        feForms = valuesTuples(presetSql, "INSERT INTO fe_form");
        feFields = valuesTuples(presetSql, "INSERT INTO fe_field");
        tplRows = valuesTuples(presetSql, "INSERT INTO rpt_indicator_tpl");
        itemRows = selectRows(section(presetSql, "INSERT INTO rpt_indicator_item"), "item_name");
        nameRows = selectRows(section(presetSql, "INSERT INTO rpt_name"), "type_code");
    }

    /** A-ATT-003：A 医院预置 10 类，顺序与名称与规格表一致；全部启用、名称必填、不启用电子化状态。 */
    @Test
    void tenPresetTypesMatchSpec() {
        String[][] expected = {
            {"ECHO", "心脏超声", "RPT_ECHO"},
            {"ANGIO", "冠脉造影", "RPT_ANGIO"},
            {"ECG", "心电图", "RPT_ECG"},
            {"CHEST_IMAGING", "胸片 / CT", "RPT_CHEST"},
            {"LAB", "检验", "RPT_LAB"},
            {"DISCHARGE_SUMMARY", "出院小结", "RPT_DISCHARGE"},
            {"OPERATION_RECORD", "手术记录", "RPT_OPERATION"},
            {"CONSENT", "知情同意书", "RPT_CONSENT"},
            {"CALL", "通话记录", "RPT_CALL"},
            {"OTHER", "其他", "RPT_OTHER"},
        };
        assertEquals(10, typeRows.size(), "A 医院预置 10 类资料类型");
        int index = 0;
        for (Map.Entry<String, List<String>> row : typeRows.entrySet()) {
            List<String> values = row.getValue();
            assertEquals(expected[index][0], values.get(0), "类型编码（第 " + (index + 1) + " 类）");
            assertEquals(expected[index][1], values.get(1), "类型名称（A 规格预置资料类型表）");
            assertEquals(expected[index][2], values.get(2), "字段集引用的 REPORT 表单");
            assertEquals("1", values.get(3), "A 医院名称全部必填（A 规格关键字段表：名称 必填 = 是）");
            assertEquals("NULL", values.get(4), "A 医院不启用电子化状态（A-ATT-018）");
            assertEquals("ENABLED", values.get(6), "预置类型启用");
            assertEquals(String.valueOf((index + 1) * 10), values.get(5), "排序按规格表顺序 10–100");
            index++;
        }
        assertEquals("CALL", expected[8][0], "通话记录 typeCode = CALL（report.md §2 A-ATT-017 口径）");
    }

    /** tech-plan §10：字段集引用 fe_form（usage_type = REPORT），每类一个、编码一一对应。 */
    @Test
    void everyTypeHasOneReportForm() {
        assertEquals(10, feForms.size(), "10 个 REPORT 表单");
        Map<String, String> formByCode = new LinkedHashMap<>();
        for (List<String> form : feForms) {
            assertEquals("REPORT", form.get(2), "fe_form.usage_type = REPORT（字段集载体）");
            formByCode.put(form.get(0), form.get(2));
        }
        for (List<String> type : typeRows.values()) {
            assertNotNull(formByCode.get(type.get(2)), "类型 " + type.get(0) + " 的字段集表单存在");
        }
        assertTrue(feFields.size() >= 18, "每类至少一个专属字段（A 规格主要字段列）");
    }

    /** A-ATT-011：名称字典示例逐项一致（心脏超声 3 项 … 通话记录 1 项；「其他」不预置名称）。 */
    @Test
    void nameDictionaryMatchesSpec() {
        Map<String, List<String>> namesByType = new LinkedHashMap<>();
        for (List<String> row : nameRows) {
            namesByType.computeIfAbsent(row.get(0), key -> new ArrayList<>()).add(row.get(1));
        }
        assertEquals(List.of("经胸超声心动图", "床旁超声", "经食道超声"), namesByType.get("ECHO"), "心脏超声名称字典示例");
        assertEquals(List.of("冠脉造影", "冠脉 CTA"), namesByType.get("ANGIO"));
        assertEquals(List.of("常规心电图", "动态心电图（Holter）"), namesByType.get("ECG"));
        assertEquals(List.of("胸部正位片", "胸部 CT"), namesByType.get("CHEST_IMAGING"));
        assertEquals(
                List.of("血常规", "肝肾功能", "血脂", "血糖与糖化血红蛋白", "NT-proBNP", "心肌损伤标志物"),
                namesByType.get("LAB"),
                "检验名称字典示例");
        assertEquals(List.of("出院小结"), namesByType.get("DISCHARGE_SUMMARY"));
        assertEquals(List.of("手术记录", "麻醉记录"), namesByType.get("OPERATION_RECORD"));
        assertEquals(List.of("手术知情同意书", "随访知情同意书"), namesByType.get("CONSENT"));
        assertEquals(List.of("随访电话录音"), namesByType.get("CALL"));
        assertEquals(null, namesByType.get("OTHER"), "「其他」不预置名称（规格表名称字典示例「—」）");
    }

    /** A-ATT-012：检验指标模板示例（项目名称、代码、参考范围、单位）与规格表逐项一致。 */
    @Test
    void indicatorTemplatesMatchSpec() {
        List<String> tplNames = tplRows.stream().map(row -> row.get(0)).toList();
        assertEquals(List.of("血常规", "肝肾功能", "血脂", "NT-proBNP", "心肌损伤标志物"), tplNames);
        Map<String, Map<String, List<String>>> itemsByTpl = new LinkedHashMap<>();
        for (List<String> row : itemRows) {
            itemsByTpl
                    .computeIfAbsent(row.get(0), key -> new LinkedHashMap<>())
                    .put(row.get(2), row);
        }
        Map<String, List<String>> cbc = itemsByTpl.get("血常规");
        assertEquals("白细胞", cbc.get("WBC").get(1));
        assertEquals("10^9/L", cbc.get("WBC").get(3), "WBC 单位");
        assertEquals("3.5–9.5", cbc.get("WBC").get(4), "WBC 参考范围");
        assertEquals("130–175", cbc.get("HGB").get(5), "HGB 男性参考范围（可按性别区分）");
        assertEquals("115–150", cbc.get("HGB").get(6), "HGB 女性参考范围");
        assertEquals("125–350", cbc.get("PLT").get(4));

        assertEquals("57–111", itemsByTpl.get("肝肾功能").get("Cr").get(5), "肌酐男性参考范围");
        assertEquals("41–81", itemsByTpl.get("肝肾功能").get("Cr").get(6), "肌酐女性参考范围");
        assertEquals("208–428", itemsByTpl.get("肝肾功能").get("UA").get(4));
        assertEquals("≤ 3.4", itemsByTpl.get("血脂").get("LDL-C").get(4));
        assertEquals("≤ 1.7", itemsByTpl.get("血脂").get("TG").get(4));
        assertEquals("＜ 125", itemsByTpl.get("NT-proBNP").get("NT-proBNP").get(4));
        assertEquals("≤ 0.04", itemsByTpl.get("心肌损伤标志物").get("hs-cTnI").get(4));
    }

    /** 指标模板只绑给检验名称（A-ATT-012：名称可绑定模板；绑定为 LEFT JOIN 且仅 LAB 五项命中）。 */
    @Test
    void templatesBoundOnlyToLabNames() {
        assertTrue(presetSql.contains("v.type_code = 'LAB'"), "模板只绑给检验类型下的名称（A 规格检验指标模板示例）");
        assertTrue(
                presetSql.contains("v.name IN ('血常规', '肝肾功能', '血脂', 'NT-proBNP', '心肌损伤标志物')"),
                "恰好五个有示例项目的名称绑定模板（血糖与糖化血红蛋白无示例项、不绑定）");
    }

    /** 菜单与授予链齐全：平台库 V53 建菜单 39 + 授权范围模板；followup V62 与种子 V124 双路授予。 */
    @Test
    void menuAndGrantsAreConsistent() throws IOException {
        String platformMenu = read("db/platform/V53__hospital_report_config_menu.sql");
        assertTrue(platformMenu.contains("(39, 30, 'HOSPITAL', 'MENU', 'followup', '报告单设置'"));
        assertTrue(
                platformMenu.contains("'followup:scan:config'"), "权限码 followup:scan:config（OQ-12）");
        assertTrue(
                platformMenu.contains("/system/report-config"),
                "路由 /system/report-config（pages.md §0.1）");
        assertTrue(
                platformMenu.contains("'followup/report/config'"),
                "component followup/report/config");
        assertTrue(platformMenu.contains("plt_scope_template"), "进授权范围模板（非敏感项默认可分配）");

        assertTrue(presetSql.contains("rm.menu_id = 39"), "followup V62 给老库 TENANT_ADMIN 补授");
        String seedGrant = read("db/tenant/seed/V124__grant_report_config_menu.sql");
        assertTrue(seedGrant.contains("menu_id = 39"), "种子 V124 给全新库兜底补授");
    }

    // ---------------------------------------------------------------- 解析

    private static String read(String path) throws IOException {
        try (InputStream in =
                APresetReportTypeSqlTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "迁移脚本应随测试 classpath 提供：" + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 取「INSERT INTO 表 …；」整段（到分号）。 */
    private static String section(String sql, String marker) {
        int start = sql.indexOf(marker);
        assertTrue(start >= 0, "迁移应包含 " + marker);
        int end = sql.indexOf(';', start);
        return sql.substring(start, end);
    }

    /** VALUES 多行元组 → 首列为键的有序映射（rpt_type 用）。 */
    private static Map<String, List<String>> tuples(String sql, String marker) {
        Map<String, List<String>> rows = new LinkedHashMap<>();
        for (List<String> tuple : valuesTuples(sql, marker)) {
            rows.put(tuple.get(0), tuple);
        }
        return rows;
    }

    /** 只解析 VALUES 之后的元组（跳过 INSERT 的列清单括号）。 */
    private static List<List<String>> valuesTuples(String sql, String marker) {
        String section = section(sql, marker);
        int values = section.indexOf("VALUES");
        assertTrue(values >= 0, marker + " 应为 VALUES 多行元组形态");
        return tupleLists(section.substring(values));
    }

    /** 解析一段 SQL 里的全部 (...) 元组为字符串清单（NULL 保留字面量，引号去除）。 */
    private static List<List<String>> tupleLists(String sql) {
        List<List<String>> rows = new ArrayList<>();
        Matcher matcher = TUPLE.matcher(sql);
        while (matcher.find()) {
            rows.add(literals(matcher.group(1)));
        }
        return rows;
    }

    /** 解析 INSERT … SELECT … UNION ALL 形态的行（项目 / 名称预置用）：按首个别名列名锚定每行。 */
    private static List<List<String>> selectRows(String sql, String firstColumnAlias) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : sql.split("\\n")) {
            String trimmed = line.trim();
            String prefix = "SELECT ";
            // 首行是「FROM (SELECT …」，后续行是「UNION ALL SELECT …」
            if (trimmed.contains(prefix)) {
                int offset = trimmed.indexOf(prefix) + prefix.length();
                int end = trimmed.endsWith(",") ? trimmed.length() - 1 : trimmed.length();
                // 去掉「AS 别名」，首行 SELECT 带 AS、后续 UNION ALL 行不带
                String columns = trimmed.substring(offset, end).replaceAll("\\s+AS\\s+\\w+", "");
                rows.add(literals(columns));
            }
        }
        assertTrue(
                rows.stream().allMatch(row -> !row.isEmpty()),
                "SELECT 预置行解析出列（锚点 " + firstColumnAlias + "）");
        return rows;
    }

    /** 逐字符拆单引号字符串与裸词（NULL / 数字），保持顺序。 */
    private static List<String> literals(String content) {
        List<String> values = new ArrayList<>();
        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '\'') {
                StringBuilder builder = new StringBuilder();
                i++;
                while (i < content.length()) {
                    if (content.charAt(i) == '\'') {
                        if (i + 1 < content.length() && content.charAt(i + 1) == '\'') {
                            builder.append('\'');
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    builder.append(content.charAt(i));
                    i++;
                }
                values.add(builder.toString());
            } else if (Character.isLetterOrDigit(c) || c == '_') {
                int start = i;
                while (i < content.length()
                        && (Character.isLetterOrDigit(content.charAt(i))
                                || content.charAt(i) == '_')) {
                    i++;
                }
                values.add(content.substring(start, i));
            } else {
                i++;
            }
        }
        return values;
    }
}
