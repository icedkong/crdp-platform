package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * E23-T21 核对（单元）：报告单类型与程控报告单映射预置迁移 V94 与医院 C 规格 §6「报告单与纸质资料归档」逐项一致 （验收原文：程控报告单可映射字段；C-SCAN-002 /
 * C-SCAN-003 / C-SCAN-005 / C-SCAN-011）。
 *
 * <p>基准是 docs/requirements/hospital-c-spec.md §6（预置资料类型表的类型 / 主要字段 / 名称字典示例 / 默认电子化状态、 指标模板示例「检验 ·
 * 凝血四项」、「程控报告单模板映射示例」表：电池、起搏阈值与脉宽、感知、阻抗、起搏比例、程控参数、 ICD 项目共 21 项），比对对象是随应用一起发布的迁移脚本
 * db/tenant/followup/V94__hospital_c_report_preset.sql： 不依赖数据库，CI 与本地必跑；程控映射的目标字段键与 V90
 * 字段字典（E23-T20 的 C_PROGRAM_FU）交叉核对，杜绝悬空引用； 落库后的同一口径由 {@code ReportConfigIT}（门控 CRDP_IT_MYSQL_PORT）经
 * HTTP 再核一遍。
 *
 * <p>随包载体口径（OQ-142）：C 的 10 类中「通话记录」「其他」复用 A 预置 CALL / OTHER（编码被 {@code
 * CallReportService.CALL_TYPE_CODE} 与前端 report-dialog-logic 契约固定，字段集亦相同），其余 8 类本院预置 以
 * <b>DISABLED</b> 随包进所有租户库——对医院 A / B 惰性（添加报告单不可选，catalogTypes 只出 ENABLED），医院 C 侧按 runbook
 * docs/ops/hospital-c-report-preset.md 激活（同 OQ-128② 计划草稿的库内数据动作口径，不写租户分支）。
 */
class CReportPresetSqlTest {

    private static final String PRESET = "db/tenant/followup/V94__hospital_c_report_preset.sql";

    private static final String REPORT_TYPES =
            "db/tenant/followup/V62__rpt_type_name_indicator.sql";

    private static final String FIELD_DICT = "db/tenant/followup/V90__hospital_c_field_dict.sql";

    /** fe_field / V90 字段字典逐行元组锚点：('表单', '字段键', '字段编码', …）。 */
    private static final Pattern FIELD_ROW = Pattern.compile("^\\('([A-Za-z0-9_]+)', '(f\\d+)',");

    private static final Pattern TUPLE = Pattern.compile("\\(([^()]*(?:'[^']*'[^()]*)*)\\)");

    private static String presetSql;

    private static Map<String, List<String>> typeRows;

    private static List<List<String>> feForms;

    private static Map<String, List<Map<String, String>>> fieldsByForm;

    private static List<List<String>> tplRows;

    private static Map<String, Map<String, List<String>>> itemsByTpl;

    private static Map<String, List<List<String>>> nameRowsByType;

    private static Map<String, List<String>> mappingRows;

    private static Map<String, List<String>> mappingItems;

    /** V90 的 C_PROGRAM_FU 字段字典：field_code → 整行字面量（type=5、unit=6、option_set=10、dict=11）。 */
    private static Map<String, List<String>> programFuByCode;

    @BeforeAll
    static void parse() throws IOException {
        presetSql = read(PRESET);
        typeRows = tuples(presetSql, "INSERT INTO rpt_type");
        feForms = valuesTuples(presetSql, "INSERT INTO fe_form");
        fieldsByForm = new LinkedHashMap<>();
        for (List<String> row : valuesTuples(presetSql, "INSERT INTO fe_field")) {
            fieldsByForm
                    .computeIfAbsent(row.get(0), key -> new ArrayList<>())
                    .add(mapOf(new String[] {"code", "label", "type"}, row, 2));
        }
        tplRows = valuesTuples(presetSql, "INSERT INTO rpt_indicator_tpl");
        itemsByTpl = new LinkedHashMap<>();
        for (List<String> row : selectRows(section(presetSql, "INSERT INTO rpt_indicator_item"))) {
            // SELECT 行：tpl_name, item_name, item_code, unit, sort_no
            itemsByTpl
                    .computeIfAbsent(row.get(0), key -> new LinkedHashMap<>())
                    .put(row.get(2), row);
        }
        nameRowsByType = new LinkedHashMap<>();
        for (List<String> row : selectRows(section(presetSql, "INSERT INTO rpt_name"))) {
            // SELECT 行：type_code, name, tpl_name, override, sort_no
            nameRowsByType.computeIfAbsent(row.get(0), key -> new ArrayList<>()).add(row);
        }
        mappingRows = new LinkedHashMap<>();
        for (List<String> row : selectRows(section(presetSql, "INSERT INTO rpt_mapping (code,"))) {
            // SELECT 行：code, name, type_code, target_type, target_form_code, phase, tpl_name,
            // extra_writes, sort_no, status, remark
            mappingRows.put(row.get(0), row);
        }
        mappingItems = new LinkedHashMap<>();
        for (List<String> row : selectRows(section(presetSql, "INSERT INTO rpt_mapping_item"))) {
            // SELECT 行：mapping_code, item_code, target_field_key, sort_no
            mappingItems.put(row.get(1), row);
        }
        programFuByCode = new LinkedHashMap<>();
        for (String line : read(FIELD_DICT).split("\\n")) {
            Matcher matcher = FIELD_ROW.matcher(line.trim());
            if (matcher.find() && "C_PROGRAM_FU".equals(matcher.group(1))) {
                Matcher tuple = TUPLE.matcher(line.trim());
                if (tuple.find()) {
                    List<String> literals = literals(tuple.group(1));
                    programFuByCode.put(literals.get(2), literals);
                }
            }
        }
        assertTrue(programFuByCode.containsKey("battery_voltage"), "V90 含 C_PROGRAM_FU 字段字典");
    }

    // ---------------------------------------------------------------- 类型（C-SCAN-002 / 003 / 011）

    /** C 规格 §6 预置资料类型表：8 类本院预置随包 DISABLED（OQ-142 惰性口径），名称必填与默认电子化状态逐项一致。 */
    @Test
    void presetTypesMatchSpec() {
        String[][] expected = {
            {"C_PROGRAM", "程控报告单", "RPT_C_PROGRAM", "0", "PENDING"},
            {"C_PAPER_FORM", "纸质随访表", "RPT_C_PAPER", "0", "PENDING"},
            {"C_IMPLANT_RECORD", "植入手术记录", "RPT_C_IMPLANT", "0", "PENDING"},
            {"C_ECG", "心电图/动态心电图", "RPT_C_ECG", "1", "NO_ENTRY"},
            {"C_CHEST", "胸片", "RPT_C_CHEST", "1", "NO_ENTRY"},
            {"C_ECHO", "心脏超声", "RPT_C_ECHO", "1", "NO_ENTRY"},
            {"C_LAB", "检验", "RPT_C_LAB", "1", "NO_ENTRY"},
            {"C_CONSENT", "知情同意书", "RPT_C_CONSENT", "0", "NO_ENTRY"},
        };
        assertEquals(8, typeRows.size(), "8 类本院预置（通话记录 / 其他复用 A 预置，不重复插入）");
        int index = 0;
        for (Map.Entry<String, List<String>> row : typeRows.entrySet()) {
            List<String> values = row.getValue();
            assertEquals(expected[index][0], values.get(0), "类型编码（第 " + (index + 1) + " 类）");
            assertEquals(expected[index][1], values.get(1), "类型名称（C 规格预置资料类型表）");
            assertEquals(expected[index][2], values.get(2), "字段集引用的 REPORT 表单");
            assertEquals(
                    expected[index][3],
                    values.get(3),
                    "名称必填（C-SCAN-003：检查、检验类必填，其他类型选填）：" + expected[index][1]);
            assertEquals(
                    expected[index][4], values.get(4), "默认电子化状态（C-SCAN-011）：" + expected[index][1]);
            assertEquals(
                    String.valueOf((index + 11) * 10),
                    values.get(5),
                    "排序接在 A 预置 10–100 之后（110–180）");
            assertEquals("DISABLED", values.get(6), "随包停用：对 A / B 惰性，C 侧 runbook 激活（OQ-142）");
            assertTrue(values.get(7).contains("医院 C 预置"), "remark 标注本院预置来源：" + values.get(0));
            index++;
        }
    }

    /** 「通话记录」「其他」复用 A 预置 CALL / OTHER：V94 不重复插入，V62 原行保持（编码为前后端契约）。 */
    @Test
    void callAndOtherReuseAPreset() throws IOException {
        assertFalse(typeRows.containsKey("CALL"), "通话记录复用 A 预置 CALL，不另建类型行");
        assertFalse(typeRows.containsKey("OTHER"), "其他复用 A 预置 OTHER，不另建类型行");
        assertTrue(
                presetSql.contains("复用 A 预置 CALL"),
                "脚本说明复用口径（CallReportService.CALL_TYPE_CODE / report-dialog-logic 契约）");
        assertTrue(presetSql.contains("复用 A 预置 OTHER"), "脚本说明「其他」复用口径");
        String v62 = read(REPORT_TYPES);
        assertTrue(v62.contains("('CALL', '通话记录'"), "V62 的 CALL 类型行仍在（编码不改）");
        assertTrue(v62.contains("('OTHER', '其他'"), "V62 的 OTHER 类型行仍在（编码不改）");
    }

    /** C-SCAN-002：每类一个 REPORT 表单 + 类型专属字段（主要字段列；所见 / 诊断走 rpt_report 公共列的槽位编码）。 */
    @Test
    void typeFormsAndFieldSetsMatchSpec() {
        Set<String> formCodes = new LinkedHashSet<>();
        for (List<String> form : feForms) {
            assertEquals("REPORT", form.get(2), "fe_form.usage_type = REPORT（字段集载体）");
            formCodes.add(form.get(0));
        }
        assertEquals(
                Set.of(
                        "RPT_C_PROGRAM",
                        "RPT_C_PAPER",
                        "RPT_C_IMPLANT",
                        "RPT_C_ECG",
                        "RPT_C_CHEST",
                        "RPT_C_ECHO",
                        "RPT_C_LAB",
                        "RPT_C_CONSENT"),
                formCodes,
                "8 个 REPORT 表单与类型一一对应");
        for (List<String> type : typeRows.values()) {
            assertTrue(formCodes.contains(type.get(2)), "类型 " + type.get(0) + " 的字段集表单存在");
        }
        // 主要字段列逐项（时间 = report_date 槽位；检查所见 / 检查诊断 / 说明走公共槽位编码 findings / conclusion / note）
        assertFields("RPT_C_PROGRAM", "report_date:时间", "hospital:医院", "brand:品牌");
        assertFields("RPT_C_PAPER", "report_date:时间");
        assertFields("RPT_C_IMPLANT", "report_date:时间", "hospital:医院", "summary:手术记录摘要");
        assertFields(
                "RPT_C_ECG",
                "report_date:时间",
                "hospital:医院",
                "result:测量结果",
                "findings:检查所见",
                "conclusion:检查诊断");
        assertFields(
                "RPT_C_CHEST",
                "report_date:时间",
                "hospital:医院",
                "part:检查部位",
                "findings:检查所见",
                "conclusion:检查诊断");
        assertFields(
                "RPT_C_ECHO",
                "report_date:时间",
                "hospital:医院",
                "result:测量结果",
                "findings:检查所见",
                "conclusion:检查诊断");
        assertFields("RPT_C_LAB", "report_date:时间", "hospital:医院", "device:设备仪器");
        assertFields("RPT_C_CONSENT", "report_date:时间", "sign_date:签署时间", "sign_place:签署地点");
        // 写入闸门需要模板版本快照（writeGateData 按版本取字段；V90 同款 v1 快照）
        String snapshot = section(presetSql, "INSERT INTO fe_form_version");
        for (String code : formCodes) {
            assertTrue(snapshot.contains("'" + code + "'"), "表单 " + code + " 建 v1 版本快照");
        }
        assertTrue(
                presetSql.contains("INSERT INTO fe_form_version_field"),
                "快照含字段行（fe_form_version_field）");
    }

    // ---------------------------------------------------------------- 名称与模板（C-SCAN-003 / 004）

    /** C 规格 §6 预置资料类型表的名称字典示例逐项；出院小结 / 起搏器识别卡默认无需录入（名称覆盖）。 */
    @Test
    void namesMatchSpecWithDigitizeOverrides() {
        assertNames(
                "C_PROGRAM",
                new String[][] {
                    {"常规程控", "程控报告单指标", "NULL"},
                    {"术中测试", "程控报告单指标", "NULL"},
                    {"ERI 评估", "程控报告单指标", "NULL"}
                });
        assertNames(
                "C_PAPER_FORM",
                new String[][] {{"随访程控记录单", "NULL", "NULL"}, {"患者登记表", "NULL", "NULL"}});
        assertNames(
                "C_IMPLANT_RECORD",
                new String[][] {
                    {"手术记录", "NULL", "NULL"},
                    {"出院小结", "NULL", "NO_ENTRY"},
                    {"起搏器识别卡", "NULL", "NO_ENTRY"}
                });
        assertNames(
                "C_ECG",
                new String[][] {{"常规心电图", "NULL", "NULL"}, {"动态心电图（Holter）", "NULL", "NULL"}});
        assertNames(
                "C_CHEST", new String[][] {{"胸部正位片", "NULL", "NULL"}, {"胸部正侧位片", "NULL", "NULL"}});
        assertNames("C_ECHO", new String[][] {{"经胸心脏超声", "NULL", "NULL"}});
        assertNames(
                "C_LAB",
                new String[][] {
                    {"血常规", "NULL", "NULL"},
                    {"凝血四项", "凝血四项", "NULL"},
                    {"肝功能", "NULL", "NULL"},
                    {"肾功能", "NULL", "NULL"},
                    {"电解质", "NULL", "NULL"},
                    {"NT-proBNP", "NULL", "NULL"}
                });
        assertNames(
                "C_CONSENT",
                new String[][] {{"植入知情同意书", "NULL", "NULL"}, {"程控知情同意书", "NULL", "NULL"}});
        assertNull(nameRowsByType.get("CALL"), "通话记录名称（随访电话 / 失访追踪）由 C 侧 runbook 补（共享 A 行）");
        assertNull(nameRowsByType.get("OTHER"), "「其他」不预置名称（规格表名称字典示例「—」）");
        assertEquals(
                21, nameRowsByType.values().stream().mapToInt(List::size).sum(), "8 类共 21 个预置名称行");
        assertTrue(
                presetSql.contains("LEFT JOIN rpt_indicator_tpl t ON t.name = v.tpl_name"),
                "名称经 LEFT JOIN 绑定指标模板（未绑名称 tpl 为 NULL）");
    }

    /** C-SCAN-004：检验指标模板示例「凝血四项」六项（项目名称、代码、单位；参考范围以本院检验科为准、不预置）。 */
    @Test
    void coagulationTemplateMatchesSpec() {
        List<String> tplNames = tplRows.stream().map(row -> row.get(0)).toList();
        assertEquals(List.of("凝血四项", "程控报告单指标"), tplNames, "两个预置指标模板");
        Map<String, List<String>> items = itemsByTpl.get("凝血四项");
        assertEquals(6, items.size(), "凝血四项六项（C 规格指标模板示例表）");
        String[][] expected = {
            {"PT", "凝血酶原时间", "s"},
            {"PT%", "凝血酶原活动度", "%"},
            {"INR", "国际标准化比值", "NULL"},
            {"APTT", "活化部分凝血活酶时间", "s"},
            {"TT", "凝血酶时间", "s"},
            {"Fbg", "纤维蛋白原", "g/L"},
        };
        for (String[] row : expected) {
            List<String> item = items.get(row[0]);
            assertNotNull(item, "缺少项目 " + row[0]);
            assertEquals(row[1], item.get(1), row[0] + " 项目名称");
            assertEquals(row[2], item.get(3), row[0] + " 单位");
        }
        assertTrue(tplRows.get(0).get(2).contains("以本院检验科为准"), "参考范围不预置（C 规格示例表注；租户管理员按本院实际维护）");
    }

    // ---------------------------------------------------------------- 程控映射（C-SCAN-005）

    /** 映射头：程控报告单 → 随访程控记录（C_PROGRAM_FU），无时相、绑定程控指标模板、启用。 */
    @Test
    void programMappingRowMatchesSpec() {
        assertEquals(Set.of("C_PROGRAM_TO_FU"), mappingRows.keySet(), "一条程控映射（映射示例表）");
        List<String> mapping = mappingRows.get("C_PROGRAM_TO_FU");
        assertEquals("程控报告单 → 随访程控记录", mapping.get(1), "映射名称");
        assertEquals("C_PROGRAM", mapping.get(2), "来源类型 = 程控报告单");
        assertEquals("FOLLOWUP_RECORD", mapping.get(3), "目标 = 随访记录（写入映射目标记录，报告单不另存参数）");
        assertEquals("C_PROGRAM_FU", mapping.get(4), "目标表单 = 随访程控记录（V90 字段字典）");
        assertEquals("NULL", mapping.get(5), "随访目标无时相");
        assertEquals("程控报告单指标", mapping.get(6), "绑定程控指标模板（项目与映射一一对应）");
        assertEquals("NULL", mapping.get(7), "无映射级附带写入（映射示例表未列）");
        assertEquals("ENABLED", mapping.get(9), "映射启用（类型停用对 A / B 已惰性：无该类型报告单）");
        assertTrue(mapping.get(10).contains("C-SCAN-005"), "remark 标注需求来源");
    }

    /**
     * 映射示例表 21 项逐项：电池 3、起搏阈值与脉宽 4、感知 2、阻抗 2、起搏比例 2、程控参数 4、ICD 项目 4； 无 REPORT_DATE 行（随访日期 f303 由转录 /
     * 补录流程落，OQ-131②）；目标字段键全部在 V90 的 C_PROGRAM_FU 内。
     */
    @Test
    void mappingItemsMatchSpecExampleTable() {
        String[][] expected = {
            {"battery_voltage", "f322"},
            {"eri_status", "f323"},
            {"battery_life", "f324"},
            {"ra_threshold", "f308"},
            {"ra_pulse_width", "f309"},
            {"rv_threshold", "f313"},
            {"rv_pulse_width", "f314"},
            {"ra_sensing", "f310"},
            {"rv_sensing", "f315"},
            {"ra_impedance", "f311"},
            {"rv_impedance", "f316"},
            {"ra_pacing_ratio", "f312"},
            {"rv_pacing_ratio", "f317"},
            {"programmed_mode", "f318"},
            {"lower_rate", "f319"},
            {"upper_rate", "f320"},
            {"av_delay", "f321"},
            {"charge_time", "f340"},
            {"hv_impedance", "f341"},
            {"icd_appropriate", "f329"},
            {"icd_inappropriate", "f330"},
        };
        assertEquals(
                21, mappingItems.size(), "映射示例表 21 项（电池 / 阈值脉宽 / 感知 / 阻抗 / 起搏比例 / 程控参数 / ICD）");
        Set<String> templateCodes = itemsByTpl.get("程控报告单指标").keySet();
        assertEquals(
                Set.copyOf(List.of(expected).stream().map(row -> row[0]).toList()),
                mappingItems.keySet(),
                "映射项目与映射示例表逐项一致");
        assertEquals(templateCodes, mappingItems.keySet(), "模板项目与映射项目一一对应（电池电压等 21 项）");
        assertNull(mappingItems.get("REPORT_DATE"), "无报告时间行（OQ-131②：随访日期由转录流程落，不随参数写入）");
        int sort = 0;
        for (String[] row : expected) {
            List<String> item = mappingItems.get(row[0]);
            assertNotNull(item, "缺少项目 " + row[0]);
            assertEquals(row[1], item.get(2), row[0] + " 的目标字段键（V90 键位契约）");
            assertEquals(String.valueOf((sort + 1) * 10), item.get(3), row[0] + " 排序（示例表行序）");
            List<String> v90 = programFuByCode.get(row[0]);
            assertNotNull(v90, row[0] + " 的目标字段在 V90 的 C_PROGRAM_FU 内（field_code 同名）");
            assertEquals(row[1], v90.get(1), "目标字段键与 V90 一致（" + row[0] + "）");
            sort++;
        }
    }

    /** 目标字段类型核对（V90）：数值项 number、ERI 状态单选 c_eri_status、起搏模式字典单选、ICD 治疗两项多选。 */
    @Test
    void mappingTargetFieldTypesCompatibleWithV90() {
        for (String code :
                List.of(
                        "battery_voltage",
                        "battery_life",
                        "ra_threshold",
                        "ra_pulse_width",
                        "rv_threshold",
                        "rv_pulse_width",
                        "ra_sensing",
                        "rv_sensing",
                        "ra_impedance",
                        "rv_impedance",
                        "ra_pacing_ratio",
                        "rv_pacing_ratio",
                        "lower_rate",
                        "upper_rate",
                        "av_delay",
                        "charge_time",
                        "hv_impedance")) {
            assertEquals(
                    "number", programFuByCode.get(code).get(5), code + " 目标为数值字段（指标结果转 JSON 数值）");
        }
        List<String> eri = programFuByCode.get("eri_status");
        assertEquals("select", eri.get(5), "ERI 状态为单选");
        assertEquals("c_eri_status", eri.get(10), "ERI 状态选项集 c_eri_status（0-1-2 键契约，OQ-130①）");
        List<String> mode = programFuByCode.get("programmed_mode");
        assertEquals("select", mode.get(5), "程控起搏模式为单选");
        assertEquals("pacing_mode", mode.get(11), "统一起搏模式字典（Q08 / C-FLD-003，字符串编码）");
        for (String code : List.of("icd_appropriate", "icd_inappropriate")) {
            List<String> field = programFuByCode.get(code);
            assertEquals("multiselect", field.get(5), code + " 为多选（仅 ICD、CRT-D 显示）");
        }
        // 模板项目单位与 V90 目标字段单位一致（报告单录入行与程控记录同单位）
        assertEquals("V", itemsByTpl.get("程控报告单指标").get("battery_voltage").get(3));
        assertEquals(
                programFuByCode.get("battery_voltage").get(6),
                itemsByTpl.get("程控报告单指标").get("battery_voltage").get(3),
                "电池电压单位与字段字典一致");
        assertEquals(
                programFuByCode.get("rv_impedance").get(6),
                itemsByTpl.get("程控报告单指标").get("rv_impedance").get(3),
                "心室阻抗单位一致（Ω）");
    }

    // ---------------------------------------------------------------- 解析（与 APreset*SqlTest 同口径）

    private static void assertFields(String form, String... codeLabelPairs) {
        List<Map<String, String>> fields = fieldsByForm.get(form);
        assertNotNull(fields, "表单 " + form + " 有字段行");
        assertEquals(codeLabelPairs.length, fields.size(), form + " 字段数（C 规格主要字段列）: " + fields);
        for (int i = 0; i < codeLabelPairs.length; i++) {
            String[] pair = codeLabelPairs[i].split(":", 2);
            assertEquals(pair[0], fields.get(i).get("code"), form + " 第 " + (i + 1) + " 个字段编码");
            assertEquals(pair[1], fields.get(i).get("label"), form + " 字段「" + pair[0] + "」显示名");
        }
    }

    private static void assertNames(String type, String[][] expected) {
        List<List<String>> rows = nameRowsByType.get(type);
        assertNotNull(rows, type + " 预置名称行存在");
        assertEquals(expected.length, rows.size(), type + " 名称数");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i][0], rows.get(i).get(1), type + " 第 " + (i + 1) + " 个名称");
            assertEquals(
                    expected[i][1], rows.get(i).get(2), expected[i][0] + " 绑定的指标模板（NULL = 不绑定）");
            assertEquals(
                    expected[i][2],
                    rows.get(i).get(3),
                    expected[i][0] + " 电子化状态覆盖（C-SCAN-011 名称可覆盖类型默认）");
        }
    }

    private static Map<String, String> mapOf(String[] keys, List<String> row, int offset) {
        Map<String, String> mapped = new LinkedHashMap<>();
        for (int i = 0; i < keys.length; i++) {
            mapped.put(keys[i], row.get(offset + i));
        }
        return mapped;
    }

    private static String read(String path) throws IOException {
        try (InputStream in =
                CReportPresetSqlTest.class.getClassLoader().getResourceAsStream(path)) {
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

    /** VALUES 多行元组 → 首列为键的有序映射（rpt_type / fe_form 用）。 */
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
        assertTrue(!rows.isEmpty(), "元组解析出列");
        return rows;
    }

    /** 解析 INSERT … SELECT … UNION ALL 形态的种子行：只取首列为引号字面量的 SELECT 行。 */
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
            } else if (Character.isLetterOrDigit(c) || c == '_' || c == '%') {
                int start = i;
                while (i < content.length()
                        && (Character.isLetterOrDigit(content.charAt(i))
                                || content.charAt(i) == '_'
                                || content.charAt(i) == '%')) {
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
