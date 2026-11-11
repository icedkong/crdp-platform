package com.crdp.followup.implant.domain;

import java.util.List;
import java.util.Map;

/**
 * 植入记录与电极的取值编码与显示名（E21-T01 / C-PAT-004、C-PAT-005；C 规格附录 A）。
 *
 * <p>数据库列存稳定字符串编码（与 pt_patient.gender M/F 同口径）。E21-T04（C-FLD-002、C-FLD-003）起，
 * 起搏器类型、品牌、<b>统一起搏模式</b>（患者登记表与程控记录表两套选项合并，植入与程控共用，Q08）、 植入指征与电极植入部位（按腔室）为业务字典项——预置在平台库
 * V70（plt_dict / plt_dict_item）， 租户经 cfg_dict 停用平台项 / 新增本院项（PLT-CFG-002）；选项清单经 options 接口现读字典下发，
 * 显示名随字典（含停用项，历史值照常显示，C-FLD-009）。字典编码见 {@code ImplantDictPort}。
 *
 * <p>本表只保留系统维护的枚举：植入类型（附录 A 植入 #2）、植入记录状态（附录 A 植入 #12）、电极状态 （附录 A 电极 #6）、电极腔室（RA / RV /
 * LV，解剖口径不可配置）与业务逻辑引用的编码常量（如无导线 起搏器不录电极 C-PAT-005、计划映射键 E21-T05）。
 */
public final class ImplantCodes {

    // ---------------------------------------------------------------- 植入类型（附录 A 植入 #2）

    public static final String TYPE_FIRST = "FIRST";
    public static final String TYPE_REPLACE = "REPLACE";
    public static final String TYPE_UPGRADE = "UPGRADE";
    public static final String TYPE_LEAD_REVISION = "LEAD_REVISION";
    public static final String TYPE_OTHER = "OTHER";

    /** 植入类型编码 → 显示名（系统枚举；更换 / 升级 / 电极修正流程随 E21-T02）。 */
    public static final Map<String, String> IMPLANT_TYPES =
            Map.of(
                    TYPE_FIRST, "首次植入",
                    TYPE_REPLACE, "更换（电池耗竭）",
                    TYPE_UPGRADE, "升级",
                    TYPE_LEAD_REVISION, "电极修正",
                    TYPE_OTHER, "其他");

    // ---------------------------------------------------------------- 起搏器类型编码常量（字典
    // pacemaker_type，附录 A 患者 #8）

    public static final String DEVICE_SSI = "SSI";
    public static final String DEVICE_DDD = "DDD";
    public static final String DEVICE_CRT_P = "CRT_P";
    public static final String DEVICE_ICD = "ICD";
    public static final String DEVICE_CRT_D = "CRT_D";

    /** 希浦系统起搏（HBP/LBBP）。 */
    public static final String DEVICE_HBP_LBBP = "HBP_LBBP";

    /** 无导线起搏器：不录电极（C-PAT-005）；条件显示与计划映射的键另见 C-FLD-005 / E21-T03 / E21-T05。 */
    public static final String DEVICE_LEADLESS = "LEADLESS";

    // ---------------------------------------------------------------- 起搏模式编码常量（字典
    // pacing_mode，C-FLD-003 / Q08）

    /** 双腔模式切换（程控表选项，Q08 定稿清单）。 */
    public static final String PACING_AAI_DDD = "AAI_DDD";

    /** 频率应答双腔模式切换（程控表选项，Q08 定稿清单）。 */
    public static final String PACING_AAIR_DDR = "AAIR_DDR";

    // ---------------------------------------------------------------- 植入指征（附录 A 植入 #11）

    /** 需补充说明的指征编码（附录 A 植入 #11「保留文字补充」，Q16；编码在字典 implant_indication 中）。 */
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

    /** 腔室 → 电极部位字典编码（Q15 按腔室过滤：lead_position_ra / rv / lv，平台库 V70 预置）。 */
    public static final Map<String, String> LEAD_POSITION_DICTS =
            Map.of(
                    CHAMBER_RA, "lead_position_ra",
                    CHAMBER_RV, "lead_position_rv",
                    CHAMBER_LV, "lead_position_lv");

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

    /** 选项清单（options 接口用）：系统枚举按 Map 迭代序不稳定，统一按显示名排序保证下发顺序稳定。 */
    public static List<Map.Entry<String, String>> sorted(Map<String, String> table) {
        return table.entrySet().stream().sorted(Map.Entry.comparingByValue()).toList();
    }
}
