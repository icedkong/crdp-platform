#!/usr/bin/env python3
"""E23-T20：从医院 C 规格「附录 A. 字段字典全表（97 个字段）」生成租户库迁移脚本。

用法：python3 scripts/gen_c_field_dict.py
  读 docs/requirements/hospital-c-spec.md 附录 A（验收基准），按本脚本登记的映射
  （选项集、条件、派生、字典绑定、存储、帮助说明）生成
  backend/crdp-server/src/main/resources/db/tenant/followup/V90__hospital_c_field_dict.sql。
生成前后各做一次核对（附录 97 行 = 患者 21 + 随访 34 + 植入 13 + 电极 6 + 随访新增 7 + 报告 16；
  fe_field 落 81 行 = 97 − 报告 16〔报告单字段由 rpt_report 通用模型与 E23-T21 类型字段集承载〕），
  与 CFieldDictSqlTest（附录 ↔ 迁移逐项核对）同一口径；改附录或映射后重跑本脚本再提交。

键位契约（OQ-130①，E21 任务先行声明，录入必须沿用）：
  附录随访组 #n → f3nn（程控组占用 f301–f334，新增组 f335–f341）；
  f303 编码 followup_date、f305 编码 visit_doctor（OQ-136⑤ / E21-T11）；
  ERI 状态选项集 c_eri_status 编码 0=正常 / 1=接近ERI / 2=已达ERI(EOI)；
  分类锚点 threshold / sensing / impedance / battery（程控分组，C-PAT-018）。

主仓库：https://github.com/icedkong/crdp-platform
"""

import re
import sys
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "docs/requirements/hospital-c-spec.md"
OUT = ROOT / "backend/crdp-server/src/main/resources/db/tenant/followup/V90__hospital_c_field_dict.sql"

ROW_RE = re.compile(r"^\|\s*(患者|随访|植入|电极|报告)\s*#(\d+)\s*\|\s*([^|]+?)\s*\|\s*([^|]+?)\s*\|\s*([^|]*?)\s*\|\s*$")


# ---------------------------------------------------------------- 附录分组（顺序 = 纸质表顺序，C-FLD-010）
GROUPS = [
    ("患者", 21, "C_REGISTRATION"),
    ("随访", 34, "C_PROGRAM_FU"),   # 纸质《随访程控记录》34 列
    ("植入", 13, "C_IMPLANT"),
    ("电极", 6, "C_IMPLANT"),
    ("随访", 7, "C_PROGRAM_FU"),    # 建议新增组（第二随访块）
    ("报告", 16, None),             # 报告单通用模型（rpt_report，E16-T02）+ E23-T21 类型字段集，不落 fe_field
]


def parse_appendix():
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
        m = ROW_RE.match(line)
        if m:
            rows.append({"group": m.group(1), "no": int(m.group(2)), "label": m.group(3).strip(),
                         "type": m.group(4).strip(), "spec": m.group(5).strip()})
    # 分组核对：按出现顺序切段（同组相邻行归一段）
    blocks = []
    for row in rows:
        if not blocks or blocks[-1][0]["group"] != row["group"]:
            blocks.append([])
        blocks[-1].append(row)
    if len(rows) != 97:
        sys.exit(f"附录 A 应解析出 97 行，实际 {len(rows)}")
    got = [(b[0]["group"], len(b)) for b in blocks]
    expect = [(g, n) for g, n, _ in GROUPS]
    if got != expect:
        sys.exit(f"附录分组与规格 §3「字段分组概览」不一致：{got} ≠ {expect}")
    for b in blocks:
        if [r["no"] for r in b] != list(range(1, len(b) + 1)):
            sys.exit(f"附录 {b[0]['group']} 组行号不连续：{[r['no'] for r in b]}")
    return blocks


# ---------------------------------------------------------------- 字段表（键 / 编码 / 分类 / 选项 / 条件 / 绑定 / 存储）
def cond_all(field, op, value):
    return {"all": [{"field": field, "op": op, "value": value}]}


def cond_ctx(path, op, value):
    return {"all": [{"ctx": path, "op": op, "value": value}]}


COND_RA = cond_ctx("device.hasRaLead", "eq", True)
COND_ICD = cond_ctx("device.type", "in", ["ICD", "CRT_D"])
COND_AV = cond_ctx("device.chambers", "gte", 2)
COND_POCKET_REQ = {"any": [cond_all("f304", "eq", 1), cond_all("f304", "eq", 2)]}

# 选项集（c_ 前缀 = 医院 C 自有；性别复用 a_gender）。互斥 / 需补充说明按 Q04、Q06、Q07。
OPTION_SETS = {
    "c_visit_method": ("随访方式", [(1, "门诊程控"), (2, "远程监测"), (3, "门诊程控+远程监测")]),
    "c_visit_timepoint": ("随访时间点", [(1, "术后72小时/出院前"), (2, "术后1个月"), (3, "术后3个月"), (4, "术后6个月"),
                                          (5, "术后1年"), (6, "每6-12个月"), (7, "ERI期随访"), (8, "其他")]),
    "c_symptom": ("症状", [(0, "无"), (1, "心悸"), (2, "黑蒙"), (3, "晕厥"), (4, "气促"), (5, "乏力"), (6, "胸痛"),
                            (7, "水肿"), (8, "其他")]),
    "c_arr_event": ("心律失常事件", [(0, "无"), (1, "快房率"), (2, "快室率"), (3, "心房噪音"), (4, "心室噪音")]),
    "c_icd_therapy": ("ICD恰当治疗", [(0, "无"), (1, "ATP"), (2, "SHOCK")]),
    "c_icd_inappropriate": ("ICD不恰当治疗", [(0, "无"), (1, "噪音SHOCK"), (2, "房颤SHOCK"), (3, "室上速误治疗"),
                                                (4, "T波超感知"), (5, "QRS波双计数"), (6, "其他")]),
    "c_complication": ("并发症（并集，Q06）", [(0, "无"), (1, "囊袋感染"), (2, "电极磨损"), (3, "电极穿孔"), (4, "电极脱位"),
                                                (5, "感知不良"), (6, "起搏阈值升高"), (7, "电极阻抗异常"), (8, "囊袋血肿"),
                                                (9, "膈肌刺激"), (10, "气胸"), (11, "其他")]),
    "c_pocket_exam": ("囊袋/伤口检查（Q07）", [(0, "愈合良好"), (1, "红肿"), (2, "渗液"), (3, "血肿"),
                                                (4, "皮肤变薄或破溃"), (5, "感染征象"), (6, "其他")]),
    "c_eri_status": ("ERI状态", [(0, "正常"), (1, "接近ERI"), (2, "已达ERI(EOI)")]),
}
EXCLUSIVE = {"c_symptom": [0], "c_arr_event": [0], "c_icd_therapy": [0], "c_icd_inappropriate": [0],
             "c_complication": [0], "c_pocket_exam": [0]}
NEEDS_DETAIL = {"c_symptom": [8], "c_icd_inappropriate": [6], "c_complication": [11], "c_pocket_exam": [6]}

# 业务字典绑定（fe_field.dict_code，OQ-69④；平台库 V70 预置 + 本院覆盖）
DICT_DEVICE_TYPE = "pacemaker_type"
DICT_BRAND = "pacemaker_brand"
DICT_PACING_MODE = "pacing_mode"
DICT_INDICATION = "implant_indication"

# 字段行：(key, code, label, category, type, unit, decimals, soft, hard, option_set, dict_code, required,
#          visible_when, required_when, derived_def, sensitive, storage, carry_over, sort, help)
# soft/hard 为 (min,max)；sort = 纸质表列序 × 10，新增字段按逻辑位置插入（C-FLD-010 顺序与纸质表一致）。
FIELDS = [
    # ---- C_REGISTRATION 患者登记（纸质表 21 列）----
    ("f001", "visit_no", "随访编号", "basic", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 10,
     "系统生成「前缀-建档年份-3 位流水号」（如 105-2026-001，C-PAT-003 / Q10）；历史患者可手填纸质原编号；规则可配置（E21-T10）"),
    ("f002", "patient_name", "姓名", "basic", "text", None, None, None, None, None, None, 1, None, None, None, 1, "encrypted", 0, 20,
     "敏感字段：默认脱敏，明文需敏感权限；加密存储"),
    ("f003", "gender", "性别", "basic", "select", None, None, None, None, "a_gender", None, 1, None, None, None, 0, "column", 0, 30,
     "编码 1=男 / 2=女；患者主档存 M / F；原表下拉误设在「备注」列（Q15 已纠正）"),
    ("f004", "birth_date", "出生日期", "basic", "date", None, None, None, None, None, None, 1, None, None, None, 0, "column", 0, 40,
     "必填；用于计算年龄"),
    ("f005", "age", "年龄", "basic", "derived", "岁", 0, None, None, None, None, 0, None, None, {"fn": "AGE_YEARS", "args": ["f004"]}, 0, "json", 0, 50,
     "派生：今天 − 出生日期，取周岁（与原表公式一致）；单参 AGE_YEARS 以今天为终点"),
    ("f006", "phone", "联系电话", "basic", "text", None, None, None, None, None, None, 0, None, None, None, 1, "encrypted", 0, 60,
     "敏感字段：默认脱敏，明文需敏感权限；可多个、旧号保留（联系方式版本表）"),
    ("f007", "mrn", "病历号", "basic", "text", None, None, None, None, None, None, 1, None, None, None, 1, "encrypted", 0, 70,
     "敏感字段：默认脱敏；本院唯一、查重（C-PAT-002）"),
    ("f008", "device_type", "起搏器类型", "implant_info", "select", None, None, None, None, None, DICT_DEVICE_TYPE, 1, None, None, None, 0, "column", 0, 80,
     "纸质表列，移入植入记录（pt_implant.device_type）；决定随访计划（C-FU-003）与条件显示（C-FLD-005）"),
    ("f009", "brand", "品牌", "implant_info", "select", None, None, None, None, None, DICT_BRAND, 0, None, None, None, 0, "column", 0, 90,
     "纸质表列，移入植入记录；业务字典 pacemaker_brand"),
    ("f010", "model", "型号", "implant_info", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 100,
     "纸质表列，移入植入记录"),
    ("f011", "pacing_mode", "起搏模式", "implant_info", "select", None, None, None, None, None, DICT_PACING_MODE, 0, None, None, None, 0, "column", 0, 110,
     "统一起搏模式字典（Q08 / C-FLD-003，平台库 V70）；植入时设置的模式"),
    ("f012", "ra_lead_position", "RA电极植入部位", "implant_info", "select", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 120,
     "纸质表列，移入电极记录；字典 lead_position_ra（按腔室过滤，Q15），选项经 GET /implants/options"),
    ("f013", "rv_lead_position", "RV电极植入部位", "implant_info", "select", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 130,
     "纸质表列，移入电极记录；字典 lead_position_rv（按腔室过滤，Q15）"),
    ("f014", "lv_lead_position", "LV电极植入部位", "implant_info", "select", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 140,
     "纸质表列，移入电极记录；字典 lead_position_lv；仅 CRT-P、CRT-D 录入（C-FLD-005）"),
    ("f015", "implant_date", "植入日期", "implant_info", "date", None, None, None, None, None, None, 1, None, None, None, 0, "column", 0, 150,
     "纸质表列，移入植入记录；随访计划起算日；允许过去日期（历史患者建档，C-PAT-008）"),
    ("f016", "implant_hospital", "植入医院", "implant_info", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 160,
     "纸质表列，移入植入记录；默认本院"),
    ("f017", "implant_doctor", "植入医生", "implant_info", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 170,
     "纸质表列，移入植入记录；本院医生从用户中选择，外院可手填"),
    ("f018", "indication", "植入指征", "implant_info", "select", None, None, None, None, None, DICT_INDICATION, 0, None, None, None, 0, "column", 0, 180,
     "纸质表列，移入植入记录；可配置字典并保留文字补充（Q16，指征补充说明随植入记录）"),
    ("f019", "visit_method", "随访方式", "followup", "select", None, None, None, None, "c_visit_method", None, 0, None, None, None, 0, "column", 0, 190,
     "患者默认随访方式，程控记录录入时带入（C-FU-008）"),
    ("f020", "follower", "责任随访人", "followup", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 200,
     "本科室病区的医生、随访员或护士；任务默认负责人"),
    ("f021", "note", "备注", "followup", "textarea", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 210,
     None),
    # ---- C_IMPLANT 植入记录（附录植入 #1–13 → f101–f113；附 V83 note / Q16 指征补充 / 结束原因三列）----
    ("f101", "implant_no", "植入序号", "device", "derived", None, 0, None, None, None, None, 0, None, None, None, 0, "column", 0, 10,
     "派生·系统：按植入日期排序编号（第 1 次、第 2 次……），列表展示层计算"),
    ("f102", "implant_type", "植入类型", "device", "select", None, None, None, None, None, None, 1, None, None, None, 0, "column", 0, 20,
     "必填（Q01、Q16）；系统枚举 FIRST 首次植入 / REPLACE 更换（电池耗竭）/ UPGRADE 升级 / LEAD_REVISION 电极修正 / OTHER 其他（须填说明 note，OQ-132②）"),
    ("f103", "implant_date", "植入日期", "device", "date", None, None, None, None, None, None, 1, None, None, None, 0, "column", 0, 30,
     "必填；计划起算日；允许过去日期"),
    ("f104", "device_type", "起搏器类型", "device", "select", None, None, None, None, None, DICT_DEVICE_TYPE, 1, None, None, None, 0, "column", 0, 40,
     "见患者登记 #8；必填；决定随访计划与条件显示"),
    ("f105", "brand", "品牌", "device", "select", None, None, None, None, None, DICT_BRAND, 0, None, None, None, 0, "column", 0, 50,
     "见患者登记 #9"),
    ("f106", "model", "型号", "device", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 60, None),
    ("f107", "serial_no", "设备序列号", "device", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 70,
     "选填（Q16 / C-FLD-008 新增）"),
    ("f108", "pacing_mode", "起搏模式", "device", "select", None, None, None, None, None, DICT_PACING_MODE, 0, None, None, None, 0, "column", 0, 80,
     "统一起搏模式字典（Q08）"),
    ("f109", "hospital", "植入医院", "device", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 90,
     "默认本院"),
    ("f110", "doctor", "植入医生", "device", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 100,
     "本院医生从用户中选择，外院可手填"),
    ("f111", "indication", "植入指征", "device", "select", None, None, None, None, None, DICT_INDICATION, 0, None, None, None, 0, "column", 0, 110,
     "可配置字典（Q16）；文字补充见指征补充说明"),
    ("f112", "status", "状态", "device", "select", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 120,
     "枚举·系统：ACTIVE 在用 / REPLACED 已更换 / REMOVED 已取出；同一患者仅一条在用（C-PAT-007）"),
    ("f113", "end_date", "结束日期与原因", "device", "date", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 130,
     "更换时自动填为新植入日期；原因（end_reason）随行维护；取出时记已取出"),
    ("f114", "indication_note", "指征补充说明", "device", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 115,
     "Q16：植入指征选「其他」或需文字补充时填写（pt_implant.indication_note，V81）"),
    ("f115", "note", "说明", "device", "textarea", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 125,
     "植入类型为「其他」时必填（OQ-132②，pt_implant.note，V83）"),
    ("f116", "end_reason", "结束原因", "device", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 135,
     "结束日期的配套原因文字（更换 / 取出时随行维护）"),
    # ---- C_IMPLANT 电极记录（附录电极 #1–6 → f121–f126）----
    ("f121", "parent_implant", "所属植入记录", "lead", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 10,
     "引用；沿用的电极可挂在新植入记录下（保留原植入日期，E21-T02）"),
    ("f122", "chamber", "腔室", "lead", "select", None, None, None, None, None, None, 1, None, None, None, 0, "column", 0, 20,
     "必填；枚举·系统：RA / RV / LV（pt_implant_lead.chamber）；无导线起搏器不录电极"),
    ("f123", "position", "植入部位", "lead", "select", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 30,
     "按腔室过滤（lead_position_ra / rv / lv，Q15）；选项经 GET /implants/options 下发，不绑单一字典"),
    ("f124", "lead_model", "电极型号", "lead", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 40,
     "选填"),
    ("f125", "lead_implant_date", "电极植入日期", "lead", "date", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 50,
     "默认同植入记录；沿用电极保留原日期（跨器械，C-PAT-006）"),
    ("f126", "lead_status", "状态", "lead", "select", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 60,
     "枚举·系统：ACTIVE 在用 / ABANDONED 废弃留置 / EXTRACTED 已拔除（OQ-132③）"),
    # ---- C_PROGRAM_FU 随访程控记录（纸质表 34 列 → f301–f334；建议新增组 → f335–f341）----
    ("f301", "visit_no", "随访编号", "overview", "derived", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 10,
     "引用：关联患者，系统自动带入（C-FU-008）"),
    ("f302", "sequence_no", "随访序号", "overview", "derived", None, 0, None, None, None, None, 0, None, None, None, 0, "json", 0, 20,
     "派生·系统：同一患者按随访日期升序编号、补录后自动重排（Q13 / C-FU-018，E21-T11 序号派生）"),
    ("f303", "followup_date", "随访日期", "overview", "date", None, None, None, None, None, None, 1, None, None, None, 0, "json", 0, 30,
     "必填；不早于当时器械植入日期、不晚于今天（服务层校验）；补录可填过去日期（重复提示按本字段匹配，OQ-136⑤）"),
    ("f304", "visit_timepoint", "随访时间点", "overview", "select", None, None, None, None, "c_visit_timepoint", None, 1, None, None, None, 0, "json", 0, 40,
     "必填；任务录入由子计划带入、补录按日期建议（C-FU-014）；原表下拉只设在一格（Q15 已纠正）"),
    ("f305", "visit_doctor", "随访医生", "overview", "text", None, None, None, None, None, None, 0, None, None, None, 0, "json", 0, 50,
     "用户引用；默认当前用户（C-FU-008 自动带入，E21-T11 entryDefaults 写入本字段）"),
    ("f306", "visit_method", "随访方式", "overview", "select", None, None, None, None, "c_visit_method", None, 0, None, None, None, 0, "json", 0, 60,
     "默认取患者随访方式（C-FU-008）；远程监测同表单人工录入（C-FU-025）"),
    ("f307", "symptom", "症状", "symptom", "multiselect", None, None, None, None, "c_symptom", None, 0, None, None, None, 0, "json", 0, 70,
     "多选（Q04）；「无」互斥、选「其他」须填说明"),
    ("f337", "pocket_exam", "囊袋/伤口检查", "symptom", "multiselect", None, None, None, None, "c_pocket_exam", None, 0, None, None, None, 0, "json", 0, 75,
     "C-FLD-008 新增（Q07）：「愈合良好」互斥、选「其他」须填说明；术后72小时、1个月默认必填"),
    ("f308", "ra_threshold", "心房起搏阈值(V)", "threshold", "number", "V", 2, (0.1, 10.0), None, None, None, 0, COND_RA, None, None, 0, "json", 0, 80,
     "有在用 RA 电极时显示（C-FU-009）；与上次比较标红（fu_compare_rule ra_threshold_rise）；建议范围 0.1–10.0 为软校验（C-FLD-007）"),
    ("f309", "ra_pulse_width", "心房脉宽(ms)", "threshold", "number", "ms", 2, (0.03, 2.00), None, None, None, 0, COND_RA, None, None, 0, "json", 0, 90,
     "有在用 RA 电极时显示；脉宽不同时阈值对比注明仅供参考（Q02）"),
    ("f310", "ra_sensing", "心房感知P波(mV)", "sensing", "number", "mV", None, (0.1, 20.0), None, None, None, 0, COND_RA, None, None, 0, "json", 0, 100,
     "有在用 RA 电极时显示；与上次比较（感知明显下降 >50% 标红）"),
    ("f311", "ra_impedance", "心房阻抗(Ω)", "impedance", "number", "Ω", 0, (100, 3000), None, None, None, 0, COND_RA, None, None, 0, "json", 0, 110,
     "整数；有在用 RA 电极时显示；与上次比较（波动 >50% 标红）"),
    ("f312", "ra_pacing_ratio", "心房起搏比例(%)", "pacing", "number", "%", None, (0, 100), None, None, None, 0, COND_RA, None, None, 0, "json", 0, 120,
     "有在用 RA 电极时显示"),
    ("f313", "rv_threshold", "心室起搏阈值(V)", "threshold", "number", "V", 2, (0.1, 10.0), None, None, None, 0, None, None, None, 0, "json", 0, 130,
     "与上次比较标红（rv_threshold_rise）"),
    ("f314", "rv_pulse_width", "心室脉宽(ms)", "threshold", "number", "ms", 2, (0.03, 2.00), None, None, None, 0, None, None, None, 0, "json", 0, 140, None),
    ("f315", "rv_sensing", "心室感知R波(mV)", "sensing", "number", "mV", None, (0.1, 30.0), None, None, None, 0, None, None, None, 0, "json", 0, 150,
     "与上次比较（感知明显下降 >50% 标红）"),
    ("f316", "rv_impedance", "心室阻抗(Ω)", "impedance", "number", "Ω", 0, (100, 3000), None, None, None, 0, None, None, None, 0, "json", 0, 160,
     "整数；与上次比较（波动 >50% 标红；心室阻抗 5000 超范围提示、确认后可保存并记确认人）"),
    ("f317", "rv_pacing_ratio", "心室起搏比例(%)", "pacing", "number", "%", None, (0, 100), None, None, None, 0, None, None, None, 0, "json", 0, 170, None),
    ("f318", "programmed_mode", "程控起搏模式", "pacing", "select", None, None, None, None, None, DICT_PACING_MODE, 0, None, None, None, 0, "json", 1, 180,
     "统一起搏模式字典（Q08 / C-FLD-003）；带入上次设置项（C-FU-016，carry_over）"),
    ("f319", "lower_rate", "下限频率(bpm)", "pacing", "number", "bpm", 0, (30, 150), None, None, None, 0, None, None, None, 0, "json", 1, 190,
     "带入上次设置项（C-FU-016）"),
    ("f320", "upper_rate", "上限频率(bpm)", "pacing", "number", "bpm", 0, (60, 200), None, None, None, 0, None, None, None, 0, "json", 1, 200,
     "须大于下限频率（fe_rule 硬校验拦截保存，C-FLD-007）；带入上次设置项（C-FU-016）"),
    ("f321", "av_delay", "AV间期(ms)", "pacing", "number", "ms", 0, (30, 350), None, None, None, 0, COND_AV, None, None, 0, "json", 1, 210,
     "双腔及以上显示（device.chambers ≥ 2，C-FU-009）；带入上次设置项（C-FU-016）"),
    ("f322", "battery_voltage", "电池电压(V)", "battery", "number", "V", 2, (0, 4.0), None, None, None, 0, None, None, None, 0, "json", 0, 220, None),
    ("f323", "eri_status", "ERI状态", "battery", "select", None, None, None, None, "c_eri_status", None, 0, None, None, None, 0, "json", 0, 230,
     "非「正常」时标红（fu_compare_rule eri_status_abnormal）并切换 ERI 期随访（C-FU-006 / E21-T06，键契约 OQ-130①）"),
    ("f324", "battery_life", "预计电池寿命(年)", "battery", "number", "年", 1, (0, 20), None, None, None, 0, None, None, None, 0, "json", 0, 240,
     "低于阈值（默认 1 年）标记接近 ERI（Q13；≤ 1 视同接近 ERI，E21-T06）"),
    ("f325", "magnet_rate", "磁铁频率(bpm)", "battery", "number", "bpm", 0, (30, 120), None, None, None, 0, None, None, None, 0, "json", 0, 250, None),
    ("f326", "mode_switch_count", "模式转换次数", "battery", "number", None, 0, (0, None), None, None, None, 0, None, None, None, 0, "json", 0, 260,
     "自上次程控以来；≥ 0"),
    ("f327", "ataf_load", "AT/AF负荷(%)", "battery", "number", "%", None, (0, 100), None, None, None, 0, None, None, None, 0, "json", 0, 270, None),
    ("f328", "arrhythmia_event", "心律失常事件", "symptom", "multiselect", None, None, None, None, "c_arr_event", None, 0, None, None, None, 0, "json", 0, 280,
     "多选（Q04）；「无」互斥"),
    ("f329", "icd_appropriate", "ICD恰当治疗", "icd", "multiselect", None, None, None, None, "c_icd_therapy", None, 0, COND_ICD, None, None, 0, "json", 0, 290,
     "仅 ICD、CRT-D 显示（Q19 / C-FU-009）"),
    ("f330", "icd_inappropriate", "ICD不恰当治疗", "icd", "multiselect", None, None, None, None, "c_icd_inappropriate", None, 0, COND_ICD, None, None, 0, "json", 0, 300,
     "仅 ICD、CRT-D 显示；「无」互斥、选「其他」须填说明（Q04）"),
    ("f340", "charge_time", "充电时间(s)", "icd", "number", "s", 1, (0, 30), None, None, None, 0, COND_ICD, None, None, 0, "json", 0, 305,
     "C-FLD-008 新增（Q19）；仅 ICD、CRT-D 显示"),
    ("f341", "hv_impedance", "高压阻抗(Ω)", "icd", "number", "Ω", 0, (10, 200), None, None, None, 0, COND_ICD, None, None, 0, "json", 0, 308,
     "C-FLD-008 新增（Q19）；仅 ICD、CRT-D 显示"),
    ("f331", "complication", "并发症", "symptom", "multiselect", None, None, None, None, "c_complication", None, 0, None, None, None, 0, "json", 0, 310,
     "多选；原表两组选项的并集 12 项（Q06）；「无」互斥、选「其他」须填说明"),
    ("f332", "management", "参数调整/处置", "outcome", "textarea", None, None, None, None, None, None, 0, None, None, None, 0, "json", 0, 320, None),
    ("f333", "next_visit_date", "下次随访时间", "outcome", "date", None, None, None, None, None, None, 0, None, None, None, 0, "json", 0, 330,
     "快捷 +1 / +2 / +3 / +6 / +12 个月（前端）；填写后覆盖计划默认日期，只影响下一次任务（C-FU-005，键契约 PlanSwitchService）"),
    ("f334", "note", "备注", "outcome", "textarea", None, None, None, None, None, None, 0, None, None, None, 0, "json", 0, 340, None),
    ("f335", "implant_ref", "关联植入记录", "overview", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 12,
     "系统：按随访日期自动匹配当时在用的器械（C-PAT-007；随访记录的器械关联列，E21-T11）"),
    ("f336", "record_source", "记录来源", "overview", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 22,
     "枚举·系统：系统内随访 / 补录（纸质）（记录来源列，C-FU-013）"),
    ("f338", "scan_refs", "对应扫描件", "overview", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 62,
     "引用（多个）：报告单位置关联的扫描件（C-SCAN-010 / 附录 A 随访新增 #4）"),
    ("f339", "entry_audit", "录入人、录入时间", "overview", "text", None, None, None, None, None, None, 0, None, None, None, 0, "column", 0, 68,
     "系统：录入人与录入时间列（修改另记字段级历史，C-PAT-011）"),
]

# 表单与分类（锚点键稳定；threshold / sensing / impedance / battery 为 OQ-130① 契约程控分组，C-PAT-018）
CATEGORIES = {
    "C_REGISTRATION": ("患者登记（医院 C）", "BASELINE", [
        ("basic", "患者信息", 10),
        ("implant_info", "器械与植入信息（纸质表列，录第一条植入记录）", 20),
        ("followup", "随访安排", 30)]),
    "C_IMPLANT": ("植入记录与电极（医院 C）", "IMPLANT", [
        ("device", "器械信息", 10),
        ("lead", "电极", 20)]),
    "C_PROGRAM_FU": ("随访程控记录（医院 C）", "FOLLOWUP", [
        ("overview", "随访概况", 10),
        ("symptom", "症状与检查", 20),
        ("threshold", "起搏阈值与脉宽", 30),
        ("sensing", "感知", 40),
        ("impedance", "阻抗", 50),
        ("pacing", "起搏比例与程控参数", 60),
        ("battery", "电池", 70),
        ("icd", "ICD 项目", 80),
        ("outcome", "随访结局", 90)]),
}

# 附录行 → 稳定键（分组块序 → 键）：纸质表行序 × 10 即 sort_no（C-FLD-010）
APPENDIX_KEY = {}
for no in range(1, 22):
    APPENDIX_KEY[("患者", no)] = f"f{no:03d}"
for no in range(1, 35):
    APPENDIX_KEY[("随访", no)] = f"f3{no:02d}"
for no in range(1, 14):
    APPENDIX_KEY[("植入", no)] = f"f1{no:02d}"
for no in range(1, 7):
    APPENDIX_KEY[("电极", no)] = f"f12{no}"
# 随访新增组 #1–7（附录第二随访块）
EXTRA_KEYS = {("随访", 1): "f335", ("随访", 2): "f336", ("随访", 3): "f337", ("随访", 4): "f338",
              ("随访", 5): "f339", ("随访", 6): "f340", ("随访", 7): "f341"}

# C-FLD-007 硬校验（格式错误与「上限频率 ≤ 下限频率」拦截保存；其余建议范围为软校验，CL-01）
# 注意：V90 随包本行 op 写了表单引擎不支持的 "gt"（COMPARE 契约仅 lt / lte，规则被静默跳过、
# 硬校验从未生效），followup V98 已把库内行修正为 {"leftField":"f319","op":"lt","rightField":"f320"}
# （E21-T24 / CProgramEntryApiIT 先红后绿锁住）。本元组保持 V90 已提交字节（Flyway checksum），
# 重跑生成时不要顺手改 op——修正只存在于 V98。
RULES = [
    ("upper_rate_gt_lower", "pacing", "f320", "gt", "f320", "f319", "上限频率须大于下限频率", 10),
]


def check(blocks):
    by_key = {f[0]: f for f in FIELDS}
    # ① 附录 ↔ 字段一对一（除报告 16 行）
    expect_pairs = []
    for bi, (group, count, _form) in enumerate(GROUPS):
        if group == "报告":
            continue
        for row in blocks[bi]:
            if group == "随访" and bi == 4:
                key = EXTRA_KEYS[(group, row["no"])]
            else:
                key = APPENDIX_KEY[(group, row["no"])]
            expect_pairs.append((group, row["no"], key, row["label"]))
    if len(expect_pairs) != 81:
        sys.exit(f"附录映射应 81 行（97 − 报告 16），实际 {len(expect_pairs)}")
    for group, no, key, label in expect_pairs:
        field = by_key.get(key)
        if field is None:
            sys.exit(f"附录 {group} #{no} 缺稳定键 {key}")
        if field[2] != label:
            sys.exit(f"附录 {group} #{no}「{label}」与字典「{field[2]}」（{key}）显示名不一致")
    # ② 行数与键唯一
    if len(FIELDS) != 84:
        sys.exit(f"字段应 84 行（81 附录 + 3 植入配套：指征补充 / 说明 / 结束原因），实际 {len(FIELDS)}")
    keys = [f[0] for f in FIELDS]
    if len(set(keys)) != len(keys):
        sys.exit("稳定键重复")
    # ③ 键契约（OQ-130① / OQ-136⑤）
    for key, code in (("f308", "ra_threshold"), ("f309", "ra_pulse_width"), ("f310", "ra_sensing"),
                      ("f311", "ra_impedance"), ("f313", "rv_threshold"), ("f314", "rv_pulse_width"),
                      ("f315", "rv_sensing"), ("f316", "rv_impedance"), ("f323", "eri_status"),
                      ("f324", "battery_life"), ("f333", "next_visit_date"), ("f303", "followup_date"),
                      ("f305", "visit_doctor")):
        if by_key[key][1] != code:
            sys.exit(f"键契约失败：{key} 编码应为 {code}，实际 {by_key[key][1]}")
    # ④ 纸质表顺序（C-FLD-010）：C_PROGRAM_FU 内附录 34 行的 sort_no 随 #n 严格递增
    paper = [(APPENDIX_KEY[("随访", no)], by_key[APPENDIX_KEY[("随访", no)]][18]) for no in range(1, 35)]
    sorts = [s for _k, s in paper]
    if sorts != list(range(10, 344, 10)):
        sys.exit(f"纸质表 34 列 sort_no 应为 #n×10 严格递增：{sorts}")
    reg = [(APPENDIX_KEY[("患者", no)], by_key[APPENDIX_KEY[("患者", no)]][18]) for no in range(1, 22)]
    if [s for _k, s in reg] != [no * 10 for no in range(1, 22)]:
        sys.exit("患者登记 21 列 sort_no 应为 #n×10 严格递增")
        sys.exit("患者登记 21 列 sort_no 应为 #n×10 严格递增")
    # ⑤ 编码 ASCII 且表单内唯一
    import re as _re
    seen_codes = set()
    form_of = {}
    for f in FIELDS:
        key, code = f[0], f[1]
        form = ("C_REGISTRATION" if key.startswith("f0") else "C_IMPLANT" if key.startswith(("f1", "f2")) else "C_PROGRAM_FU")
        if not _re.fullmatch(r"[A-Za-z0-9_]{1,64}", code):
            sys.exit(f"{key} 字段编码必须是字母/数字/下划线：{code}")
        if (form, code) in seen_codes:
            sys.exit(f"字段编码表单内重复：{form} {code}")
        seen_codes.add((form, code))
        form_of[key] = form
    return form_of


def sqlstr(v):
    return "NULL" if v is None else "'" + str(v).replace("'", "''") + "'"


def jsonlit(v):
    return "NULL" if v is None else "'" + json.dumps(v, ensure_ascii=False, separators=(",", ":")) + "'"


def numlit(v):
    return "NULL" if v is None else str(v)


def emit(form_of):
    by_form = {}
    for f in FIELDS:
        by_form.setdefault(form_of[f[0]], []).append(f)
    out = []
    out.append("-- E23-T20 / C-FLD-001、C-FLD-008、C-FLD-010（OQ-130①、OQ-129②、OQ-132②、OQ-136⑤）：医院 C 字段字典录入")
    out.append("--   （附录 A 的 97 个字段中 81 个落 fe_field：患者 21 + 随访 34 + 随访新增 7 + 植入 13 + 电极 6，另加")
    out.append("--   植入配套 3 列〔指征补充说明 / 其他说明 note / 结束原因〕= 84 行；报告 #1–16 由报告单通用模型")
    out.append("--   rpt_report（E16-T02）与 E23-T21 类型字段集承载，不在 fe_field 重复登记）。")
    out.append("-- 由 scripts/gen_c_field_dict.py 从 docs/requirements/hospital-c-spec.md 附录 A 生成，")
    out.append("-- 与 CFieldDictSqlTest（附录 ↔ 迁移逐项核对）同口径；改附录或映射后重跑生成脚本再提交，不要手改本文件。")
    out.append("--")
    out.append("-- 键位契约（E21 任务先行声明，OQ-130① / OQ-135④ / OQ-136⑤）：附录随访组 #n → f3nn（f301–f334，新增组")
    out.append("--   f335–f341）；f303 编码 followup_date（重复提示按本字段匹配）、f305 编码 visit_doctor（自动带入）；")
    out.append("--   ERI 状态选项集 c_eri_status（0=正常 / 1=接近ERI / 2=已达ERI(EOI)，V82 对比规则引用）；分类锚点")
    out.append("--   threshold / sensing / impedance / battery（程控分组，C-PAT-018）。纸质表行序 × 10 = sort_no")
    out.append("--   （C-FLD-010 字段顺序与纸质表一致；顺序可由租户管理员在字段字典页调整）。")
    out.append("-- 业务字典绑定（dict_code，OQ-69④ / V89 列）：起搏器类型 pacemaker_type、品牌 pacemaker_brand、")
    out.append("--   统一起搏模式 pacing_mode（Q08 / C-FLD-003，植入与程控共用）、植入指征 implant_indication；选项经")
    out.append("--   FormDictPort 读本院生效字典展开（字符串编码）。电极部位按腔室三字典不绑单列（Q15，/implants/options）。")
    out.append("-- 程控参数范围为软校验（CL-01：附录「初步建议」按软提示确认后可保存并记确认人；格式错误与")
    out.append("--   上限 ≤ 下限才拦截，C-FLD-007）；心房参数组 / AV 间期 / ICD 项目按器械上下文条件显示（E21-T03 DSL）。")
    out.append("-- 带入上次设置项（C-FU-016，carry_over / OQ-67①）：程控起搏模式、上下限频率、AV 间期；测量值不带入。")
    out.append("")
    out.append("-- ① 表单与分类（锚点键稳定）")
    out.append("INSERT INTO fe_form (code, name, usage_type, current_version, categories) VALUES")
    form_lines = []
    for code, (name, usage, cats) in CATEGORIES.items():
        cat_json = ",\n  ".join(
            f"JSON_OBJECT('key', '{k}', 'label', '{l}', 'sortNo', {s})" for k, l, s in cats)
        form_lines.append(f"('{code}', '{name}', '{usage}', 1, JSON_ARRAY(\n  {cat_json}))")
    out.append(",\n".join(form_lines) + ";")
    out.append("")
    out.append("-- ② 选项集与选项（A-FLD-002 / C-FLD-004：互斥「无 / 愈合良好」、需补充说明「其他」，Q04 / Q06 / Q07；")
    out.append("--   c_eri_status 编码为 V82 对比规则契约）")
    out.append("INSERT INTO fe_option_set (code, name) VALUES")
    out.append(",\n".join(f"  ({sqlstr(c)}, {sqlstr(n)})" for c, (n, _o) in sorted(OPTION_SETS.items())) + ";")
    out.append("")
    out.append("INSERT INTO fe_option (set_code, option_code, label, exclusive, needs_detail, sort_no) VALUES")
    opt_rows = []
    for set_code, (_name, options) in sorted(OPTION_SETS.items()):
        for i, (code, label) in enumerate(options):
            exclusive = 1 if code in EXCLUSIVE.get(set_code, []) else 0
            needs = 1 if code in NEEDS_DETAIL.get(set_code, []) else 0
            opt_rows.append(f"  ({sqlstr(set_code)}, {code}, {sqlstr(label)}, {exclusive}, {needs}, {(i + 1) * 10})")
    out.append(",\n".join(opt_rows) + ";")
    out.append("")
    out.append("-- ③ 字段字典（84 行 = 附录 81 + 植入配套 3；JSON 列以字符串字面量书写，纸质表行序 × 10 = sort_no）")
    out.append("INSERT INTO fe_field")
    out.append("  (form_code, field_key, field_code, label, category_key, type, unit, decimals, hard_range, soft_range,")
    out.append("   option_set_code, dict_code, carry_over, required, visible_when, required_when, derived_def,")
    out.append("   sensitive_flag, storage_type, help_text, sort_no, status)")
    out.append("VALUES")
    lines = []
    for form in ("C_REGISTRATION", "C_IMPLANT", "C_PROGRAM_FU"):
        for f in sorted(by_form[form], key=lambda x: x[18]):
            key, code, label, cat, typ, unit, decimals, soft, hard, oset, dict_code, req, vis, reqw, der, sens, storage, carry, sort, help_text = f
            def rng(r):
                if r is None:
                    return None
                mn, mx = r
                def num(x):
                    return int(x) if x is not None and float(x).is_integer() else x
                return {"min": num(mn), **({"max": num(mx)} if mx is not None else {})}
            values = [
                sqlstr(form), sqlstr(key), sqlstr(code), sqlstr(label), sqlstr(cat), sqlstr(typ),
                sqlstr(unit), numlit(decimals), jsonlit(rng(hard)), jsonlit(rng(soft)),
                sqlstr(oset), sqlstr(dict_code), str(carry), str(req), jsonlit(vis), jsonlit(reqw),
                jsonlit(der), str(sens), sqlstr(storage), sqlstr(help_text), str(sort), "'ENABLED'",
            ]
            lines.append("(" + ", ".join(values) + ")")
    out.append(",\n".join(lines) + ";")
    out.append("")
    out.append("-- ④ C-FLD-007 硬校验：上限频率须大于下限频率（格式错误与该规则拦截保存，其余建议范围为软校验，CL-01）")
    out.append("INSERT INTO fe_rule")
    out.append("  (form_code, rule_id, category_key, target_field, severity, rule_type, params, message, sort_no)")
    out.append("VALUES")
    rule_lines = []
    for rule_id, cat, target, op, left, right, message, sort in RULES:
        params = jsonlit({"leftField": left, "op": op, "rightField": right})
        rule_lines.append(f"('C_PROGRAM_FU', {sqlstr(rule_id)}, {sqlstr(cat)}, {sqlstr(target)}, 'HARD', 'COMPARE', {params}, {sqlstr(message)}, {sort})")
    out.append(",\n".join(rule_lines) + ";")
    out.append("")
    out.append("-- ⑤ v1 版本快照（V29 同款：字典随包即有可按版本读取的模板；含 dict_code / carry_over 快照列，V89）")
    for form in ("C_REGISTRATION", "C_IMPLANT", "C_PROGRAM_FU"):
        out.append(f"INSERT INTO fe_form_version (form_code, version, categories, field_count, published_by)")
        out.append(f"SELECT fm.code, fm.current_version, fm.categories,")
        out.append(f"       (SELECT COUNT(*) FROM fe_field f WHERE f.form_code = fm.code AND f.status = 'ENABLED'), NULL")
        out.append(f"FROM fe_form fm WHERE fm.code = '{form}';")
        out.append("INSERT INTO fe_form_version_field")
        out.append("  (version_id, form_code, version, field_key, field_code, label, category_key, type,")
        out.append("   unit, decimals, hard_range, soft_range, option_set_code, dict_code, carry_over, required,")
        out.append("   visible_when, required_when, derived_def, sensitive_flag, storage_type, help_text, sort_no)")
        out.append("SELECT v.id, f.form_code, v.version, f.field_key, f.field_code, f.label, f.category_key, f.type,")
        out.append("       f.unit, f.decimals, f.hard_range, f.soft_range, f.option_set_code, f.dict_code, f.carry_over, f.required,")
        out.append("       f.visible_when, f.required_when, f.derived_def, f.sensitive_flag, f.storage_type,")
        out.append("       f.help_text, f.sort_no")
        out.append("FROM fe_field f")
        out.append("JOIN fe_form_version v ON v.form_code = f.form_code AND v.version = 1")
        out.append(f"WHERE f.form_code = '{form}' AND f.status = 'ENABLED';")
        out.append("")
    return "\n".join(out).rstrip() + "\n"


def main():
    blocks = parse_appendix()
    form_of = check(blocks)
    OUT.write_text(emit(form_of), encoding="utf-8")
    counts = {form: sum(1 for f in FIELDS if form_of[f[0]] == form) for form in CATEGORIES}
    print(f"已生成 {OUT.relative_to(ROOT)}：{len(FIELDS)} 字段（附录 81 + 植入配套 3）{counts}")


if __name__ == "__main__":
    main()
