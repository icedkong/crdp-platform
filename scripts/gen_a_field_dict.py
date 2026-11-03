#!/usr/bin/env python3
"""E23-T01：从医院 A 规格「附录 A. 字段字典全表（266 个字段）」生成租户库迁移脚本。

用法：python3 scripts/gen_a_field_dict.py
  读 docs/requirements/hospital-a-spec.md 附录 A（验收基准），按本脚本登记的映射
  （选项集、条件、派生、存储、帮助说明）生成
  backend/crdp-server/src/main/resources/db/tenant/followup/V38__hospital_a_field_dict.sql。
生成前后各做一次核对（行数 266、9 个分类字段数、类型分布、敏感 4、必填 10），
与 AFieldDictSqlTest（附录 ↔ 迁移逐项比对）同一口径；改附录或映射后重跑本脚本再提交。

主仓库：https://github.com/icedkong/crdp-platform
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "docs/requirements/hospital-a-spec.md"
OUT = ROOT / "backend/crdp-server/src/main/resources/db/tenant/followup/V38__hospital_a_field_dict.sql"

ROW_RE = re.compile(r"^\|\s*(\d+)\s*\|\s*(.+?)\s*·\s*`([^`]+)`\s*\|\s*([^|]+?)\s*\|\s*([^|]*?)\s*\|\s*$")
UNIT_RE = re.compile(r"单位 ([^；]+)")
RANGE_RE = re.compile(r"范围 ([0-9.]+)–([0-9.]+)")

# 附录类型 → fe_field.type（components.md §10.1）
TYPE_MAP = {
    "是否": "radio",
    "单选": "select",
    "数值": "number",
    "整数": "number",
    "数值×2": "pair_number",
    "日期": "date",
    "日期时间": "datetime",
    "文本": "text",
    "文本（敏感）": "text",
    "引用": "select",
    "派生·整数": "derived",
    "派生·数值": "derived",
}

# 序号区间 → (表单编码, 分类键)（9 个分类：基线 8 分类 + 随访 4 分组，A-FLD-007）
def category_of(no: int) -> tuple[str, str]:
    if no <= 12:
        return ("A_BASELINE", "basic")
    if no <= 49:
        return ("A_BASELINE", "case")
    if no <= 67:
        return ("A_BASELINE", "lab")
    if no <= 116:
        return ("A_BASELINE", "echo")
    if no <= 134:
        return ("A_BASELINE", "angio")
    if no <= 176:
        return ("A_BASELINE", "surgery")
    if no <= 192:
        return ("A_BASELINE", "periop")
    if no <= 212:
        return ("A_BASELINE", "discharge_med")
    if no <= 218:
        return ("A_ANNUAL_FU", "fu_contact")
    if no <= 235:
        return ("A_ANNUAL_FU", "fu_event")
    if no <= 258:
        return ("A_ANNUAL_FU", "fu_echo")
    return ("A_ANNUAL_FU", "fu_med")


CATEGORIES = {
    "A_BASELINE": [
        ("basic", "基本信息", 10),
        ("case", "病例基本信息", 20),
        ("lab", "实验室检查", 30),
        ("echo", "超声检查", 40),
        ("angio", "术前造影", 50),
        ("surgery", "手术信息", 60),
        ("periop", "围手术期", 70),
        ("discharge_med", "出院带药", 80),
    ],
    "A_ANNUAL_FU": [
        ("fu_contact", "联系与生存", 10),
        ("fu_event", "终点事件", 20),
        ("fu_echo", "复查超声", 30),
        ("fu_med", "用药", 40),
    ],
}

# 选项集签名（code=label|…，附录解析结果）→ (选项集编码, 名称)；a_mr_reflux 带序数（「返流加重 ≥ 2 级」比较用）
OPTION_SETS = {
    "0=否|1=是": ("a_yesno", "是否"),
    "0=无|1=有": ("a_have", "无有"),
    "1=男|2=女": ("a_gender", "性别"),
    "0=无|1=微量|2=少量|3=中量|4=大量": ("a_mr_reflux", "瓣膜返流量"),
    "0=无|1=室速|2=室扑|3=室颤|4=室早": ("a_vent_arr", "室性心律失常"),
    "0=无心绞痛|1=一级|2=二级|3=三级|4=四级": ("a_ccs", "CCS 心绞痛分级"),
    "1=一级|2=二级|3=三级|4=四级": ("a_nyha", "NYHA 心功能分级"),
    "0=无|1=脑出血|2=脑梗死|3=短暂脑缺血发作|4=动静脉畸形|5=腔隙性脑梗": ("a_cvd", "脑血管疾病"),
    "0=无|1=起搏器|2=ICD": ("a_pm_icd", "术前起搏器或ICD"),
    "1=经胸|2=经食道|3=床旁": ("a_echo_type", "超声类型"),
    "1=线性|2=环缩|3=补片|4=折叠": ("a_aneurysm", "室壁瘤术式"),
    "0=无|1=成形+瓣环植入|2=单纯成形|3=置换": ("a_valve_surgery", "瓣膜手术方式"),
    "1=保留全瓣|2=保留后瓣|3=未保留瓣叶": ("a_mvr_preserve", "二尖瓣置换方式"),
    "0=无|1=达格列净|2=恩格列净|3=卡格列净": ("a_sglt2", "SGLT2 抑制剂"),
    "0=无|1=西格列汀|2=沙格列汀|3=利格列汀|4=阿格列汀": ("a_ddp4", "DPP4 抑制剂"),
    "0=无|1=阿卡波糖|2=伏格列波糖": ("a_aglu", "α-葡萄糖苷酶抑制剂"),
    "0=无|1=呋塞米|2=托拉塞米|3=氢氯噻嗪|4=替米沙坦": ("a_diuretic", "利尿剂"),
    "0=无|1=单硝酸|2=二硝酸|3=三硝酸": ("a_nitrate", "硝酸酯类"),
    "0=无|1=阿托伐|2=瑞舒伐|3=氟伐|4=洛伐|5=匹伐|6=普伐|7=辛伐": ("a_statin", "他汀类"),
    "1=存活|2=病重自动出院|3=死亡": ("a_discharge_status", "出院状态"),
    "0=否|1=脑出血|2=脑梗": ("a_periop_cvd", "围术期脑血管意外"),
    "0=无|1=脑出血|2=脑梗死|3=未知类型": ("a_post_cvd", "术后脑血管疾病"),
    "1=心衰|2=冠脉疾病|3=室上性心率失常|4=室性心率失常|5=主动脉疾病|6=外周血管疾病": ("a_readmit_type", "心血管再住院类型"),
    "0=无|1=内科支架植入或球囊扩张|2=外科再次冠脉搭桥": ("a_revasc", "再次血运重建"),
    "0=无|1=瓣膜介入治疗|2=外科再次瓣膜手术": ("a_revalve", "再次瓣膜手术"),
    "1=二尖瓣|2=三尖瓣|3=主动脉瓣": ("a_revalve_pos", "再次瓣膜手术位置"),
    "1=起搏器置入|2=射频消融术|3=ICD": ("a_rearr_type", "心律失常再次手术类型"),
    "0=无|1=消化道出血|2=皮肤出血|3=鼻出血|4=牙龈出血|5=结膜出血|6=眼底出血|7=其他出血": ("a_bleed_type", "术后出血类型"),
    "1=心包填塞|2=脑出血|3=胃肠道出血": ("a_postop_bleed", "术后出血部位"),
}

# 字段编码（ASCII：字母 / 数字 / 下划线，E11-T09 导入工具 CODE_PATTERN 口径，见 OQ-72）。
# 附录 A 的原表列名（中文）不直接做编码，完整保留在 help_text「原表列名「…」」供核对与宽表导入表头匹配（E18）。
CODES = {
    1: "surgery_date", 2: "mrn", 3: "patient_name", 4: "gender",
    5: "surgery_age", 6: "diagnosis", 7: "ward", 8: "surgeon",
    9: "phone_1", 10: "phone_2", 11: "surgery_note", 12: "patient_type",
    13: "height_cm", 14: "weight_kg", 15: "bmi", 16: "admission_date",
    17: "discharge_date", 18: "postop_stay_days", 19: "smoking_history", 20: "preop_cardiac_mri",
    21: "preop_contrast_cardiac_mri", 22: "preop_nuclear_medicine", 23: "diabetes", 24: "hypertension",
    25: "hyperlipidemia", 26: "chronic_lung_disease", 27: "renal_insufficiency", 28: "dialysis",
    29: "peripheral_vascular_disease", 30: "carotid_stenosis", 31: "vertebral_stenosis", 32: "subclavian_stenosis",
    33: "intracranial_stenosis", 34: "intracranial_stenosis_detail", 35: "af_or_flutter", 36: "ventricular_arrhythmia",
    37: "ccs_class", 38: "nyha_class", 39: "cerebrovascular_disease", 40: "prior_mi",
    41: "mi_site", 42: "months_from_mi", 43: "mi_within_90d", 44: "three_vessel_disease",
    45: "left_main_disease", 46: "prior_pci", 47: "months_from_pci", 48: "prior_cabg",
    49: "preop_pm_or_icd", 50: "preop_hba1c", 51: "preop_wbc", 52: "preop_hemoglobin",
    53: "preop_platelet", 54: "first_glu_admission", 55: "preop_glu", 56: "first_tg_admission",
    57: "preop_tg", 58: "preop_creatinine", 59: "creatinine_peak_48h", 60: "creatinine_peak_7d",
    61: "preop_uric_acid", 62: "preop_hscrp", 63: "preop_ldlc", 64: "preop_ntprobnp",
    65: "predischarge_ntprobnp_peak", 66: "preop_hsctni", 67: "hsctni_peak_72h", 68: "echo_count_half_year",
    69: "min_ef_half_year", 70: "rwma_half_year", 71: "echo_type", 72: "mv_regurg_3rd_last",
    73: "echo_date_3rd_last", 74: "mv_regurg_2nd_last", 75: "echo_date_2nd_last", 76: "mv_regurg_max",
    77: "echo_date_max", 78: "mv_regurg_last", 79: "echo_date_last", 80: "la_diameter_last",
    81: "la_volume_last", 82: "ivs_thickness_last", 83: "ef_last", 84: "lvedd_last",
    85: "lvedv_last", 86: "lvds_last", 87: "lvesv_last", 88: "sv_last",
    89: "fs_last", 90: "co_last", 91: "e_e_prime_last", 92: "rv_diameter_last",
    93: "aneurysm_size", 94: "ventricular_thrombus", 95: "aneurysm_site", 96: "septal_perforation",
    97: "preop_mr", 98: "preop_tr", 99: "preop_ar", 100: "predischarge_la_diameter",
    101: "predischarge_la_volume", 102: "predischarge_ef", 103: "predischarge_lvedd", 104: "predischarge_lvedv",
    105: "predischarge_lvds", 106: "predischarge_lvesv", 107: "predischarge_rv_diameter", 108: "predischarge_sv",
    109: "predischarge_fs", 110: "predischarge_co", 111: "predischarge_e_e_prime", 112: "predischarge_aneurysm_size",
    113: "predischarge_thrombus", 114: "predischarge_mr", 115: "predischarge_tr", 116: "predischarge_ar",
    117: "angiography_within_1y", 118: "lesion_vessel_count", 119: "lm_lesion", 120: "lm_stenosis",
    121: "lad_lesion", 122: "lad_stenosis", 123: "dm_rm_lesion", 124: "dm_rm_stenosis",
    125: "lcx_lesion", 126: "lcx_stenosis", 127: "om_lesion", 128: "om_stenosis",
    129: "pda_lesion", 130: "pda_stenosis", 131: "rca_lesion", 132: "rca_stenosis",
    133: "plb_lesion", 134: "plb_stenosis", 135: "preop_ecmo", 136: "preop_iabp",
    137: "emergency_surgery", 138: "surgery_start_time", 139: "surgery_end_time", 140: "surgery_duration_min",
    141: "cpb", 142: "cpb_duration_min", 143: "cross_clamp_min", 144: "postop_ecmo",
    145: "postop_iabp", 146: "postop_crrt", 147: "postop_lvad", 148: "cabg",
    149: "graft_count", 150: "arterial_graft_count", 151: "venous_graft_count", 152: "endarterectomy",
    153: "lm_target", 154: "lad_target", 155: "lcx_target", 156: "rca_target",
    157: "lv_aneurysm_repair", 158: "aneurysm_technique", 159: "thrombectomy", 160: "vsd_repair",
    161: "av_surgery_type", 162: "mv_surgery_type", 163: "mv_ring_used", 164: "mv_ring_size",
    165: "mv_ring_model", 166: "papillary_approximation", 167: "papillary_suspension", 168: "artificial_chordae",
    169: "leaflet_enlargement", 170: "mvr_preserve_type", 171: "tv_surgery_type", 172: "tv_ring_used",
    173: "tv_ring_model", 174: "laa_resection", 175: "maze_procedure", 176: "other_concomitant_surgery",
    177: "blood_products_used", 178: "rbc_units", 179: "platelet_units", 180: "plasma_ml",
    181: "periop_ventricular_arrhythmia", 182: "postop_new_af", 183: "postop_bleeding_site", 184: "chest_drainage_ml",
    185: "re_exploration", 186: "re_exploration_reason", 187: "icu_in_time", 188: "icu_out_time",
    189: "icu_hours", 190: "ventilator_hours", 191: "discharge_status", 192: "periop_stroke",
    193: "amiodarone_at_discharge", 194: "mexiletine", 195: "aspirin_at_discharge", 196: "clopidogrel_at_discharge",
    197: "warfarin_at_discharge", 198: "ticagrelor_at_discharge", 199: "acei_at_discharge", 200: "arb_at_discharge",
    201: "arni_at_discharge", 202: "sglt2_type", 203: "dpp4_type", 204: "agi_type",
    205: "beta_blocker_at_discharge", 206: "iron_supplement_at_discharge", 207: "digoxin", 208: "diuretic_type",
    209: "spironolactone", 210: "nitrate_type", 211: "statin_type", 212: "ezetimibe",
    213: "wechat_added", 214: "followup_date", 215: "death", 216: "death_date",
    217: "death_followup_date", 218: "death_cause", 219: "postop_mi", 220: "postop_mi_date",
    221: "postop_cvd", 222: "postop_cvd_date", 223: "cv_readmission_type", 224: "cv_readmission_date",
    225: "revascularization_type", 226: "revascularization_date", 227: "revalve_surgery_type", 228: "revalve_position",
    229: "revalve_surgery_date", 230: "arrhythmia_reoperation", 231: "arrhythmia_reoperation_date", 232: "arrhythmia_reoperation_type",
    233: "postop_bleed_type", 234: "postop_bleed_date", 235: "special_note", 236: "echo_recheck",
    237: "echo_recheck_date", 238: "fu_mr_present", 239: "fu_mr_degree", 240: "fu_ar_present",
    241: "fu_ar_degree", 242: "fu_tr_present", 243: "fu_tr_degree", 244: "fu_rv_diameter",
    245: "fu_la_size", 246: "fu_la_volume", 247: "fu_ivs_thickness", 248: "fu_ef",
    249: "fu_lvdd", 250: "fu_lvedv", 251: "fu_lvds", 252: "fu_lvesv",
    253: "fu_sv", 254: "fu_fs", 255: "fu_co", 256: "fu_e_e_prime",
    257: "fu_aneurysm_size", 258: "fu_thrombus", 259: "postop_diuretic", 260: "postop_beta_blocker",
    261: "postop_acei", 262: "postop_arb", 263: "postop_arni", 264: "postop_mra",
    265: "postop_sglt2", 266: "postop_sgc_stimulator",
}

# 患者主档 / 系统绑定字段 → 实体列（tech-plan §6 混合存储；出生日期 f267 为 A-FLD-013 新增）
COLUMN_ROWS = {1, 4, 7, 8, 12, 16, 17, 191, 267}

# 派生定义（规格 §3「派生字段计算规则」，公式已由用户确认；键为附录序号）
DERIVED = {
    5: ("AGE_YEARS", ["f267", "f001"], 0, "手术日期 − 出生日期，取周岁；无出生日期时允许手工录入并标记（A-FLD-005）"),
    15: ("BMI", ["f014", "f013"], 1, "体重 Kg ÷ (身高cm ÷ 100)²，保留 1 位小数（用户已确认）"),
    18: ("DAYS_INCLUSIVE", ["f001", "f017"], 0, "出院日期 − 手术日期 + 1（含当天；出院≥手术才计算，用户已确认）"),
    140: ("MINUTES_BETWEEN", ["f138", "f139"], 0, "手术结束时间 − 手术开始时间（分钟，向下取整，用户已确认）"),
    149: ("SUM", ["f150", "f151"], 0, "动脉桥数量 + 静脉桥数量（可校验）"),
    189: ("HOURS_BETWEEN_1DP", ["f187", "f188"], 1, "出ICU时间 − 进ICU时间（小时，保留 1 位小数，如 160小时54分→160.9，用户已确认）"),
}

# 软范围（提示可保存，A-FLD-003）：身高承接 V28 演示口径，满足验收「215 黄色提示可保存、300 不可保存」
SOFT_RANGE = {13: (130, 210)}


def cond(field: str, op: str, value) -> dict:
    return {"any": [{"field": field, "op": op, "value": value}]}


COND_EMPTY = {"any": [{"field": "f223", "op": "notEmpty"}]}
COND_DEATH = cond("f215", "eq", 1)
COND_ECHO = cond("f236", "eq", 1)
COND_CPB = cond("f141", "eq", 1)

# 条件显示 / 条件必填（A-FLD-004）：#序号 → (visible_when, required_when)
CONDITIONS = {
    142: (COND_CPB, None),
    143: (COND_CPB, None),
    216: (COND_DEATH, COND_DEATH),
    218: (COND_DEATH, COND_DEATH),
    220: (None, cond("f219", "eq", 1)),
    222: (None, cond("f221", "ne", 0)),
    224: (COND_EMPTY, COND_EMPTY),
    226: (None, cond("f225", "ne", 0)),
    229: (None, cond("f227", "ne", 0)),
    231: (None, cond("f230", "eq", 1)),
    232: (None, cond("f230", "eq", 1)),
    234: (None, cond("f233", "ne", 0)),
}
# 复查超声=是 → 超声指标全部显示（#237 还要必填，A-FLD-004 / A-FLD-007 验收）
for no in range(237, 259):
    if no not in CONDITIONS:
        CONDITIONS[no] = (COND_ECHO, COND_ECHO if no == 237 else None)

# 帮助说明（A-FLD-001「保留原表判定口径」；其余字段的口径已在选项 / 范围元数据里）
HELP = {
    2: "敏感字段：默认脱敏，明文需敏感权限；加密存储 + 盲索引唯一（A-PAT-007）",
    3: "敏感字段：默认脱敏，明文需敏感权限；加密存储 + 盲索引精确检索",
    4: "编码 1=男 / 2=女（建议）；患者主档存 M / F（patient.md §1 口径）",
    5: None,  # 派生公式已在派生定义，见 DERIVED
    7: "病区/中心字典（可配置，独立于科室；A-FLD-012 / A-WARD-001 病区管理）",
    8: "术者业务字典（surgeon，可配置；A-FLD-012）；与字典的绑定列随 E21-T04 落地",
    9: "敏感字段：默认脱敏，明文需敏感权限；加密存储，存入联系方式版本表（pt_contact，E13-T03）",
    10: "敏感字段：默认脱敏，明文需敏感权限；加密存储，存入联系方式版本表（pt_contact，E13-T03）",
    12: "患者类型业务字典（patient_type，可配置；A-FLD-012，平台库 V36 预置示例项）",
    27: "判定：有慢性肾衰病史，或术前肌酐 >133 μmol/L（任一次超标即算）",
    46: "表中未写编码，按 0=否、1=是 处理（需医院确认，A 规格 §3）",
    58: "单位按 μmol/L（医院已确认；原表 mmol/L，字段编码保留原列名）",
    71: "建议：经胸 / 经食道 / 床旁；原表要求床旁超声备注「床旁」",
    142: "体外循环=有 时填写",
    143: "体外循环=有 时填写",
    153: "表中未写编码，按 0=否、1=是 处理（需医院确认，A 规格 §3）",
    154: "表中未写编码，按 0=否、1=是 处理（需医院确认，A 规格 §3）",
    155: "表中未写编码，按 0=否、1=是 处理（需医院确认，A 规格 §3）",
    156: "表中未写编码，按 0=否、1=是 处理（需医院确认，A 规格 §3）",
    177: "原表写作「1是」，按 1=是 处理",
    213: "建议同时记入联系方式（A 规格第 2 章）",
    214: "本次实际随访（联系完成）日期",
    217: "与 #216 死亡日期合并为同一字段（Q22：记录患者死亡日期，不计算时长）；导入时两列都有值且不一致的行报警告",
    218: "记录最具体原因，如「心梗死亡」而非「心源性死亡」",
    228: "原表「主动动脉瓣」按「主动脉瓣」处理（A 规格 §3 已确认笔误修正）",
}

# A-FLD-006 规则链（OQ-64①）：引擎只校验 JSON 存储字段（FormDataValidator 拒绝实体列键入 JSON），
# 主档日期链（入院 ≤ 手术 ≤ 出院、出生 ≤ 手术、死亡 ≥ 出院、随访事件在出院与随访日期之间）
# 端点为实体列 / 跨表单，随患者域编辑校验落地（E13-T02 / E15），不进 fe_rule。
RULES = [
    ("surgery_start_lt_end", "surgery", "f139", "lt", "f138", "f139", "手术开始时间须早于手术结束时间", 10),
    ("icu_in_lt_out", "periop", "f188", "lt", "f187", "f188", "进ICU时间须早于出ICU时间", 20),
]


def clean(raw: str) -> str:
    value = raw.strip()
    paren = value.find("（")
    if paren > 0:
        value = value[:paren].strip()
    return "主动脉瓣" if value == "主动动脉瓣" else value


def parse_options(spec: str) -> dict[int, str]:
    head = spec.split("；", 2)[0].strip()
    if "字典（可配置" in head:
        return {}
    if head.startswith("取值：") or head.startswith("建议："):
        labels = head[3:].strip().split(" / ")
        base = 1 if head.startswith("建议：") else 0
        return {base + i: clean(label) for i, label in enumerate(labels)}
    options: dict[int, str] = {}
    for part in head.split(" / "):
        if "=" in part:
            code, label = part.split("=", 1)
            options[int(code)] = clean(label)
    return options


def parse_appendix() -> list[dict]:
    lines = SPEC.read_text(encoding="utf-8").splitlines()
    rows = []
    in_appendix = False
    for line in lines:
        if line.startswith("## 附录 A."):
            in_appendix = True
            continue
        if in_appendix and line.startswith("## "):
            break
        if not in_appendix:
            continue
        match = ROW_RE.match(line)
        if match:
            rows.append(
                {
                    "no": int(match.group(1)),
                    "label": match.group(2).strip(),
                    "code": match.group(3).strip(),
                    "type": match.group(4).strip(),
                    "spec": match.group(5).strip(),
                }
            )
    if len(rows) != 266:
        sys.exit(f"附录 A 应解析出 266 行，实际 {len(rows)}")
    return rows


def check(rows: list[dict], fields: list[dict]) -> None:
    by_no = {field["no"]: field for field in fields}
    type_dist: dict[str, int] = {}
    for row in rows:
        type_dist[row["type"]] = type_dist.get(row["type"], 0) + 1
    expect_types = {"是否": 94, "单选": 43, "数值": 77, "整数": 4, "数值×2": 3, "日期": 18,
                    "日期时间": 4, "文本": 11, "文本（敏感）": 4, "引用": 2, "派生·整数": 3, "派生·数值": 3}
    if type_dist != expect_types:
        sys.exit(f"附录类型分布与规格 §3「字段类型」表不一致：{type_dist}")
    cat_dist: dict[str, int] = {}
    for field in fields:
        if field["key"] != "f267":
            cat_dist[field["category"]] = cat_dist.get(field["category"], 0) + 1
    expect_cats = {"basic": 12, "case": 37, "lab": 18, "echo": 49, "angio": 18, "surgery": 42,
                   "periop": 16, "discharge_med": 20, "fu_contact": 6, "fu_event": 17,
                   "fu_echo": 23, "fu_med": 8}
    if cat_dist != expect_cats:
        sys.exit(f"分类字段数与规格 §3「9 个分类概览」不一致：{cat_dist}")
    required = [row["no"] for row in rows if "必填" in row["spec"] and "时必填" not in row["spec"]]
    if required != [1, 2, 3, 4, 7, 16, 17, 191, 214, 215]:
        sys.exit(f"基础必填项与附录不一致：{required}")
    sensitive = sorted(no for no, field in by_no.items() if field["sensitive"])
    if sensitive != [2, 3, 9, 10]:
        sys.exit(f"敏感字段应为姓名 / 病案号 / 电话1 / 电话2：{sensitive}")
    if by_no[217]["status"] != "DISABLED":
        sys.exit("#217 死亡随访时间应停用（与 #216 合并，Q22）")
    codes_seen = [field["code"] for field in fields]
    if len(set(codes_seen)) != len(codes_seen):
        sys.exit("字段编码重复")
    import re as _re

    for field in fields:
        if not _re.fullmatch(r"[A-Za-z0-9_]{1,64}", field["code"]):
            sys.exit(f"字段编码必须是字母/数字/下划线（≤64）：{field['key']} {field['code']}")
        if not (field["help"] or "").startswith("原表列名「") and field["key"] != "f267":
            sys.exit(f"{field['key']} 帮助说明应带原表列名锚点")


def sqlescape(text: str) -> str:
    return text.replace("'", "''")


def sqlstr(text) -> str:
    return "NULL" if text is None else "'" + sqlescape(str(text)) + "'"


def jsonlit(value) -> str:
    import json

    return "NULL" if value is None else "'" + json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "'"


def numlit(value) -> str:
    return "NULL" if value is None else str(value)


def build_fields(rows: list[dict]) -> list[dict]:
    fields = []
    for row in rows:
        no = row["no"]
        form, category = category_of(no)
        appendix_type = row["type"]
        fe_type = TYPE_MAP[appendix_type]
        options = parse_options(row["spec"])
        option_set = None
        if options:
            signature = "|".join(f"{code}={label}" for code, label in options.items())
            if signature not in OPTION_SETS:
                sys.exit(f"#{no} 选项未登记选项集：{signature}")
            option_set = OPTION_SETS[signature][0]
        unit_match = UNIT_RE.search(row["spec"])
        unit = clean(unit_match.group(1)) if unit_match else None
        if appendix_type == "数值×2":
            unit = "mm"  # 拆为长、宽两个数值（mm），页面显示「长 × 宽」
        range_match = RANGE_RE.search(row["spec"])
        hard = (float(range_match.group(1)), float(range_match.group(2))) if range_match else None
        decimals = None
        if appendix_type == "整数":
            decimals = 0
        derived_def = None
        derived_help = None
        if no in DERIVED:
            fn, args, decimals, derived_help = DERIVED[no]
            derived_def = {"fn": fn, "args": args}
        visible, required_when = CONDITIONS.get(no, (None, None))
        sensitive = 1 if appendix_type == "文本（敏感）" else 0
        if no in COLUMN_ROWS:
            storage = "column"
        elif sensitive:
            storage = "encrypted"
        else:
            storage = "json"
        # 帮助说明：原表列名锚点 + 字段口径（A-FLD-001「保留原表判定口径」）；编码用 ASCII（OQ-72）
        origin = f"原表列名「{row['code']}」"
        detail = derived_help or HELP.get(no)
        help_text = origin + ("；" + detail if detail else "")
        fields.append(
            {
                "no": no,
                "key": f"f{no:03d}",
                "form": form,
                "code": CODES[no],
                "label": row["label"],
                "category": category,
                "type": fe_type,
                "unit": unit,
                "decimals": decimals,
                "hard": hard,
                "soft": SOFT_RANGE.get(no),
                "option_set": option_set,
                "options": options,
                "required": 1 if ("必填" in row["spec"] and "时必填" not in row["spec"]) else 0,
                "visible_when": visible,
                "required_when": required_when,
                "derived_def": derived_def,
                "sensitive": sensitive,
                "storage": storage,
                "help": help_text,
                "sort": no * 10,
                "status": "DISABLED" if no == 217 else "ENABLED",
                "appendix_type": appendix_type,
            }
        )
    # A-FLD-013 出生日期（附录 266 之外的新增字段）：排在进行年龄（f005）之前
    fields.append(
        {
            "no": 267,
            "key": "f267",
            "form": "A_BASELINE",
            "code": "birth_date",
            "label": "出生日期",
            "category": "basic",
            "type": "date",
            "unit": None,
            "decimals": None,
            "hard": None,
            "soft": None,
            "option_set": None,
            "options": {},
            "required": 0,
            "visible_when": None,
            "required_when": None,
            "derived_def": None,
            "sensitive": 0,
            "storage": "column",
            "help": "A-FLD-013 新增：用于自动计算手术年龄；基本信息不采集身份证号",
            "sort": 45,
            "status": "ENABLED",
            "appendix_type": None,
        }
    )
    fields.sort(key=lambda field: (field["form"], field["sort"]))
    return fields


def build_option_rows(fields: list[dict]) -> tuple[list[tuple], list[tuple]]:
    """用到的选项集（按编码排序）与选项行（set_code, option_code, label, ordinal, sort_no）。"""
    seen: dict[str, list[tuple]] = {}
    for field in fields:
        if not field["option_set"]:
            continue
        code, _name = OPTION_SETS[
            "|".join(f"{c}={label}" for c, label in field["options"].items())
        ]
        if code not in seen:
            rows = []
            for option_code, label in field["options"].items():
                ordinal = option_code if code == "a_mr_reflux" else None
                rows.append((code, option_code, label, ordinal, (option_code + 1) * 10))
            seen[code] = rows
    sets = [
        (code, name)
        for _signature, (code, name) in sorted(OPTION_SETS.items(), key=lambda item: item[1][0])
        if code in seen
    ]
    rows = []
    for code, _name in sets:
        rows.extend(seen[code])
    return sets, rows


def emit(fields: list[dict]) -> str:
    sets, option_rows = build_option_rows(fields)
    baseline = [field for field in fields if field["form"] == "A_BASELINE"]
    out = []
    out.append("-- E23-T01 / A-FLD-001、A-FLD-013：医院 A 字段字典全量录入（附录 A 的 266 个字段 + 新增出生日期）。")
    out.append("-- 由 scripts/gen_a_field_dict.py 从 docs/requirements/hospital-a-spec.md 附录 A 生成，")
    out.append("-- 与 AFieldDictSqlTest（附录 ↔ 迁移逐项核对）、AFieldDictVerifyIT（真实库核对）同口径；")
    out.append("-- 改附录或映射后重跑生成脚本再提交，不要手改本文件。")
    out.append("--")
    out.append("-- 字段编码为 ASCII（E11-T09 导入工具口径，OQ-72）；附录原表列名（中文）完整保留在 help_text「原表列名「…」」。")
    out.append("-- 表单：A_BASELINE 基线档案（分类 1–8，212 字段 + 出生日期）与 A_ANNUAL_FU 年度随访记录")
    out.append("--   （分类 9，54 字段；随访四分组 A-FLD-007）。#217 死亡随访时间与 #216 死亡日期按 Q22 合并为同一")
    out.append("--   字段：登记在册但停用（停用不进模板下发与校验），导入两列不一致报警告随 E18。")
    out.append("-- 替换 V28 / V31 / V33 / V36 的演示种子（示例字段、yesno 选项集、演示规则与记录）：")
    out.append("--   演示记录及其字段历史一并清理（全部虚构数据，规则 5）；v1 版本快照按 V29 同款重建。")
    out.append("-- fe_field.carry_over / dict_code 列与 FormSchema 下发随 E21-T04 字段字典录入配套迁移（OQ-67① / OQ-69④）。")
    out.append("")
    out.append("-- ① 清理演示种子（V28 演示表单 A_BASELINE 的字段 / 选项集 / 规则 / 记录与快照）")
    out.append("DELETE fh FROM fe_field_history fh")
    out.append("  JOIN fe_record r ON fh.record_type = 'FE_RECORD' AND fh.record_id = r.id")
    out.append(" WHERE r.form_code = 'A_BASELINE';")
    out.append("DELETE FROM fe_record WHERE form_code = 'A_BASELINE';")
    out.append("DELETE FROM fe_form_version_field WHERE form_code = 'A_BASELINE';")
    out.append("DELETE FROM fe_form_version WHERE form_code = 'A_BASELINE';")
    out.append("DELETE FROM fe_field WHERE form_code = 'A_BASELINE';")
    out.append("DELETE FROM fe_rule WHERE form_code = 'A_BASELINE';")
    out.append("DELETE FROM fe_option WHERE set_code = 'yesno';")
    out.append("DELETE FROM fe_option_set WHERE code = 'yesno';")
    out.append("")
    out.append("-- ② 表单与分类（9 个分类概览 / 随访四分组，锚点键稳定）")
    categories = ",\n  ".join(
        f"JSON_OBJECT('key', '{key}', 'label', '{label}', 'sortNo', {sort})"
        for key, label, sort in CATEGORIES["A_BASELINE"]
    )
    out.append(
        "UPDATE fe_form SET name = '基线档案', current_version = 1, categories = JSON_ARRAY(\n  "
        + categories
        + ") WHERE code = 'A_BASELINE';"
    )
    fu_categories = ",\n  ".join(
        f"JSON_OBJECT('key', '{key}', 'label', '{label}', 'sortNo', {sort})"
        for key, label, sort in CATEGORIES["A_ANNUAL_FU"]
    )
    out.append("")
    out.append("INSERT INTO fe_form (code, name, usage_type, current_version, categories) VALUES")
    out.append(
        "('A_ANNUAL_FU', '年度随访记录', 'FOLLOWUP', 1, JSON_ARRAY(\n  "
        + fu_categories
        + "));"
    )
    out.append("")
    out.append("-- ③ 选项集与选项（A-FLD-002：数据库存编码、页面显示显示名；编码创建后不可改）")
    out.append("INSERT INTO fe_option_set (code, name) VALUES")
    out.append(
        ",\n".join(f"  ({sqlstr(code)}, {sqlstr(name)})" for code, name in sets) + ";"
    )
    out.append("")
    out.append("-- a_mr_reflux 带序数：无=0、微量=1、少量=2、中量=3、大量=4（「返流加重 ≥ 2 级」按序数比较）")
    out.append("INSERT INTO fe_option (set_code, option_code, label, ordinal, sort_no) VALUES")
    out.append(
        ",\n".join(
            f"  ({sqlstr(code)}, {option_code}, {sqlstr(label)}, {numlit(ordinal)}, {sort})"
            for code, option_code, label, ordinal, sort in option_rows
        )
        + ";"
    )
    out.append("")
    out.append("-- ④ 字段字典（267 行 = 附录 266 + 出生日期 f267；JSON 列以字符串字面量书写）")
    out.append(
        "INSERT INTO fe_field\n  (form_code, field_key, field_code, label, category_key, type, unit,"
        " decimals, hard_range, soft_range,\n   option_set_code, required, visible_when,"
        " required_when, derived_def, sensitive_flag, storage_type, help_text, sort_no, status)"
    )
    out.append("VALUES")
    lines = []
    for field in fields:
        hard = (
            None
            if field["hard"] is None
            else {"min": int(field["hard"][0]) if field["hard"][0].is_integer() else field["hard"][0],
                  "max": int(field["hard"][1]) if field["hard"][1].is_integer() else field["hard"][1]}
        )
        soft = (
            None
            if field["soft"] is None
            else {"min": field["soft"][0], "max": field["soft"][1]}
        )
        values = [
            sqlstr(field["form"]),
            sqlstr(field["key"]),
            sqlstr(field["code"]),
            sqlstr(field["label"]),
            sqlstr(field["category"]),
            sqlstr(field["type"]),
            sqlstr(field["unit"]),
            numlit(field["decimals"]),
            jsonlit(hard),
            jsonlit(soft),
            sqlstr(field["option_set"]),
            str(field["required"]),
            jsonlit(field["visible_when"]),
            jsonlit(field["required_when"]),
            jsonlit(field["derived_def"]),
            str(field["sensitive"]),
            sqlstr(field["storage"]),
            sqlstr(field["help"]),
            str(field["sort"]),
            sqlstr(field["status"]),
        ]
        lines.append("(" + ", ".join(values) + ")")
    out.append(",\n".join(lines) + ";")
    out.append("")
    out.append("-- ⑤ A-FLD-006 规则链（OQ-64①）：引擎可表达的两条（JSON 存储字段对，HARD 拦截 42200）；")
    out.append("--   入院 ≤ 手术 ≤ 出院、出生 ≤ 手术、死亡 ≥ 出院、随访事件窗口的端点为实体列 / 跨表单，")
    out.append("--   随患者域编辑校验落地（E13-T02 / E15），不进 fe_rule。")
    out.append(
        "INSERT INTO fe_rule\n  (form_code, rule_id, category_key, target_field, severity,"
        " rule_type, params, message, sort_no)"
    )
    out.append("VALUES")
    rule_lines = []
    for rule_id, category, target, op, left, right, message, sort in RULES:
        params = jsonlit({"leftField": left, "op": op, "rightField": right})
        rule_lines.append(
            f"('A_BASELINE', {sqlstr(rule_id)}, {sqlstr(category)}, {sqlstr(target)}, 'HARD',"
            f" 'COMPARE', {params}, {sqlstr(message)}, {sort})"
        )
    out.append(",\n".join(rule_lines) + ";")
    out.append("")
    out.append("-- ⑥ v1 版本快照重建（V29 同款：发布机制上线即有可按版本读取的模板）")
    for form in ("A_BASELINE", "A_ANNUAL_FU"):
        out.append(
            f"INSERT INTO fe_form_version (form_code, version, categories, field_count, published_by)\n"
            f"SELECT fm.code, fm.current_version, fm.categories,\n"
            f"       (SELECT COUNT(*) FROM fe_field f WHERE f.form_code = fm.code AND f.status = 'ENABLED'),\n"
            f"       NULL\n"
            f"FROM fe_form fm WHERE fm.code = '{form}';"
        )
        out.append(
            "INSERT INTO fe_form_version_field\n"
            "  (version_id, form_code, version, field_key, field_code, label, category_key, type,\n"
            "   unit, decimals, hard_range, soft_range, option_set_code, required,\n"
            "   visible_when, required_when, derived_def, sensitive_flag, storage_type, help_text, sort_no)\n"
            "SELECT v.id, f.form_code, v.version, f.field_key, f.field_code, f.label, f.category_key, f.type,\n"
            "       f.unit, f.decimals, f.hard_range, f.soft_range, f.option_set_code, f.required,\n"
            "       f.visible_when, f.required_when, f.derived_def, f.sensitive_flag, f.storage_type,\n"
            "       f.help_text, f.sort_no\n"
            "FROM fe_field f\n"
            "JOIN fe_form_version v ON v.form_code = f.form_code AND v.version = 1\n"
            f"WHERE f.form_code = '{form}' AND f.status = 'ENABLED';"
        )
        out.append("")
    return "\n".join(out).rstrip() + "\n"


def main() -> int:
    rows = parse_appendix()
    fields = build_fields(rows)
    check(rows, fields)
    sql = emit(fields)
    OUT.write_text(sql, encoding="utf-8")
    print(f"已生成 {OUT.relative_to(ROOT)}：{len(fields)} 字段（附录 266 + 出生日期）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
