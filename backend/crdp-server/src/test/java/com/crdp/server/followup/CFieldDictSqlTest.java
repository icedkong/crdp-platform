package com.crdp.server.followup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * E23-T20 核对（单元）：迁移 V90 的医院 C 字段字典与规格附录 A「97 个字段」逐项一致（tasks.csv 验收原文「字段顺序与纸质表一致」）。
 *
 * <p>基准是 docs/requirements/hospital-c-spec.md 附录 A（本类内解析），比对对象是随应用一起发布的迁移脚本
 * db/tenant/followup/V90__hospital_c_field_dict.sql：不依赖数据库，CI 与本地必跑。
 *
 * <p>覆盖：
 *
 * <ul>
 *   <li>附录 97 行（患者 21 + 随访 34 + 植入 13 + 电极 6 + 随访新增 7 + 报告 16，规格 §3「字段分组概览」）↔ fe_field 81 行（报告
 *       #1–16 由报告单通用模型 rpt_report〔E16-T02〕与 E23-T21 类型字段集承载，不在 fe_field 重复登记）+ 植入配套 3 行（指征补充说明 /
 *       其他说明 note / 结束原因，OQ-132②、V81 / V83 落列）= 84 行；
 *   <li>键位契约（OQ-130① / OQ-136⑤）：附录随访组 #n → f3nn；f303 编码 followup_date、f305 编码 visit_doctor； ERI
 *       状态选项集 c_eri_status 编码 0=正常 / 1=接近ERI / 2=已达ERI(EOI)；分类锚点 threshold / sensing / impedance /
 *       battery；
 *   <li>字段顺序与纸质表一致（C-FLD-010，验收原文）：患者登记 21 列与随访程控记录 34 列的 sort_no = 纸质表行号 × 10 严格递增（新增字段按逻辑位置插入其间）；
 *   <li>业务字典绑定（OQ-69④ / V89 列）：起搏器类型 / 品牌 / 统一起搏模式 / 植入指征 → 平台库 V70 字典； 带入上次值标记（OQ-67① / C-FU-016）=
 *       程控起搏模式、上下限频率、AV 间期；
 *   <li>选项集（C-FLD-004）：症状 / 心律失常事件 / ICD 恰当与不恰当治疗 / 并发症「无」互斥（Q04 / Q06）；
 *       囊袋/伤口检查「愈合良好」互斥（Q07）；「其他」需补充说明。
 * </ul>
 */
class CFieldDictSqlTest {

    private static final String MIGRATION = "db/tenant/followup/V90__hospital_c_field_dict.sql";

    private static final String CARRIER = "db/tenant/followup/V89__fe_field_dict_binding.sql";

    /** 附录分组（顺序与规格 §3「字段分组概览」一致）。 */
    private static final String[] GROUP_NAMES = {"患者", "随访", "植入", "电极", "随访", "报告"};

    private static final int[] GROUP_SIZES = {21, 34, 13, 6, 7, 16};

    /** 附录行：分组、行号、显示名、类型、取值与说明原文。 */
    private record Row(String group, int no, String label, String appendixType, String spec) {}

    private static final Pattern ROW_RE =
            Pattern.compile(
                    "^\\|\\s*(患者|随访|植入|电极|报告)\\s*#(\\d+)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*([^|]+?)\\s*\\|\\s*([^|]*?)\\s*\\|\\s*$");

    private static List<List<Row>> appendixBlocks;

    private static String sql;

    /** fe_field 行：列名 → 值（NULL 为 null；JSON 列保留原文）。 */
    private static List<Map<String, String>> fieldRows;

    private static Map<String, Map<Integer, String>> optionSets;

    private static Map<String, Map<Integer, Boolean>> exclusiveFlags;

    private static Map<String, Map<Integer, Boolean>> needsDetailFlags;

    @BeforeAll
    static void parse() throws IOException {
        appendixBlocks = parseAppendix();
        sql = read(MIGRATION);
        fieldRows = parseInsertRows(sql, "fe_field");
        optionSets = new LinkedHashMap<>();
        exclusiveFlags = new LinkedHashMap<>();
        needsDetailFlags = new LinkedHashMap<>();
        for (Map<String, String> row : parseInsertRows(sql, "fe_option")) {
            optionSets
                    .computeIfAbsent(row.get("set_code"), k -> new LinkedHashMap<>())
                    .put(Integer.parseInt(row.get("option_code")), row.get("label"));
            exclusiveFlags
                    .computeIfAbsent(row.get("set_code"), k -> new LinkedHashMap<>())
                    .put(
                            Integer.parseInt(row.get("option_code")),
                            "1".equals(row.get("exclusive")));
            needsDetailFlags
                    .computeIfAbsent(row.get("set_code"), k -> new LinkedHashMap<>())
                    .put(
                            Integer.parseInt(row.get("option_code")),
                            "1".equals(row.get("needs_detail")));
        }
    }

    // ---------------------------------------------------------------- 附录 ↔ 字段一对一

    /** 附录 97 行按规格 §3「字段分组概览」切段；报告 16 行由 rpt 模型承载不落 fe_field（81 + 植入配套 3 = 84 行）。 */
    @Test
    void appendixRowsMapToFields() {
        assertEquals(6, appendixBlocks.size(), "附录六个分组");
        for (int i = 0; i < GROUP_NAMES.length; i++) {
            assertEquals(GROUP_NAMES[i], appendixBlocks.get(i).get(0).group(), "分组 " + i);
            assertEquals(GROUP_SIZES[i], appendixBlocks.get(i).size(), "分组 " + i + " 行数（规格 §3 概览）");
        }
        Map<String, List<String>> keysByForm = new LinkedHashMap<>();
        for (Map<String, String> row : fieldRows) {
            keysByForm
                    .computeIfAbsent(row.get("form_code"), k -> new ArrayList<>())
                    .add(row.get("field_key"));
        }
        for (Map.Entry<String, List<String>> entry : keysByForm.entrySet()) {
            assertEquals(
                    entry.getValue().size(),
                    entry.getValue().stream().distinct().count(),
                    entry.getKey() + " 稳定键不应重复");
        }
        assertEquals(84, fieldRows.size(), "fe_field 84 行 = 附录 81（97 − 报告 16）+ 植入配套 3");
        long byForm =
                fieldRows.stream().filter(r -> "C_PROGRAM_FU".equals(r.get("form_code"))).count();
        assertEquals(41, byForm, "C_PROGRAM_FU 41 行 = 随访 34 + 随访新增 7");
        assertEquals(
                21,
                fieldRows.stream().filter(r -> "C_REGISTRATION".equals(r.get("form_code"))).count(),
                "C_REGISTRATION 21 行 = 患者 21");
        assertEquals(
                22,
                fieldRows.stream().filter(r -> "C_IMPLANT".equals(r.get("form_code"))).count(),
                "C_IMPLANT 22 行 = 植入 13 + 配套 3 + 电极 6");
        // 报告 #1–16 不进 fe_field（rpt_report 通用模型 + E23-T21 类型字段集承载）
        assertTrue(
                fieldRows.stream().noneMatch(r -> "C_REPORT".equals(r.get("form_code"))),
                "报告字段不另立 fe_form（由 rpt_report 模型承载，E16-T02 / E23-T21）");
        assertTrue(sql.contains("报告 #1–16 由报告单通用模型"), "迁移注释应说明报告 16 字段的承载口径");
    }

    /** 附录行 → 稳定键（患者 #n → f0nn、植入 #n → f10n、电极 #n → f12n、随访 #n → f3nn、新增 #n → f33n+4）。 */
    @Test
    void everyAppendixRowHasFieldWithSameLabel() {
        Map<String, Map<String, String>> fields = fieldsByKey();
        // ① 患者登记 21 列（纸质表）
        for (Row row : appendixBlocks.get(0)) {
            String key = String.format("f%03d", row.no());
            assertLabel(fields, key, row, "C_REGISTRATION");
        }
        // ② 随访程控记录 34 列（纸质表，OQ-130①：#n → f3nn）
        for (Row row : appendixBlocks.get(1)) {
            String key = String.format("f3%02d", row.no());
            assertLabel(fields, key, row, "C_PROGRAM_FU");
        }
        // ③ 植入记录 13 行 → f101–f113
        for (Row row : appendixBlocks.get(2)) {
            String key = String.format("f1%02d", row.no());
            assertLabel(fields, key, row, "C_IMPLANT");
        }
        // ④ 电极记录 6 行 → f121–f126
        for (Row row : appendixBlocks.get(3)) {
            String key = String.format("f12%d", row.no());
            assertLabel(fields, key, row, "C_IMPLANT");
        }
        // ⑤ 随访新增 7 行 → f335–f341
        Map<String, String> extraKeys =
                Map.of(
                        "关联植入记录",
                        "f335",
                        "记录来源",
                        "f336",
                        "囊袋/伤口检查",
                        "f337",
                        "对应扫描件",
                        "f338",
                        "录入人、录入时间",
                        "f339",
                        "充电时间(s)",
                        "f340",
                        "高压阻抗(Ω)",
                        "f341");
        for (Row row : appendixBlocks.get(4)) {
            String key = extraKeys.get(row.label());
            assertNotNull(key, "随访新增行应登记键位：" + row.label());
            assertLabel(fields, key, row, "C_PROGRAM_FU");
        }
    }

    /** 字段顺序与纸质表一致（C-FLD-010，验收原文）：患者 21 列与随访 34 列 sort_no = 行号 × 10 严格递增。 */
    @Test
    void paperOrderPreservedBySortNo() {
        Map<String, Map<String, String>> fields = fieldsByKey();
        int last = 0;
        for (Row row : appendixBlocks.get(0)) {
            int sort = sortOf(fields, String.format("f%03d", row.no()));
            assertEquals(row.no() * 10, sort, "患者登记 #" + row.no() + " sort_no = 行号 × 10（纸质表顺序）");
            assertTrue(sort > last, "患者登记顺序严格递增");
            last = sort;
        }
        last = 0;
        for (Row row : appendixBlocks.get(1)) {
            int sort = sortOf(fields, String.format("f3%02d", row.no()));
            assertEquals(row.no() * 10, sort, "随访程控记录 #" + row.no() + " sort_no = 行号 × 10（纸质表顺序）");
            assertTrue(sort > last, "随访程控记录顺序严格递增");
            last = sort;
        }
    }

    /** 键位契约（OQ-130① / OQ-136⑤ / E21-T11）：对比规则与提交切换引用的稳定键与编码。 */
    @Test
    void keyContractHeld() {
        assertEquals(
                "followup_date",
                fieldOf("C_PROGRAM_FU", "f303").get("field_code"),
                "f303 编码 followup_date（重复提示，OQ-136⑤）");
        assertEquals(
                "visit_doctor",
                fieldOf("C_PROGRAM_FU", "f305").get("field_code"),
                "f305 编码 visit_doctor（C-FU-008 自动带入，E21-T11）");
        for (String[] expect :
                new String[][] {
                    {"f308", "ra_threshold"}, {"f309", "ra_pulse_width"}, {"f310", "ra_sensing"},
                    {"f311", "ra_impedance"}, {"f313", "rv_threshold"}, {"f314", "rv_pulse_width"},
                    {"f315", "rv_sensing"}, {"f316", "rv_impedance"}, {"f323", "eri_status"},
                    {"f324", "battery_life"}, {"f333", "next_visit_date"}
                }) {
            assertEquals(
                    expect[1],
                    fieldOf("C_PROGRAM_FU", expect[0]).get("field_code"),
                    expect[0] + " 编码（V82 对比规则 / PlanSwitchService 键契约，OQ-130①）");
        }
        // ERI 状态选项集（V82 契约：0=正常 / 1=接近ERI / 2=已达ERI(EOI)）
        assertEquals(Map.of(0, "正常", 1, "接近ERI", 2, "已达ERI(EOI)"), optionSets.get("c_eri_status"));
        // 分类锚点（OQ-130①：threshold / sensing / impedance / battery，C-PAT-018 程控分组）
        for (String anchor : List.of("threshold", "sensing", "impedance", "battery")) {
            assertTrue(sql.contains("'" + anchor + "'"), "C_PROGRAM_FU 应含分类锚点 " + anchor);
        }
        assertTrue(
                sql.contains("('C_PROGRAM_FU', '随访程控记录（医院 C）', 'FOLLOWUP'"),
                "C_PROGRAM_FU 表单随包（V91 计划节点引用）");
    }

    /** 业务字典绑定（OQ-69④ / V89 列）与带入上次值标记（OQ-67① / C-FU-016 设置项，测量值不带入）。 */
    @Test
    void dictBindingsAndCarryOver() throws IOException {
        String carrier = read(CARRIER);
        assertTrue(carrier.contains("ADD COLUMN dict_code VARCHAR(64)"), "V89 应加 dict_code 列");
        assertTrue(carrier.contains("ADD COLUMN carry_over TINYINT"), "V89 应加 carry_over 列");
        assertTrue(carrier.contains("ALTER TABLE fe_form_version_field"), "版本快照表同步加列（发布时固化绑定与标记）");
        // 绑定行：起搏器类型 / 品牌 / 统一起搏模式（植入与程控共用，Q08）/ 植入指征
        for (String[] expect :
                new String[][] {
                    {"C_REGISTRATION", "f008", "pacemaker_type"},
                    {"C_REGISTRATION", "f009", "pacemaker_brand"},
                    {"C_REGISTRATION", "f011", "pacing_mode"},
                    {"C_REGISTRATION", "f018", "implant_indication"},
                    {"C_IMPLANT", "f104", "pacemaker_type"},
                    {"C_IMPLANT", "f105", "pacemaker_brand"},
                    {"C_IMPLANT", "f108", "pacing_mode"},
                    {"C_IMPLANT", "f111", "implant_indication"},
                    {"C_PROGRAM_FU", "f318", "pacing_mode"}
                }) {
            assertEquals(
                    expect[2],
                    fieldOf(expect[0], expect[1]).get("dict_code"),
                    expect[0] + "·" + expect[1] + " 绑定业务字典（平台库 V70）");
        }
        assertEquals(
                9,
                fieldRows.stream().filter(r -> r.get("dict_code") != null).count(),
                "绑定业务字典的字段共 9 行（其余走选项集或系统枚举）");
        // 带入上次值：程控起搏模式、上下限频率、AV 间期（C-FU-016；测量值不带入）
        for (String key : List.of("f318", "f319", "f320", "f321")) {
            assertEquals("1", fieldOf("C_PROGRAM_FU", key).get("carry_over"), key + " 可带入上次设置");
        }
        assertEquals(
                4,
                fieldRows.stream().filter(r -> "1".equals(r.get("carry_over"))).count(),
                "可带入字段只有程控设置项 4 个");
    }

    /** 选项集（C-FLD-004 / Q04、Q06、Q07）：互斥「无」「愈合良好」、「其他」需补充说明、并发症并集 12 项。 */
    @Test
    void optionSetsMatchSpec() {
        assertEquals(
                Map.of(
                        0, "无", 1, "心悸", 2, "黑蒙", 3, "晕厥", 4, "气促", 5, "乏力", 6, "胸痛", 7, "水肿", 8,
                        "其他"),
                optionSets.get("c_symptom"),
                "附录随访 #7 症状（Q04）");
        assertTrue(exclusiveFlags.get("c_symptom").get(0), "症状「无」互斥（Q04）");
        assertTrue(needsDetailFlags.get("c_symptom").get(8), "症状「其他」须填说明（Q04）");
        assertEquals(12, optionSets.get("c_complication").size(), "并发症 = 原表两组选项的并集 12 项（Q06）");
        assertTrue(optionSets.get("c_complication").containsValue("膈肌刺激"), "并集含前表膈肌刺激（Q06）");
        assertTrue(optionSets.get("c_complication").containsValue("气胸"), "并集含后表气胸（Q06）");
        assertTrue(exclusiveFlags.get("c_complication").get(0), "并发症「无」互斥");
        assertTrue(needsDetailFlags.get("c_complication").get(11), "并发症「其他」须填说明");
        assertEquals(
                Map.of(0, "愈合良好", 1, "红肿", 2, "渗液", 3, "血肿", 4, "皮肤变薄或破溃", 5, "感染征象", 6, "其他"),
                optionSets.get("c_pocket_exam"),
                "囊袋/伤口检查（Q07 / C-FLD-008 新增）");
        assertTrue(exclusiveFlags.get("c_pocket_exam").get(0), "囊袋检查「愈合良好」互斥（Q07）");
        assertTrue(needsDetailFlags.get("c_pocket_exam").get(6), "囊袋检查「其他」须填说明（Q07）");
        assertEquals(
                Map.of(
                        1,
                        "术后72小时/出院前",
                        2,
                        "术后1个月",
                        3,
                        "术后3个月",
                        4,
                        "术后6个月",
                        5,
                        "术后1年",
                        6,
                        "每6-12个月",
                        7,
                        "ERI期随访",
                        8,
                        "其他"),
                optionSets.get("c_visit_timepoint"),
                "随访时间点（附录随访 #4）");
        assertEquals(
                Map.of(1, "门诊程控", 2, "远程监测", 3, "门诊程控+远程监测"),
                optionSets.get("c_visit_method"),
                "随访方式（附录患者 #19 / 随访 #6）");
        assertEquals(
                Map.of(0, "无", 1, "ATP", 2, "SHOCK"),
                optionSets.get("c_icd_therapy"),
                "ICD恰当治疗（Q19）");
        assertEquals(7, optionSets.get("c_icd_inappropriate").size(), "ICD不恰当治疗 7 项（附录随访 #30）");
        assertEquals(5, optionSets.get("c_arr_event").size(), "心律失常事件 5 项（附录随访 #28）");
        assertTrue(exclusiveFlags.get("c_arr_event").get(0), "心律失常事件「无」互斥（Q04）");
    }

    /**
     * 条件显示（C-FLD-005 / C-FU-009，E21-T03 DSL：device.hasRaLead / device.type in [ICD, CRT_D] /
     * chambers ≥ 2）。
     */
    @Test
    void deviceContextConditions() {
        for (String[] expect :
                new String[][] {
                    {"f308", "device.hasRaLead"},
                    {"f309", "device.hasRaLead"},
                    {"f310", "device.hasRaLead"},
                    {"f311", "device.hasRaLead"},
                    {"f312", "device.hasRaLead"}
                }) {
            String visible = fieldOf("C_PROGRAM_FU", expect[0]).get("visible_when");
            assertNotNull(visible, expect[0] + " 应有器械上下文条件显示");
            assertTrue(visible.contains("\"" + expect[1] + "\""), expect[0] + " 条件引用 " + expect[1]);
        }
        for (String key : List.of("f329", "f330", "f340", "f341")) {
            String visible = fieldOf("C_PROGRAM_FU", key).get("visible_when");
            assertNotNull(visible, key + " 应有器械上下文条件显示");
            assertTrue(
                    visible.contains("device.type")
                            && visible.contains("ICD")
                            && visible.contains("CRT_D"),
                    key + " 仅 ICD、CRT-D 显示（Q19）");
        }
        String av = fieldOf("C_PROGRAM_FU", "f321").get("visible_when");
        assertTrue(av.contains("device.chambers"), "AV间期双腔及以上显示（C-FU-009）");
    }

    /** 程控参数范围为软校验（CL-01：附录「初步建议」超范围黄提示确认后可保存；上限 ≤ 下限才拦截，C-FLD-007）。 */
    @Test
    void softRangesAndHardRule() {
        // 心室阻抗建议范围 100–3000 为软校验（验收：填 5000 出提示、确认后可保存）
        assertEquals(
                "{\"min\":100,\"max\":3000}",
                fieldOf("C_PROGRAM_FU", "f316").get("soft_range"),
                "心室阻抗建议范围（附录随访 #16）");
        assertNull(
                fieldOf("C_PROGRAM_FU", "f316").get("hard_range"), "建议范围不做硬拦截（CL-01 / C-FLD-007）");
        assertTrue(
                sql.contains("'upper_rate_gt_lower'") && sql.contains("上限频率须大于下限频率"),
                "上限频率 ≤ 下限为硬校验（C-FLD-007 拦截保存）");
        assertEquals("1", fieldOf("C_PROGRAM_FU", "f303").get("required"), "随访日期必填（附录随访 #3）");
        assertEquals("1", fieldOf("C_PROGRAM_FU", "f304").get("required"), "随访时间点必填（附录随访 #4）");
    }

    /** 植入配套列随字典登记（OQ-132② note / Q16 指征补充 / 结束原因；电极部位按腔室不绑单列，Q15）。 */
    @Test
    void implantCompanionFieldsRegistered() {
        assertEquals(
                "note", fieldOf("C_IMPLANT", "f115").get("field_code"), "其他说明 note（OQ-132②，V83）");
        assertEquals(
                "indication_note",
                fieldOf("C_IMPLANT", "f114").get("field_code"),
                "指征补充说明（Q16，V81）");
        assertEquals(
                "end_reason", fieldOf("C_IMPLANT", "f116").get("field_code"), "结束原因（附录植入 #13 的配套）");
        assertNull(
                fieldOf("C_IMPLANT", "f123").get("dict_code"),
                "电极植入部位按腔室三字典（lead_position_ra/rv/lv，Q15）不绑单列——选项经 /implants/options");
        assertTrue(
                fieldOf("C_IMPLANT", "f123").get("help_text").contains("lead_position"),
                "电极部位帮助说明应注明按腔室字典");
    }

    // ---------------------------------------------------------------- 解析

    /** 附录 A 按分组切段（同组相邻行归一段；行号连续）。 */
    private static List<List<Row>> parseAppendix() throws IOException {
        Path spec = locateSpec();
        List<Row> rows = new ArrayList<>();
        boolean inAppendix = false;
        for (String line : Files.readAllLines(spec, StandardCharsets.UTF_8)) {
            if (line.startsWith("## 附录 A.")) {
                inAppendix = true;
                continue;
            }
            if (inAppendix && line.startsWith("## ")) {
                break;
            }
            if (!inAppendix) {
                continue;
            }
            Matcher m = ROW_RE.matcher(line);
            if (m.matches()) {
                rows.add(
                        new Row(
                                m.group(1),
                                Integer.parseInt(m.group(2)),
                                m.group(3).trim(),
                                m.group(4).trim(),
                                m.group(5).trim()));
            }
        }
        assertEquals(97, rows.size(), "附录 A 应解析出 97 行");
        List<List<Row>> blocks = new ArrayList<>();
        for (Row row : rows) {
            if (blocks.isEmpty()
                    || !blocks.get(blocks.size() - 1).get(0).group().equals(row.group())) {
                blocks.add(new ArrayList<>());
            }
            blocks.get(blocks.size() - 1).add(row);
        }
        return blocks;
    }

    /** 从模块目录向上找到仓库根（Maven 与 IDE 的工作目录不一致都能命中）。 */
    private static Path locateSpec() {
        Path dir = Paths.get("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("docs/requirements/hospital-c-spec.md");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到 docs/requirements/hospital-c-spec.md（应在仓库内运行）");
    }

    /** 定位 INSERT INTO table（空白后跟左括号；跳过列名另起一行的写法）。 */
    private static int findInsert(String sql, String table, int from) {
        int head = sql.indexOf("INSERT INTO " + table, from);
        while (head >= 0) {
            int rest = head + ("INSERT INTO " + table).length();
            while (rest < sql.length() && Character.isWhitespace(sql.charAt(rest))) {
                rest++;
            }
            if (rest < sql.length() && sql.charAt(rest) == '(') {
                return head;
            }
            head = sql.indexOf("INSERT INTO " + table, head + 1);
        }
        return -1;
    }

    private static String read(String path) throws IOException {
        try (InputStream in = CFieldDictSqlTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(in, "迁移脚本应在 classpath：" + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 表的全部 INSERT…VALUES 行（列名 → 值；单引号内豁免切分）。 */
    private static List<Map<String, String>> parseInsertRows(String sql, String table) {
        int head = findInsert(sql, table, 0);
        assertTrue(head >= 0, "应包含 " + table + " 的 INSERT");
        int end = sql.indexOf(';', head);
        String insert = sql.substring(head, end);
        int columnsEnd = insert.indexOf(')');
        List<String> columns = splitTopLevel(insert.substring(insert.indexOf('(') + 1, columnsEnd));
        String values = insert.substring(insert.indexOf("VALUES", columnsEnd) + "VALUES".length());
        values = values.substring(values.indexOf('('));
        List<Map<String, String>> rows = new ArrayList<>();
        for (String tuple : splitTuples(values)) {
            String trimmed = tuple.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            assertTrue(trimmed.startsWith("(") && trimmed.endsWith(")"), "值元组应以括号包裹");
            List<String> cells = splitTopLevel(trimmed.substring(1, trimmed.length() - 1));
            assertEquals(columns.size(), cells.size(), table + " 列数与值数一致");
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                row.put(columns.get(i).strip(), token(cells.get(i)));
            }
            rows.add(row);
        }
        return rows;
    }

    /** 括号深度 0 的逗号切分（单引号字符串内豁免，'' 为转义；元组边界 = 深度 0 的逗号）。 */
    private static List<String> splitTuples(String raw) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        int depth = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                current.append(c);
                if (c == '\'') {
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++;
                    } else {
                        inString = false;
                    }
                }
                continue;
            }
            if (c == '\'') {
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

    /** 顶层逗号切分（单引号字符串内豁免，'' 为转义）。 */
    private static List<String> splitTopLevel(String raw) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inString = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inString) {
                if (c == '\'') {
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '\'') {
                        current.append('\'');
                        i++;
                    } else {
                        inString = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '\'') {
                inString = true;
            } else if (c == ',') {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    private static String token(String raw) {
        String trimmed = raw.strip();
        if ("NULL".equals(trimmed)) {
            return null;
        }
        return trimmed.startsWith("'") && trimmed.endsWith("'")
                ? trimmed.substring(1, trimmed.length() - 1).replace("''", "'")
                : trimmed;
    }

    /** form →（稳定键 → 显示名）；行级列访问走 {@link #fieldOf}。 */
    private static Map<String, Map<String, String>> fieldsByKey() {
        Map<String, Map<String, String>> byKey = new LinkedHashMap<>();
        for (Map<String, String> row : fieldRows) {
            byKey.computeIfAbsent(row.get("form_code"), k -> new LinkedHashMap<>())
                    .put(row.get("field_key"), row.get("label"));
        }
        return byKey;
    }

    private static Map<String, String> fieldOf(String formCode, String fieldKey) {
        return fieldRows.stream()
                .filter(
                        r ->
                                formCode.equals(r.get("form_code"))
                                        && fieldKey.equals(r.get("field_key")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("字段不存在：" + formCode + "·" + fieldKey));
    }

    private static void assertLabel(
            Map<String, Map<String, String>> fields, String key, Row row, String formCode) {
        assertTrue(
                fields.getOrDefault(formCode, Map.of()).containsKey(key),
                "附录 "
                        + row.group()
                        + " #"
                        + row.no()
                        + "「"
                        + row.label()
                        + "」应有稳定键 "
                        + key
                        + "（"
                        + formCode
                        + "）");
        assertEquals(
                row.label(),
                fieldOf(formCode, key).get("label"),
                "附录 " + row.group() + " #" + row.no() + " 显示名一致");
    }

    private static int sortOf(Map<String, Map<String, String>> fields, String key) {
        for (Map<String, String> row : fieldRows) {
            if (key.equals(row.get("field_key"))) {
                return Integer.parseInt(row.get("sort_no"));
            }
        }
        throw new IllegalStateException("字段不存在：" + key);
    }
}
