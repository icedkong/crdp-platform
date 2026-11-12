-- E23-T20 / C-FLD-001、C-FLD-008、C-FLD-010（OQ-130①、OQ-129②、OQ-132②、OQ-136⑤）：医院 C 字段字典录入
--   （附录 A 的 97 个字段中 81 个落 fe_field：患者 21 + 随访 34 + 随访新增 7 + 植入 13 + 电极 6，另加
--   植入配套 3 列〔指征补充说明 / 其他说明 note / 结束原因〕= 84 行；报告 #1–16 由报告单通用模型
--   rpt_report（E16-T02）与 E23-T21 类型字段集承载，不在 fe_field 重复登记）。
-- 由 scripts/gen_c_field_dict.py 从 docs/requirements/hospital-c-spec.md 附录 A 生成，
-- 与 CFieldDictSqlTest（附录 ↔ 迁移逐项核对）同口径；改附录或映射后重跑生成脚本再提交，不要手改本文件。
--
-- 键位契约（E21 任务先行声明，OQ-130① / OQ-135④ / OQ-136⑤）：附录随访组 #n → f3nn（f301–f334，新增组
--   f335–f341）；f303 编码 followup_date（重复提示按本字段匹配）、f305 编码 visit_doctor（自动带入）；
--   ERI 状态选项集 c_eri_status（0=正常 / 1=接近ERI / 2=已达ERI(EOI)，V82 对比规则引用）；分类锚点
--   threshold / sensing / impedance / battery（程控分组，C-PAT-018）。纸质表行序 × 10 = sort_no
--   （C-FLD-010 字段顺序与纸质表一致；顺序可由租户管理员在字段字典页调整）。
-- 业务字典绑定（dict_code，OQ-69④ / V89 列）：起搏器类型 pacemaker_type、品牌 pacemaker_brand、
--   统一起搏模式 pacing_mode（Q08 / C-FLD-003，植入与程控共用）、植入指征 implant_indication；选项经
--   FormDictPort 读本院生效字典展开（字符串编码）。电极部位按腔室三字典不绑单列（Q15，/implants/options）。
-- 程控参数范围为软校验（CL-01：附录「初步建议」按软提示确认后可保存并记确认人；格式错误与
--   上限 ≤ 下限才拦截，C-FLD-007）；心房参数组 / AV 间期 / ICD 项目按器械上下文条件显示（E21-T03 DSL）。
-- 带入上次设置项（C-FU-016，carry_over / OQ-67①）：程控起搏模式、上下限频率、AV 间期；测量值不带入。

-- ① 表单与分类（锚点键稳定）
INSERT INTO fe_form (code, name, usage_type, current_version, categories) VALUES
('C_REGISTRATION', '患者登记（医院 C）', 'BASELINE', 1, JSON_ARRAY(
  JSON_OBJECT('key', 'basic', 'label', '患者信息', 'sortNo', 10),
  JSON_OBJECT('key', 'implant_info', 'label', '器械与植入信息（纸质表列，录第一条植入记录）', 'sortNo', 20),
  JSON_OBJECT('key', 'followup', 'label', '随访安排', 'sortNo', 30))),
('C_IMPLANT', '植入记录与电极（医院 C）', 'IMPLANT', 1, JSON_ARRAY(
  JSON_OBJECT('key', 'device', 'label', '器械信息', 'sortNo', 10),
  JSON_OBJECT('key', 'lead', 'label', '电极', 'sortNo', 20))),
('C_PROGRAM_FU', '随访程控记录（医院 C）', 'FOLLOWUP', 1, JSON_ARRAY(
  JSON_OBJECT('key', 'overview', 'label', '随访概况', 'sortNo', 10),
  JSON_OBJECT('key', 'symptom', 'label', '症状与检查', 'sortNo', 20),
  JSON_OBJECT('key', 'threshold', 'label', '起搏阈值与脉宽', 'sortNo', 30),
  JSON_OBJECT('key', 'sensing', 'label', '感知', 'sortNo', 40),
  JSON_OBJECT('key', 'impedance', 'label', '阻抗', 'sortNo', 50),
  JSON_OBJECT('key', 'pacing', 'label', '起搏比例与程控参数', 'sortNo', 60),
  JSON_OBJECT('key', 'battery', 'label', '电池', 'sortNo', 70),
  JSON_OBJECT('key', 'icd', 'label', 'ICD 项目', 'sortNo', 80),
  JSON_OBJECT('key', 'outcome', 'label', '随访结局', 'sortNo', 90)));

-- ② 选项集与选项（A-FLD-002 / C-FLD-004：互斥「无 / 愈合良好」、需补充说明「其他」，Q04 / Q06 / Q07；
--   c_eri_status 编码为 V82 对比规则契约）
INSERT INTO fe_option_set (code, name) VALUES
  ('c_arr_event', '心律失常事件'),
  ('c_complication', '并发症（并集，Q06）'),
  ('c_eri_status', 'ERI状态'),
  ('c_icd_inappropriate', 'ICD不恰当治疗'),
  ('c_icd_therapy', 'ICD恰当治疗'),
  ('c_pocket_exam', '囊袋/伤口检查（Q07）'),
  ('c_symptom', '症状'),
  ('c_visit_method', '随访方式'),
  ('c_visit_timepoint', '随访时间点');

INSERT INTO fe_option (set_code, option_code, label, exclusive, needs_detail, sort_no) VALUES
  ('c_arr_event', 0, '无', 1, 0, 10),
  ('c_arr_event', 1, '快房率', 0, 0, 20),
  ('c_arr_event', 2, '快室率', 0, 0, 30),
  ('c_arr_event', 3, '心房噪音', 0, 0, 40),
  ('c_arr_event', 4, '心室噪音', 0, 0, 50),
  ('c_complication', 0, '无', 1, 0, 10),
  ('c_complication', 1, '囊袋感染', 0, 0, 20),
  ('c_complication', 2, '电极磨损', 0, 0, 30),
  ('c_complication', 3, '电极穿孔', 0, 0, 40),
  ('c_complication', 4, '电极脱位', 0, 0, 50),
  ('c_complication', 5, '感知不良', 0, 0, 60),
  ('c_complication', 6, '起搏阈值升高', 0, 0, 70),
  ('c_complication', 7, '电极阻抗异常', 0, 0, 80),
  ('c_complication', 8, '囊袋血肿', 0, 0, 90),
  ('c_complication', 9, '膈肌刺激', 0, 0, 100),
  ('c_complication', 10, '气胸', 0, 0, 110),
  ('c_complication', 11, '其他', 0, 1, 120),
  ('c_eri_status', 0, '正常', 0, 0, 10),
  ('c_eri_status', 1, '接近ERI', 0, 0, 20),
  ('c_eri_status', 2, '已达ERI(EOI)', 0, 0, 30),
  ('c_icd_inappropriate', 0, '无', 1, 0, 10),
  ('c_icd_inappropriate', 1, '噪音SHOCK', 0, 0, 20),
  ('c_icd_inappropriate', 2, '房颤SHOCK', 0, 0, 30),
  ('c_icd_inappropriate', 3, '室上速误治疗', 0, 0, 40),
  ('c_icd_inappropriate', 4, 'T波超感知', 0, 0, 50),
  ('c_icd_inappropriate', 5, 'QRS波双计数', 0, 0, 60),
  ('c_icd_inappropriate', 6, '其他', 0, 1, 70),
  ('c_icd_therapy', 0, '无', 1, 0, 10),
  ('c_icd_therapy', 1, 'ATP', 0, 0, 20),
  ('c_icd_therapy', 2, 'SHOCK', 0, 0, 30),
  ('c_pocket_exam', 0, '愈合良好', 1, 0, 10),
  ('c_pocket_exam', 1, '红肿', 0, 0, 20),
  ('c_pocket_exam', 2, '渗液', 0, 0, 30),
  ('c_pocket_exam', 3, '血肿', 0, 0, 40),
  ('c_pocket_exam', 4, '皮肤变薄或破溃', 0, 0, 50),
  ('c_pocket_exam', 5, '感染征象', 0, 0, 60),
  ('c_pocket_exam', 6, '其他', 0, 1, 70),
  ('c_symptom', 0, '无', 1, 0, 10),
  ('c_symptom', 1, '心悸', 0, 0, 20),
  ('c_symptom', 2, '黑蒙', 0, 0, 30),
  ('c_symptom', 3, '晕厥', 0, 0, 40),
  ('c_symptom', 4, '气促', 0, 0, 50),
  ('c_symptom', 5, '乏力', 0, 0, 60),
  ('c_symptom', 6, '胸痛', 0, 0, 70),
  ('c_symptom', 7, '水肿', 0, 0, 80),
  ('c_symptom', 8, '其他', 0, 1, 90),
  ('c_visit_method', 1, '门诊程控', 0, 0, 10),
  ('c_visit_method', 2, '远程监测', 0, 0, 20),
  ('c_visit_method', 3, '门诊程控+远程监测', 0, 0, 30),
  ('c_visit_timepoint', 1, '术后72小时/出院前', 0, 0, 10),
  ('c_visit_timepoint', 2, '术后1个月', 0, 0, 20),
  ('c_visit_timepoint', 3, '术后3个月', 0, 0, 30),
  ('c_visit_timepoint', 4, '术后6个月', 0, 0, 40),
  ('c_visit_timepoint', 5, '术后1年', 0, 0, 50),
  ('c_visit_timepoint', 6, '每6-12个月', 0, 0, 60),
  ('c_visit_timepoint', 7, 'ERI期随访', 0, 0, 70),
  ('c_visit_timepoint', 8, '其他', 0, 0, 80);

-- ③ 字段字典（84 行 = 附录 81 + 植入配套 3；JSON 列以字符串字面量书写，纸质表行序 × 10 = sort_no）
INSERT INTO fe_field
  (form_code, field_key, field_code, label, category_key, type, unit, decimals, hard_range, soft_range,
   option_set_code, dict_code, carry_over, required, visible_when, required_when, derived_def,
   sensitive_flag, storage_type, help_text, sort_no, status)
VALUES
('C_REGISTRATION', 'f001', 'visit_no', '随访编号', 'basic', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '系统生成「前缀-建档年份-3 位流水号」（如 105-2026-001，C-PAT-003 / Q10）；历史患者可手填纸质原编号；规则可配置（E21-T10）', 10, 'ENABLED'),
('C_REGISTRATION', 'f002', 'patient_name', '姓名', 'basic', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 1, NULL, NULL, NULL, 1, 'encrypted', '敏感字段：默认脱敏，明文需敏感权限；加密存储', 20, 'ENABLED'),
('C_REGISTRATION', 'f003', 'gender', '性别', 'basic', 'select', NULL, NULL, NULL, NULL, 'a_gender', NULL, 0, 1, NULL, NULL, NULL, 0, 'column', '编码 1=男 / 2=女；患者主档存 M / F；原表下拉误设在「备注」列（Q15 已纠正）', 30, 'ENABLED'),
('C_REGISTRATION', 'f004', 'birth_date', '出生日期', 'basic', 'date', NULL, NULL, NULL, NULL, NULL, NULL, 0, 1, NULL, NULL, NULL, 0, 'column', '必填；用于计算年龄', 40, 'ENABLED'),
('C_REGISTRATION', 'f005', 'age', '年龄', 'basic', 'derived', '岁', 0, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, '{"fn":"AGE_YEARS","args":["f004"]}', 0, 'json', '派生：今天 − 出生日期，取周岁（与原表公式一致）；单参 AGE_YEARS 以今天为终点', 50, 'ENABLED'),
('C_REGISTRATION', 'f006', 'phone', '联系电话', 'basic', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 1, 'encrypted', '敏感字段：默认脱敏，明文需敏感权限；可多个、旧号保留（联系方式版本表）', 60, 'ENABLED'),
('C_REGISTRATION', 'f007', 'mrn', '病历号', 'basic', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 1, NULL, NULL, NULL, 1, 'encrypted', '敏感字段：默认脱敏；本院唯一、查重（C-PAT-002）', 70, 'ENABLED'),
('C_REGISTRATION', 'f008', 'device_type', '起搏器类型', 'implant_info', 'select', NULL, NULL, NULL, NULL, NULL, 'pacemaker_type', 0, 1, NULL, NULL, NULL, 0, 'column', '纸质表列，移入植入记录（pt_implant.device_type）；决定随访计划（C-FU-003）与条件显示（C-FLD-005）', 80, 'ENABLED'),
('C_REGISTRATION', 'f009', 'brand', '品牌', 'implant_info', 'select', NULL, NULL, NULL, NULL, NULL, 'pacemaker_brand', 0, 0, NULL, NULL, NULL, 0, 'column', '纸质表列，移入植入记录；业务字典 pacemaker_brand', 90, 'ENABLED'),
('C_REGISTRATION', 'f010', 'model', '型号', 'implant_info', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '纸质表列，移入植入记录', 100, 'ENABLED'),
('C_REGISTRATION', 'f011', 'pacing_mode', '起搏模式', 'implant_info', 'select', NULL, NULL, NULL, NULL, NULL, 'pacing_mode', 0, 0, NULL, NULL, NULL, 0, 'column', '统一起搏模式字典（Q08 / C-FLD-003，平台库 V70）；植入时设置的模式', 110, 'ENABLED'),
('C_REGISTRATION', 'f012', 'ra_lead_position', 'RA电极植入部位', 'implant_info', 'select', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '纸质表列，移入电极记录；字典 lead_position_ra（按腔室过滤，Q15），选项经 GET /implants/options', 120, 'ENABLED'),
('C_REGISTRATION', 'f013', 'rv_lead_position', 'RV电极植入部位', 'implant_info', 'select', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '纸质表列，移入电极记录；字典 lead_position_rv（按腔室过滤，Q15）', 130, 'ENABLED'),
('C_REGISTRATION', 'f014', 'lv_lead_position', 'LV电极植入部位', 'implant_info', 'select', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '纸质表列，移入电极记录；字典 lead_position_lv；仅 CRT-P、CRT-D 录入（C-FLD-005）', 140, 'ENABLED'),
('C_REGISTRATION', 'f015', 'implant_date', '植入日期', 'implant_info', 'date', NULL, NULL, NULL, NULL, NULL, NULL, 0, 1, NULL, NULL, NULL, 0, 'column', '纸质表列，移入植入记录；随访计划起算日；允许过去日期（历史患者建档，C-PAT-008）', 150, 'ENABLED'),
('C_REGISTRATION', 'f016', 'implant_hospital', '植入医院', 'implant_info', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '纸质表列，移入植入记录；默认本院', 160, 'ENABLED'),
('C_REGISTRATION', 'f017', 'implant_doctor', '植入医生', 'implant_info', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '纸质表列，移入植入记录；本院医生从用户中选择，外院可手填', 170, 'ENABLED'),
('C_REGISTRATION', 'f018', 'indication', '植入指征', 'implant_info', 'select', NULL, NULL, NULL, NULL, NULL, 'implant_indication', 0, 0, NULL, NULL, NULL, 0, 'column', '纸质表列，移入植入记录；可配置字典并保留文字补充（Q16，指征补充说明随植入记录）', 180, 'ENABLED'),
('C_REGISTRATION', 'f019', 'visit_method', '随访方式', 'followup', 'select', NULL, NULL, NULL, NULL, 'c_visit_method', NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '患者默认随访方式，程控记录录入时带入（C-FU-008）', 190, 'ENABLED'),
('C_REGISTRATION', 'f020', 'follower', '责任随访人', 'followup', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '本科室病区的医生、随访员或护士；任务默认负责人', 200, 'ENABLED'),
('C_REGISTRATION', 'f021', 'note', '备注', 'followup', 'textarea', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', NULL, 210, 'ENABLED'),
('C_IMPLANT', 'f101', 'implant_no', '植入序号', 'device', 'derived', NULL, 0, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '派生·系统：按植入日期排序编号（第 1 次、第 2 次……），列表展示层计算', 10, 'ENABLED'),
('C_IMPLANT', 'f121', 'parent_implant', '所属植入记录', 'lead', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '引用；沿用的电极可挂在新植入记录下（保留原植入日期，E21-T02）', 10, 'ENABLED'),
('C_IMPLANT', 'f102', 'implant_type', '植入类型', 'device', 'select', NULL, NULL, NULL, NULL, NULL, NULL, 0, 1, NULL, NULL, NULL, 0, 'column', '必填（Q01、Q16）；系统枚举 FIRST 首次植入 / REPLACE 更换（电池耗竭）/ UPGRADE 升级 / LEAD_REVISION 电极修正 / OTHER 其他（须填说明 note，OQ-132②）', 20, 'ENABLED'),
('C_IMPLANT', 'f122', 'chamber', '腔室', 'lead', 'select', NULL, NULL, NULL, NULL, NULL, NULL, 0, 1, NULL, NULL, NULL, 0, 'column', '必填；枚举·系统：RA / RV / LV（pt_implant_lead.chamber）；无导线起搏器不录电极', 20, 'ENABLED'),
('C_IMPLANT', 'f103', 'implant_date', '植入日期', 'device', 'date', NULL, NULL, NULL, NULL, NULL, NULL, 0, 1, NULL, NULL, NULL, 0, 'column', '必填；计划起算日；允许过去日期', 30, 'ENABLED'),
('C_IMPLANT', 'f123', 'position', '植入部位', 'lead', 'select', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '按腔室过滤（lead_position_ra / rv / lv，Q15）；选项经 GET /implants/options 下发，不绑单一字典', 30, 'ENABLED'),
('C_IMPLANT', 'f104', 'device_type', '起搏器类型', 'device', 'select', NULL, NULL, NULL, NULL, NULL, 'pacemaker_type', 0, 1, NULL, NULL, NULL, 0, 'column', '见患者登记 #8；必填；决定随访计划与条件显示', 40, 'ENABLED'),
('C_IMPLANT', 'f124', 'lead_model', '电极型号', 'lead', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '选填', 40, 'ENABLED'),
('C_IMPLANT', 'f105', 'brand', '品牌', 'device', 'select', NULL, NULL, NULL, NULL, NULL, 'pacemaker_brand', 0, 0, NULL, NULL, NULL, 0, 'column', '见患者登记 #9', 50, 'ENABLED'),
('C_IMPLANT', 'f125', 'lead_implant_date', '电极植入日期', 'lead', 'date', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '默认同植入记录；沿用电极保留原日期（跨器械，C-PAT-006）', 50, 'ENABLED'),
('C_IMPLANT', 'f106', 'model', '型号', 'device', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', NULL, 60, 'ENABLED'),
('C_IMPLANT', 'f126', 'lead_status', '状态', 'lead', 'select', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '枚举·系统：ACTIVE 在用 / ABANDONED 废弃留置 / EXTRACTED 已拔除（OQ-132③）', 60, 'ENABLED'),
('C_IMPLANT', 'f107', 'serial_no', '设备序列号', 'device', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '选填（Q16 / C-FLD-008 新增）', 70, 'ENABLED'),
('C_IMPLANT', 'f108', 'pacing_mode', '起搏模式', 'device', 'select', NULL, NULL, NULL, NULL, NULL, 'pacing_mode', 0, 0, NULL, NULL, NULL, 0, 'column', '统一起搏模式字典（Q08）', 80, 'ENABLED'),
('C_IMPLANT', 'f109', 'hospital', '植入医院', 'device', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '默认本院', 90, 'ENABLED'),
('C_IMPLANT', 'f110', 'doctor', '植入医生', 'device', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '本院医生从用户中选择，外院可手填', 100, 'ENABLED'),
('C_IMPLANT', 'f111', 'indication', '植入指征', 'device', 'select', NULL, NULL, NULL, NULL, NULL, 'implant_indication', 0, 0, NULL, NULL, NULL, 0, 'column', '可配置字典（Q16）；文字补充见指征补充说明', 110, 'ENABLED'),
('C_IMPLANT', 'f114', 'indication_note', '指征补充说明', 'device', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', 'Q16：植入指征选「其他」或需文字补充时填写（pt_implant.indication_note，V81）', 115, 'ENABLED'),
('C_IMPLANT', 'f112', 'status', '状态', 'device', 'select', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '枚举·系统：ACTIVE 在用 / REPLACED 已更换 / REMOVED 已取出；同一患者仅一条在用（C-PAT-007）', 120, 'ENABLED'),
('C_IMPLANT', 'f115', 'note', '说明', 'device', 'textarea', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '植入类型为「其他」时必填（OQ-132②，pt_implant.note，V83）', 125, 'ENABLED'),
('C_IMPLANT', 'f113', 'end_date', '结束日期与原因', 'device', 'date', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '更换时自动填为新植入日期；原因（end_reason）随行维护；取出时记已取出', 130, 'ENABLED'),
('C_IMPLANT', 'f116', 'end_reason', '结束原因', 'device', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '结束日期的配套原因文字（更换 / 取出时随行维护）', 135, 'ENABLED'),
('C_PROGRAM_FU', 'f301', 'visit_no', '随访编号', 'overview', 'derived', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '引用：关联患者，系统自动带入（C-FU-008）', 10, 'ENABLED'),
('C_PROGRAM_FU', 'f335', 'implant_ref', '关联植入记录', 'overview', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '系统：按随访日期自动匹配当时在用的器械（C-PAT-007；随访记录的器械关联列，E21-T11）', 12, 'ENABLED'),
('C_PROGRAM_FU', 'f302', 'sequence_no', '随访序号', 'overview', 'derived', NULL, 0, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', '派生·系统：同一患者按随访日期升序编号、补录后自动重排（Q13 / C-FU-018，E21-T11 序号派生）', 20, 'ENABLED'),
('C_PROGRAM_FU', 'f336', 'record_source', '记录来源', 'overview', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '枚举·系统：系统内随访 / 补录（纸质）（记录来源列，C-FU-013）', 22, 'ENABLED'),
('C_PROGRAM_FU', 'f303', 'followup_date', '随访日期', 'overview', 'date', NULL, NULL, NULL, NULL, NULL, NULL, 0, 1, NULL, NULL, NULL, 0, 'json', '必填；不早于当时器械植入日期、不晚于今天（服务层校验）；补录可填过去日期（重复提示按本字段匹配，OQ-136⑤）', 30, 'ENABLED'),
('C_PROGRAM_FU', 'f304', 'visit_timepoint', '随访时间点', 'overview', 'select', NULL, NULL, NULL, NULL, 'c_visit_timepoint', NULL, 0, 1, NULL, NULL, NULL, 0, 'json', '必填；任务录入由子计划带入、补录按日期建议（C-FU-014）；原表下拉只设在一格（Q15 已纠正）', 40, 'ENABLED'),
('C_PROGRAM_FU', 'f305', 'visit_doctor', '随访医生', 'overview', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', '用户引用；默认当前用户（C-FU-008 自动带入，E21-T11 entryDefaults 写入本字段）', 50, 'ENABLED'),
('C_PROGRAM_FU', 'f306', 'visit_method', '随访方式', 'overview', 'select', NULL, NULL, NULL, NULL, 'c_visit_method', NULL, 0, 0, NULL, NULL, NULL, 0, 'json', '默认取患者随访方式（C-FU-008）；远程监测同表单人工录入（C-FU-025）', 60, 'ENABLED'),
('C_PROGRAM_FU', 'f338', 'scan_refs', '对应扫描件', 'overview', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '引用（多个）：报告单位置关联的扫描件（C-SCAN-010 / 附录 A 随访新增 #4）', 62, 'ENABLED'),
('C_PROGRAM_FU', 'f339', 'entry_audit', '录入人、录入时间', 'overview', 'text', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'column', '系统：录入人与录入时间列（修改另记字段级历史，C-PAT-011）', 68, 'ENABLED'),
('C_PROGRAM_FU', 'f307', 'symptom', '症状', 'symptom', 'multiselect', NULL, NULL, NULL, NULL, 'c_symptom', NULL, 0, 0, NULL, NULL, NULL, 0, 'json', '多选（Q04）；「无」互斥、选「其他」须填说明', 70, 'ENABLED'),
('C_PROGRAM_FU', 'f337', 'pocket_exam', '囊袋/伤口检查', 'symptom', 'multiselect', NULL, NULL, NULL, NULL, 'c_pocket_exam', NULL, 0, 0, NULL, NULL, NULL, 0, 'json', 'C-FLD-008 新增（Q07）：「愈合良好」互斥、选「其他」须填说明；术后72小时、1个月默认必填', 75, 'ENABLED'),
('C_PROGRAM_FU', 'f308', 'ra_threshold', '心房起搏阈值(V)', 'threshold', 'number', 'V', 2, NULL, '{"min":0.1,"max":10}', NULL, NULL, 0, 0, '{"all":[{"ctx":"device.hasRaLead","op":"eq","value":true}]}', NULL, NULL, 0, 'json', '有在用 RA 电极时显示（C-FU-009）；与上次比较标红（fu_compare_rule ra_threshold_rise）；建议范围 0.1–10.0 为软校验（C-FLD-007）', 80, 'ENABLED'),
('C_PROGRAM_FU', 'f309', 'ra_pulse_width', '心房脉宽(ms)', 'threshold', 'number', 'ms', 2, NULL, '{"min":0.03,"max":2}', NULL, NULL, 0, 0, '{"all":[{"ctx":"device.hasRaLead","op":"eq","value":true}]}', NULL, NULL, 0, 'json', '有在用 RA 电极时显示；脉宽不同时阈值对比注明仅供参考（Q02）', 90, 'ENABLED'),
('C_PROGRAM_FU', 'f310', 'ra_sensing', '心房感知P波(mV)', 'sensing', 'number', 'mV', NULL, NULL, '{"min":0.1,"max":20}', NULL, NULL, 0, 0, '{"all":[{"ctx":"device.hasRaLead","op":"eq","value":true}]}', NULL, NULL, 0, 'json', '有在用 RA 电极时显示；与上次比较（感知明显下降 >50% 标红）', 100, 'ENABLED'),
('C_PROGRAM_FU', 'f311', 'ra_impedance', '心房阻抗(Ω)', 'impedance', 'number', 'Ω', 0, NULL, '{"min":100,"max":3000}', NULL, NULL, 0, 0, '{"all":[{"ctx":"device.hasRaLead","op":"eq","value":true}]}', NULL, NULL, 0, 'json', '整数；有在用 RA 电极时显示；与上次比较（波动 >50% 标红）', 110, 'ENABLED'),
('C_PROGRAM_FU', 'f312', 'ra_pacing_ratio', '心房起搏比例(%)', 'pacing', 'number', '%', NULL, NULL, '{"min":0,"max":100}', NULL, NULL, 0, 0, '{"all":[{"ctx":"device.hasRaLead","op":"eq","value":true}]}', NULL, NULL, 0, 'json', '有在用 RA 电极时显示', 120, 'ENABLED'),
('C_PROGRAM_FU', 'f313', 'rv_threshold', '心室起搏阈值(V)', 'threshold', 'number', 'V', 2, NULL, '{"min":0.1,"max":10}', NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', '与上次比较标红（rv_threshold_rise）', 130, 'ENABLED'),
('C_PROGRAM_FU', 'f314', 'rv_pulse_width', '心室脉宽(ms)', 'threshold', 'number', 'ms', 2, NULL, '{"min":0.03,"max":2}', NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', NULL, 140, 'ENABLED'),
('C_PROGRAM_FU', 'f315', 'rv_sensing', '心室感知R波(mV)', 'sensing', 'number', 'mV', NULL, NULL, '{"min":0.1,"max":30}', NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', '与上次比较（感知明显下降 >50% 标红）', 150, 'ENABLED'),
('C_PROGRAM_FU', 'f316', 'rv_impedance', '心室阻抗(Ω)', 'impedance', 'number', 'Ω', 0, NULL, '{"min":100,"max":3000}', NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', '整数；与上次比较（波动 >50% 标红；心室阻抗 5000 超范围提示、确认后可保存并记确认人）', 160, 'ENABLED'),
('C_PROGRAM_FU', 'f317', 'rv_pacing_ratio', '心室起搏比例(%)', 'pacing', 'number', '%', NULL, NULL, '{"min":0,"max":100}', NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', NULL, 170, 'ENABLED'),
('C_PROGRAM_FU', 'f318', 'programmed_mode', '程控起搏模式', 'pacing', 'select', NULL, NULL, NULL, NULL, NULL, 'pacing_mode', 1, 0, NULL, NULL, NULL, 0, 'json', '统一起搏模式字典（Q08 / C-FLD-003）；带入上次设置项（C-FU-016，carry_over）', 180, 'ENABLED'),
('C_PROGRAM_FU', 'f319', 'lower_rate', '下限频率(bpm)', 'pacing', 'number', 'bpm', 0, NULL, '{"min":30,"max":150}', NULL, NULL, 1, 0, NULL, NULL, NULL, 0, 'json', '带入上次设置项（C-FU-016）', 190, 'ENABLED'),
('C_PROGRAM_FU', 'f320', 'upper_rate', '上限频率(bpm)', 'pacing', 'number', 'bpm', 0, NULL, '{"min":60,"max":200}', NULL, NULL, 1, 0, NULL, NULL, NULL, 0, 'json', '须大于下限频率（fe_rule 硬校验拦截保存，C-FLD-007）；带入上次设置项（C-FU-016）', 200, 'ENABLED'),
('C_PROGRAM_FU', 'f321', 'av_delay', 'AV间期(ms)', 'pacing', 'number', 'ms', 0, NULL, '{"min":30,"max":350}', NULL, NULL, 1, 0, '{"all":[{"ctx":"device.chambers","op":"gte","value":2}]}', NULL, NULL, 0, 'json', '双腔及以上显示（device.chambers ≥ 2，C-FU-009）；带入上次设置项（C-FU-016）', 210, 'ENABLED'),
('C_PROGRAM_FU', 'f322', 'battery_voltage', '电池电压(V)', 'battery', 'number', 'V', 2, NULL, '{"min":0,"max":4}', NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', NULL, 220, 'ENABLED'),
('C_PROGRAM_FU', 'f323', 'eri_status', 'ERI状态', 'battery', 'select', NULL, NULL, NULL, NULL, 'c_eri_status', NULL, 0, 0, NULL, NULL, NULL, 0, 'json', '非「正常」时标红（fu_compare_rule eri_status_abnormal）并切换 ERI 期随访（C-FU-006 / E21-T06，键契约 OQ-130①）', 230, 'ENABLED'),
('C_PROGRAM_FU', 'f324', 'battery_life', '预计电池寿命(年)', 'battery', 'number', '年', 1, NULL, '{"min":0,"max":20}', NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', '低于阈值（默认 1 年）标记接近 ERI（Q13；≤ 1 视同接近 ERI，E21-T06）', 240, 'ENABLED'),
('C_PROGRAM_FU', 'f325', 'magnet_rate', '磁铁频率(bpm)', 'battery', 'number', 'bpm', 0, NULL, '{"min":30,"max":120}', NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', NULL, 250, 'ENABLED'),
('C_PROGRAM_FU', 'f326', 'mode_switch_count', '模式转换次数', 'battery', 'number', NULL, 0, NULL, '{"min":0}', NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', '自上次程控以来；≥ 0', 260, 'ENABLED'),
('C_PROGRAM_FU', 'f327', 'ataf_load', 'AT/AF负荷(%)', 'battery', 'number', '%', NULL, NULL, '{"min":0,"max":100}', NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', NULL, 270, 'ENABLED'),
('C_PROGRAM_FU', 'f328', 'arrhythmia_event', '心律失常事件', 'symptom', 'multiselect', NULL, NULL, NULL, NULL, 'c_arr_event', NULL, 0, 0, NULL, NULL, NULL, 0, 'json', '多选（Q04）；「无」互斥', 280, 'ENABLED'),
('C_PROGRAM_FU', 'f329', 'icd_appropriate', 'ICD恰当治疗', 'icd', 'multiselect', NULL, NULL, NULL, NULL, 'c_icd_therapy', NULL, 0, 0, '{"all":[{"ctx":"device.type","op":"in","value":["ICD","CRT_D"]}]}', NULL, NULL, 0, 'json', '仅 ICD、CRT-D 显示（Q19 / C-FU-009）', 290, 'ENABLED'),
('C_PROGRAM_FU', 'f330', 'icd_inappropriate', 'ICD不恰当治疗', 'icd', 'multiselect', NULL, NULL, NULL, NULL, 'c_icd_inappropriate', NULL, 0, 0, '{"all":[{"ctx":"device.type","op":"in","value":["ICD","CRT_D"]}]}', NULL, NULL, 0, 'json', '仅 ICD、CRT-D 显示；「无」互斥、选「其他」须填说明（Q04）', 300, 'ENABLED'),
('C_PROGRAM_FU', 'f340', 'charge_time', '充电时间(s)', 'icd', 'number', 's', 1, NULL, '{"min":0,"max":30}', NULL, NULL, 0, 0, '{"all":[{"ctx":"device.type","op":"in","value":["ICD","CRT_D"]}]}', NULL, NULL, 0, 'json', 'C-FLD-008 新增（Q19）；仅 ICD、CRT-D 显示', 305, 'ENABLED'),
('C_PROGRAM_FU', 'f341', 'hv_impedance', '高压阻抗(Ω)', 'icd', 'number', 'Ω', 0, NULL, '{"min":10,"max":200}', NULL, NULL, 0, 0, '{"all":[{"ctx":"device.type","op":"in","value":["ICD","CRT_D"]}]}', NULL, NULL, 0, 'json', 'C-FLD-008 新增（Q19）；仅 ICD、CRT-D 显示', 308, 'ENABLED'),
('C_PROGRAM_FU', 'f331', 'complication', '并发症', 'symptom', 'multiselect', NULL, NULL, NULL, NULL, 'c_complication', NULL, 0, 0, NULL, NULL, NULL, 0, 'json', '多选；原表两组选项的并集 12 项（Q06）；「无」互斥、选「其他」须填说明', 310, 'ENABLED'),
('C_PROGRAM_FU', 'f332', 'management', '参数调整/处置', 'outcome', 'textarea', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', NULL, 320, 'ENABLED'),
('C_PROGRAM_FU', 'f333', 'next_visit_date', '下次随访时间', 'outcome', 'date', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', '快捷 +1 / +2 / +3 / +6 / +12 个月（前端）；填写后覆盖计划默认日期，只影响下一次任务（C-FU-005，键契约 PlanSwitchService）', 330, 'ENABLED'),
('C_PROGRAM_FU', 'f334', 'note', '备注', 'outcome', 'textarea', NULL, NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, NULL, 0, 'json', NULL, 340, 'ENABLED');

-- ④ C-FLD-007 硬校验：上限频率须大于下限频率（格式错误与该规则拦截保存，其余建议范围为软校验，CL-01）
INSERT INTO fe_rule
  (form_code, rule_id, category_key, target_field, severity, rule_type, params, message, sort_no)
VALUES
('C_PROGRAM_FU', 'upper_rate_gt_lower', 'pacing', 'f320', 'HARD', 'COMPARE', '{"leftField":"f320","op":"gt","rightField":"f319"}', '上限频率须大于下限频率', 10);

-- ⑤ v1 版本快照（V29 同款：字典随包即有可按版本读取的模板；含 dict_code / carry_over 快照列，V89）
INSERT INTO fe_form_version (form_code, version, categories, field_count, published_by)
SELECT fm.code, fm.current_version, fm.categories,
       (SELECT COUNT(*) FROM fe_field f WHERE f.form_code = fm.code AND f.status = 'ENABLED'), NULL
FROM fe_form fm WHERE fm.code = 'C_REGISTRATION';
INSERT INTO fe_form_version_field
  (version_id, form_code, version, field_key, field_code, label, category_key, type,
   unit, decimals, hard_range, soft_range, option_set_code, dict_code, carry_over, required,
   visible_when, required_when, derived_def, sensitive_flag, storage_type, help_text, sort_no)
SELECT v.id, f.form_code, v.version, f.field_key, f.field_code, f.label, f.category_key, f.type,
       f.unit, f.decimals, f.hard_range, f.soft_range, f.option_set_code, f.dict_code, f.carry_over, f.required,
       f.visible_when, f.required_when, f.derived_def, f.sensitive_flag, f.storage_type,
       f.help_text, f.sort_no
FROM fe_field f
JOIN fe_form_version v ON v.form_code = f.form_code AND v.version = 1
WHERE f.form_code = 'C_REGISTRATION' AND f.status = 'ENABLED';

INSERT INTO fe_form_version (form_code, version, categories, field_count, published_by)
SELECT fm.code, fm.current_version, fm.categories,
       (SELECT COUNT(*) FROM fe_field f WHERE f.form_code = fm.code AND f.status = 'ENABLED'), NULL
FROM fe_form fm WHERE fm.code = 'C_IMPLANT';
INSERT INTO fe_form_version_field
  (version_id, form_code, version, field_key, field_code, label, category_key, type,
   unit, decimals, hard_range, soft_range, option_set_code, dict_code, carry_over, required,
   visible_when, required_when, derived_def, sensitive_flag, storage_type, help_text, sort_no)
SELECT v.id, f.form_code, v.version, f.field_key, f.field_code, f.label, f.category_key, f.type,
       f.unit, f.decimals, f.hard_range, f.soft_range, f.option_set_code, f.dict_code, f.carry_over, f.required,
       f.visible_when, f.required_when, f.derived_def, f.sensitive_flag, f.storage_type,
       f.help_text, f.sort_no
FROM fe_field f
JOIN fe_form_version v ON v.form_code = f.form_code AND v.version = 1
WHERE f.form_code = 'C_IMPLANT' AND f.status = 'ENABLED';

INSERT INTO fe_form_version (form_code, version, categories, field_count, published_by)
SELECT fm.code, fm.current_version, fm.categories,
       (SELECT COUNT(*) FROM fe_field f WHERE f.form_code = fm.code AND f.status = 'ENABLED'), NULL
FROM fe_form fm WHERE fm.code = 'C_PROGRAM_FU';
INSERT INTO fe_form_version_field
  (version_id, form_code, version, field_key, field_code, label, category_key, type,
   unit, decimals, hard_range, soft_range, option_set_code, dict_code, carry_over, required,
   visible_when, required_when, derived_def, sensitive_flag, storage_type, help_text, sort_no)
SELECT v.id, f.form_code, v.version, f.field_key, f.field_code, f.label, f.category_key, f.type,
       f.unit, f.decimals, f.hard_range, f.soft_range, f.option_set_code, f.dict_code, f.carry_over, f.required,
       f.visible_when, f.required_when, f.derived_def, f.sensitive_flag, f.storage_type,
       f.help_text, f.sort_no
FROM fe_field f
JOIN fe_form_version v ON v.form_code = f.form_code AND v.version = 1
WHERE f.form_code = 'C_PROGRAM_FU' AND f.status = 'ENABLED';
