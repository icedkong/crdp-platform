package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * E23-T03 核对（单元）：心脏超声映射预置迁移 V64 与医院 A 规格「关键字段 · 心脏超声报告单映射」逐项一致 （验收原文：10 类资料类型与映射可用；10 类随 E16-T01
 * V62 落地，本任务补映射配置）。
 *
 * <p>基准是 docs/requirements/hospital-a-spec.md（A-ATT-012 指标模板、A-ATT-013 映射表： 术前最后一次 / 出院前最后一次 / 第 N
 * 年随访三列，附带写入「复查超声 = 是、超声检查时间 = 报告时间」「同时填有无…返流」） 与 tech-plan §10（rpt_mapping / rpt_mapping_item
 * 表设计：目标、项目代码 → 字段键、附带写入）， 比对对象是随应用一起发布的迁移脚本
 * db/tenant/followup/V64__rpt_mapping_echo_preset.sql：不依赖数据库，CI 与本地必跑； 目标字段键与 V38 字段字典（E23-T01 的
 * 266 个字段）交叉核对，杜绝悬空引用；落库后的同一口径由 {@code ReportConfigIT}（门控 CRDP_IT_MYSQL_PORT）经 HTTP 再核一遍。
 */
class APresetEchoMappingSqlTest {

    private static final String PRESET = "db/tenant/followup/V64__rpt_mapping_echo_preset.sql";
    private static final String FIELD_DICT = "db/tenant/followup/V38__hospital_a_field_dict.sql";
    private static final String REPORT_TYPES =
            "db/tenant/followup/V62__rpt_type_name_indicator.sql";

    /** fe_field 逐行元组锚点：('表单', '字段键', '字段编码', …）。 */
    private static final Pattern FIELD_ROW = Pattern.compile("^\\('([A-Za-z0-9_]+)', '(f\\d+)',");

    private static String presetSql;
    private static Map<String, List<String>> mappingRows;
    private static Map<String, Map<String, List<String>>> itemsByMapping;
    private static List<List<String>> tplItems;
    private static String tplInsert;
    private static Map<String, Set<String>> fieldKeysByForm;

    @BeforeAll
    static void parse() throws IOException {
        presetSql = read(PRESET);
        mappingRows = new LinkedHashMap<>();
        for (List<String> row : selectRows(section(presetSql, "INSERT INTO rpt_mapping (code,"))) {
            mappingRows.put(row.get(0), row);
        }
        itemsByMapping = new LinkedHashMap<>();
        for (List<String> row : selectRows(section(presetSql, "INSERT INTO rpt_mapping_item"))) {
            itemsByMapping
                    .computeIfAbsent(row.get(0), key -> new LinkedHashMap<>())
                    .put(row.get(1), row);
        }
        tplInsert = section(presetSql, "INSERT INTO rpt_indicator_tpl");
        tplItems = selectRows(section(presetSql, "INSERT INTO rpt_indicator_item"));
        fieldKeysByForm = fieldKeys(read(FIELD_DICT));
    }

    /** A-ATT-013：三条映射目标（基线 · 术前最后一次 / 基线 · 出院前最后一次 / 第 N 年随访），类型都是心脏超声。 */
    @Test
    void threeMappingTargetsMatchSpec() {
        assertEquals(
                Set.of("ECHO_PREOP_LAST", "ECHO_PREDISCHARGE_LAST", "ECHO_FOLLOWUP"),
                mappingRows.keySet(),
                "A 规格映射表的三个目标列各成一条映射");

        List<String> preop = mappingRows.get("ECHO_PREOP_LAST");
        assertEquals("ECHO", preop.get(2), "来源类型 = 心脏超声（rpt_type.code）");
        assertEquals("BASELINE", preop.get(3), "目标 = 基线");
        assertEquals("A_BASELINE", preop.get(4), "基线表单（V38 字段字典）");
        assertEquals("PREOP_LAST", preop.get(5), "时相 = 术前最后一次（映射到基线时上传侧选时相）");

        List<String> predischarge = mappingRows.get("ECHO_PREDISCHARGE_LAST");
        assertEquals("BASELINE", predischarge.get(3));
        assertEquals("A_BASELINE", predischarge.get(4));
        assertEquals("PREDISCHARGE_LAST", predischarge.get(5), "时相 = 出院前最后一次");

        List<String> followup = mappingRows.get("ECHO_FOLLOWUP");
        assertEquals("FOLLOWUP_RECORD", followup.get(3), "目标 = 随访记录（写入该年复查超声字段）");
        assertEquals("A_ANNUAL_FU", followup.get(4), "年度随访表单（V38 字段字典）");
        assertEquals("NULL", followup.get(5), "随访目标无时相（写入映射目标记录的复查超声分组）");
        assertEquals("10", mappingRows.get("ECHO_PREOP_LAST").get(7), "排序：术前 → 出院前 → 随访");
        assertEquals("30", followup.get(7), "随访映射排第三");
        assertEquals("ENABLED", followup.get(8), "预置映射启用（A-ATT-018 按租户配置可停用）");
    }

    /** 映射可用性硬闸门：每个目标字段键都在 V38 字段字典的目标表单内（映射写入随 E16-T07 消费本配置）。 */
    @Test
    void everyTargetFieldKeyExistsInFieldDict() {
        assertTrue(mappingRows.size() == 3, "先决：三条映射已解析");
        int checked = 0;
        for (Map.Entry<String, List<String>> mapping : mappingRows.entrySet()) {
            String targetForm = mapping.getValue().get(4);
            Set<String> keys = fieldKeysByForm.get(targetForm);
            assertNotNull(keys, "目标表单 " + targetForm + " 在 V38 字段字典中存在");
            for (List<String> item : itemsByMapping.get(mapping.getKey()).values()) {
                assertTrue(
                        keys.contains(item.get(2)),
                        "映射 "
                                + mapping.getKey()
                                + " 的项目 "
                                + item.get(1)
                                + " 指向字段 "
                                + item.get(2)
                                + "，该字段键不在 "
                                + targetForm
                                + " 字段字典内");
                checked++;
            }
        }
        assertTrue(checked >= 55, "三条映射合计至少 55 个项目行（19 + 17 + 19）");
    }

    /** A-ATT-013 映射表逐行核对：项目行数与关键行（验收示例 EF、报告时间）目标字段一致。 */
    @Test
    void mappingItemsMatchSpecColumns() {
        Map<String, List<String>> preop = itemsByMapping.get("ECHO_PREOP_LAST");
        Map<String, List<String>> predischarge = itemsByMapping.get("ECHO_PREDISCHARGE_LAST");
        Map<String, List<String>> followup = itemsByMapping.get("ECHO_FOLLOWUP");

        assertEquals(19, preop.size(), "术前最后一次：报告时间 + 18 个指标项目（A 规格映射表第一列全有）");
        assertEquals(17, predischarge.size(), "出院前最后一次：无报告时间（规格「—（无对应字段，只存于报告单）」）、无 IVS（规格「—」）");
        assertEquals(19, followup.size(), "第 N 年随访：报告时间 + 18 个指标项目");

        // 报告时间行（验收「超声检查时间等于报告时间」「术前最后一次超声时间」）
        assertEquals("f079", preop.get("REPORT_DATE").get(2), "报告时间 → 术前最后一次超声时间（f079）");
        assertNull(predischarge.get("REPORT_DATE"), "出院前列无报告时间行（规格「—」）");
        assertEquals("f237", followup.get("REPORT_DATE").get(2), "报告时间 → 超声检查时间（f237）");

        // 验收示例：EF 45 → 第 2 年随访「EF 射血分数」；出院前最后一次 EF
        assertEquals("f083", preop.get("EF").get(2), "EF → 术前最后一次超声EF值（f083）");
        assertEquals("f102", predischarge.get("EF").get(2), "EF → 出院前最后一次EF（f102）");
        assertEquals("f248", followup.get("EF").get(2), "EF → EF 射血分数（f248）");

        // 其余指标项目逐条（三列各 18 / 16 / 18 个）
        assertTarget(preop, "LVDD", "f084");
        assertTarget(preop, "LVEDV", "f085");
        assertTarget(preop, "LVDS", "f086");
        assertTarget(preop, "LVESV", "f087");
        assertTarget(preop, "LA_DIAMETER", "f080");
        assertTarget(preop, "LA_VOLUME", "f081");
        assertTarget(preop, "IVS_THICKNESS", "f082");
        assertTarget(preop, "RV_DIAMETER", "f092");
        assertTarget(preop, "SV", "f088");
        assertTarget(preop, "FS", "f089");
        assertTarget(preop, "CO", "f090");
        assertTarget(preop, "E_E_PRIME", "f091");
        assertTarget(preop, "MR_DEGREE", "f097");
        assertTarget(preop, "TR_DEGREE", "f098");
        assertTarget(preop, "AR_DEGREE", "f099");
        assertTarget(preop, "ANEURYSM_SIZE", "f093");
        assertTarget(preop, "VENTRICULAR_THROMBUS", "f094");

        assertNull(predischarge.get("IVS_THICKNESS"), "出院前无室间隔厚度字段（规格「—」）");
        assertTarget(predischarge, "LVDD", "f103");
        assertTarget(predischarge, "LVEDV", "f104");
        assertTarget(predischarge, "LVDS", "f105");
        assertTarget(predischarge, "LVESV", "f106");
        assertTarget(predischarge, "LA_DIAMETER", "f100");
        assertTarget(predischarge, "LA_VOLUME", "f101");
        assertTarget(predischarge, "RV_DIAMETER", "f107");
        assertTarget(predischarge, "SV", "f108");
        assertTarget(predischarge, "FS", "f109");
        assertTarget(predischarge, "CO", "f110");
        assertTarget(predischarge, "E_E_PRIME", "f111");
        assertTarget(predischarge, "MR_DEGREE", "f114");
        assertTarget(predischarge, "TR_DEGREE", "f115");
        assertTarget(predischarge, "AR_DEGREE", "f116");
        assertTarget(predischarge, "ANEURYSM_SIZE", "f112");
        assertTarget(predischarge, "VENTRICULAR_THROMBUS", "f113");

        assertTarget(followup, "LVDD", "f249");
        assertTarget(followup, "LVEDV", "f250");
        assertTarget(followup, "LVDS", "f251");
        assertTarget(followup, "LVESV", "f252");
        assertTarget(followup, "LA_DIAMETER", "f245");
        assertTarget(followup, "LA_VOLUME", "f246");
        assertTarget(followup, "IVS_THICKNESS", "f247");
        assertTarget(followup, "RV_DIAMETER", "f244");
        assertTarget(followup, "SV", "f253");
        assertTarget(followup, "FS", "f254");
        assertTarget(followup, "CO", "f255");
        assertTarget(followup, "E_E_PRIME", "f256");
        assertTarget(followup, "MR_DEGREE", "f239");
        assertTarget(followup, "TR_DEGREE", "f243");
        assertTarget(followup, "AR_DEGREE", "f241");
        assertTarget(followup, "ANEURYSM_SIZE", "f257");
        assertTarget(followup, "VENTRICULAR_THROMBUS", "f258");
    }

    /** A-ATT-013「心脏超声的指标模板与超声字段一一对应」：模板项目 = 三列映射项目的并集（REPORT_DATE 是报告时间伪项目、不在模板内）。 */
    @Test
    void templateItemsOneToOneWithMappingItems() {
        assertTrue(tplInsert.contains("'心脏超声指标'"), "预置指标模板「心脏超声指标」（A-ATT-012 模板由租户管理员维护）");
        Set<String> templateCodes = new LinkedHashSet<>();
        for (List<String> item : tplItems) {
            assertEquals("心脏超声指标", item.get(0), "预置项目都属于心脏超声指标模板");
            templateCodes.add(item.get(2));
        }
        Set<String> mappingCodes = new LinkedHashSet<>();
        for (Map<String, List<String>> items : itemsByMapping.values()) {
            items.keySet().stream()
                    .filter(code -> !"REPORT_DATE".equals(code))
                    .forEach(mappingCodes::add);
        }
        assertEquals(templateCodes, mappingCodes, "模板项目与映射项目一一对应（REPORT_DATE 报告时间除外，报告单自身的报告日期字段）");
        assertEquals(18, templateCodes.size(), "18 个指标项目（A 规格映射表的项目列）");
    }

    /** A-ATT-013 附带写入：随访目标「复查超声 = 是」（f236=1）与瓣膜返流程度「同时填有无…返流」（f238 / f242 / f240 = 1）。 */
    @Test
    void followupSideWritesPresent() {
        String followupExtra = mappingRows.get("ECHO_FOLLOWUP").get(6);
        assertNotNull(followupExtra, "随访映射附带写入不缺省");
        assertTrue(followupExtra.contains("\"fieldKey\":\"f236\""), "复查超声（f236）置为是");
        assertTrue(followupExtra.contains("\"value\":\"1\""), "a_yesno 取值 1 = 是（V38 选项集）");

        assertItemExtra("ECHO_FOLLOWUP", "MR_DEGREE", "f238", "二尖瓣");
        assertItemExtra("ECHO_FOLLOWUP", "TR_DEGREE", "f242", "三尖瓣");
        assertItemExtra("ECHO_FOLLOWUP", "AR_DEGREE", "f240", "主动脉瓣");
    }

    /** A-ATT-012 / A-ATT-013：心脏超声名称绑定指标模板（V62 预置的三个名称），添加报告单时按模板带出项目。 */
    @Test
    void echoNamesBoundToEchoTemplate() throws IOException {
        assertTrue(
                presetSql.contains("UPDATE rpt_name"), "用迁移把 V62 预置的心脏超声名称绑定到心脏超声指标模板（新增脚本、不改旧脚本）");
        assertTrue(presetSql.contains("n.type_code = 'ECHO'"), "只绑定心脏超声类型的名称");
        String v62 = read(REPORT_TYPES);
        for (String name : List.of("经胸超声心动图", "床旁超声", "经食道超声")) {
            assertTrue(v62.contains("'" + name + "'"), "V62 预置名称仍在（" + name + "），绑定在其上");
        }
    }

    // ---------------------------------------------------------------- 解析

    private static void assertTarget(
            Map<String, List<String>> items, String code, String fieldKey) {
        List<String> row = items.get(code);
        assertNotNull(row, "缺少项目行 " + code);
        assertEquals(fieldKey, row.get(2), code + " 的目标字段键");
    }

    private static void assertItemExtra(
            String mappingCode, String code, String fieldKey, String label) {
        List<String> row = itemsByMapping.get(mappingCode).get(code);
        assertNotNull(row, mappingCode + " 缺少项目 " + code);
        String extra = row.get(3);
        assertNotNull(extra, label + "返流程度写入时同时填「有无" + label + "返流」（A 规格映射表第 N 年随访列）");
        assertTrue(
                extra.contains("\"fieldKey\":\"" + fieldKey + "\""),
                label + "返流的附带写入字段 " + fieldKey);
        assertTrue(extra.contains("\"value\":\"1\""), "置为「有」（a_yesno 1 = 是）");
    }

    private static String read(String path) throws IOException {
        try (InputStream in =
                APresetEchoMappingSqlTest.class.getClassLoader().getResourceAsStream(path)) {
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

    /**
     * 解析 INSERT … SELECT … UNION ALL 形态的种子行（与 V62 预置同形态）：只取 首列为引号字面量的 SELECT 行——外层 SELECT 的列引用（t.id
     * / v.code 等裸词）不是种子行，跳过。
     */
    private static List<List<String>> selectRows(String sql) {
        List<List<String>> rows = new ArrayList<>();
        for (String line : sql.split("\\n")) {
            String trimmed = line.trim();
            String prefix = "SELECT ";
            int at = trimmed.indexOf(prefix);
            if (at >= 0 && trimmed.charAt(at + prefix.length()) == '\'') {
                int offset = at + prefix.length() + 1;
                String columns =
                        ("'" + trimmed.substring(offset))
                                .replaceAll("\\s+AS\\s+\\w+", "")
                                .replaceFirst(",$", "");
                rows.add(literals(columns));
            }
        }
        assertTrue(!rows.isEmpty(), "SELECT 预置行解析出列");
        return rows;
    }

    /** 逐字符拆单引号字符串与裸词（NULL / 数字，'' 转义保留为 '），保持顺序。 */
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
                        && (Character.isLetterOrDigit(content.charAt(i)) || c == '_')) {
                    i++;
                }
                values.add(content.substring(start, i));
            } else {
                i++;
            }
        }
        return values;
    }

    /** V38 字段字典：表单 → 字段键集合（fe_field 逐行元组首两列）。 */
    private static Map<String, Set<String>> fieldKeys(String dictSql) {
        Map<String, Set<String>> keys = new LinkedHashMap<>();
        for (String line : dictSql.split("\\n")) {
            Matcher matcher = FIELD_ROW.matcher(line.trim());
            if (matcher.find()) {
                keys.computeIfAbsent(matcher.group(1), form -> new LinkedHashSet<>())
                        .add(matcher.group(2));
            }
        }
        assertTrue(keys.containsKey("A_BASELINE"), "V38 含基线表单字段");
        assertTrue(keys.containsKey("A_ANNUAL_FU"), "V38 含年度随访表单字段");
        return keys;
    }
}
