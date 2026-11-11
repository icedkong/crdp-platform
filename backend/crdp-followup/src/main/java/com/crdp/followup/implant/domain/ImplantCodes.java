package com.crdp.followup.implant.domain;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 植入记录与电极的取值编码与显示名（E21-T01 / C-PAT-004、C-PAT-005；C 规格附录 A）。
 *
 * <p>数据库列存稳定字符串编码（与 pt_patient.gender M/F 同口径），显示名由本表统一维护并经 options
 * 接口下发（页面不硬编码选项文案）。起搏器类型、品牌、起搏模式、植入指征的业务字典 值随 E21-T04 录入 cfg_dict（OQ-69①），本表为暂行预置（取值清单来自 C 规格附录 A
 * 患者 #8 / #9、 植入 #2 / #8 / #11）；起搏模式为统一起搏模式字典（C-FLD-003）的暂行并集。
 */
public final class ImplantCodes {

    // ---------------------------------------------------------------- 植入类型（附录 A 植入 #2）

    public static final String TYPE_FIRST = "FIRST";
    public static final String TYPE_REPLACE = "REPLACE";
    public static final String TYPE_UPGRADE = "UPGRADE";
    public static final String TYPE_LEAD_REVISION = "LEAD_REVISION";
    public static final String TYPE_OTHER = "OTHER";

    /** 植入类型编码 → 显示名（更换 / 升级 / 电极修正流程随 E21-T02）。 */
    public static final Map<String, String> IMPLANT_TYPES =
            Map.of(
                    TYPE_FIRST, "首次植入",
                    TYPE_REPLACE, "更换（电池耗竭）",
                    TYPE_UPGRADE, "升级",
                    TYPE_LEAD_REVISION, "电极修正",
                    TYPE_OTHER, "其他");

    // ---------------------------------------------------------------- 起搏器类型（附录 A 患者 #8）

    public static final String DEVICE_SSI = "SSI";
    public static final String DEVICE_DDD = "DDD";
    public static final String DEVICE_CRT_P = "CRT_P";
    public static final String DEVICE_ICD = "ICD";
    public static final String DEVICE_CRT_D = "CRT_D";
    public static final String DEVICE_HBP_LBBP = "HBP_LBBP";
    public static final String DEVICE_LEADLESS = "LEADLESS";

    /** 起搏器类型编码 → 显示名（条件显示与计划映射的键，C-FLD-005 / E21-T03 / E21-T05）。 */
    public static final Map<String, String> DEVICE_TYPES =
            Map.of(
                    DEVICE_SSI, "单腔起搏器(SSI)",
                    DEVICE_DDD, "双腔起搏器(DDD)",
                    DEVICE_CRT_P, "三腔起搏器(CRT-P)",
                    DEVICE_ICD, "ICD",
                    DEVICE_CRT_D, "CRT-D",
                    DEVICE_HBP_LBBP, "希浦系统起搏(HBP/LBBP)",
                    DEVICE_LEADLESS, "无导线起搏器");

    // ---------------------------------------------------------------- 品牌（附录 A 患者 #9）

    /** 品牌编码 → 显示名（暂行预置，字典值随 E21-T04）。 */
    public static final Map<String, String> BRANDS =
            Map.of(
                    "MEDTRONIC", "美敦力",
                    "ABBOTT", "雅培",
                    "BIOTRONIK", "百多力",
                    "BOSTON_SCIENTIFIC", "波士顿科学",
                    "CHUANGLING", "创领心律",
                    "WEICHUANG", "微创医疗",
                    "LEPU", "乐普",
                    "JINSHI", "金仕生物",
                    "OTHER", "其他");

    // ---------------------------------------------------------------- 起搏模式（附录 A 植入 #8，C-FLD-003
    // 暂行）

    /** 起搏模式编码 → 显示名（登记表与程控表两套选项的暂行并集，统一字典随 E21-T04 / Q08）。 */
    public static final Map<String, String> PACING_MODES =
            Map.ofEntries(
                    Map.entry("VVI", "VVI"),
                    Map.entry("VVIR", "VVIR"),
                    Map.entry("AAI", "AAI"),
                    Map.entry("AAIR", "AAIR"),
                    Map.entry("DDD", "DDD"),
                    Map.entry("DDDR", "DDDR"),
                    Map.entry("VDD", "VDD"),
                    Map.entry("VDDR", "VDDR"),
                    Map.entry("SSI", "SSI"),
                    Map.entry("CRT_P", "CRT-P"),
                    Map.entry("CRT_D", "CRT-D"));

    // ---------------------------------------------------------------- 植入指征（附录 A 植入 #11，字典 + 文字）

    /** 植入指征编码 → 显示名（暂行预置，字典值随 E21-T04 / Q16；「其他」须填补充说明）。 */
    public static final Map<String, String> INDICATIONS =
            Map.of(
                    "SSS", "病态窦房结综合征",
                    "AVB", "房室传导阻滞",
                    "AF_PAUSE", "房颤伴长RR间期",
                    "CRT_IND", "心脏再同步治疗指征",
                    "OTHER", "其他");

    /** 需补充说明的指征编码（附录 A 植入 #11「保留文字补充」）。 */
    public static final String INDICATION_OTHER = "OTHER";

    // ---------------------------------------------------------------- 植入记录状态（附录 A 植入 #12，系统维护）

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REPLACED = "REPLACED";
    public static final String STATUS_EXPLANTED = "EXPLANTED";

    /** 植入记录状态编码 → 显示名（在用 = 当前器械，C-PAT-007 随 E21-T03 完整判定）。 */
    public static final Map<String, String> IMPLANT_STATUSES =
            Map.of(
                    STATUS_ACTIVE, "在用",
                    STATUS_REPLACED, "已更换",
                    STATUS_EXPLANTED, "已取出");

    // ---------------------------------------------------------------- 电极（附录 A 电极 #2 / #3 / #6）

    public static final String CHAMBER_RA = "RA";
    public static final String CHAMBER_RV = "RV";
    public static final String CHAMBER_LV = "LV";

    /** 电极植入部位按腔室过滤（Q15：原表三列共用一组选项，建议按腔室过滤；暂行划分随 E21-T04 复核）。 */
    public static final Map<String, Map<String, String>> LEAD_POSITIONS =
            Map.of(
                    CHAMBER_RA,
                    Map.of("RA_APPENDAGE", "右心耳", "RA_SEPTUM", "右房间隔", "OTHER", "其他"),
                    CHAMBER_RV,
                    Map.of(
                            "RV_APEX", "右室心尖",
                            "RV_SEPTUM", "右室间隔",
                            "HIS", "希氏束",
                            "LBB", "左束支",
                            "OTHER", "其他"),
                    CHAMBER_LV,
                    Map.of("CS", "冠状静脉窦", "OTHER", "其他"));

    public static final String LEAD_STATUS_ACTIVE = "ACTIVE";
    public static final String LEAD_STATUS_ABANDONED = "ABANDONED";
    public static final String LEAD_STATUS_EXTRACTED = "EXTRACTED";

    /** 电极状态编码 → 显示名。 */
    public static final Map<String, String> LEAD_STATUSES =
            Map.of(
                    LEAD_STATUS_ACTIVE, "在用",
                    LEAD_STATUS_ABANDONED, "废弃留置",
                    LEAD_STATUS_EXTRACTED, "已拔除");

    private ImplantCodes() {}

    /** 编码 → 显示名（未知编码返回 null，调用方按校验口径处理）。 */
    public static Optional<String> label(Map<String, String> table, String code) {
        return Optional.ofNullable(code).map(table::get);
    }

    /** 选项清单（options 接口用）：按 Map 迭代序不稳定，统一按显示名排序保证下发顺序稳定。 */
    public static List<Map.Entry<String, String>> sorted(Map<String, String> table) {
        return table.entrySet().stream().sorted(Map.Entry.comparingByValue()).toList();
    }
}
