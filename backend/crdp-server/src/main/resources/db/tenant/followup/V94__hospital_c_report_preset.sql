-- E23-T21 / C-SCAN-002、C-SCAN-003、C-SCAN-004、C-SCAN-005、C-SCAN-011（验收「程控报告单可映射字段」）：
-- 医院报告单 / 资料的租户配置（平台通用能力、按院配置，V62 建表、V64 映射表同载体）预置医院 C 的 10 类：
--   * 8 类本院预置随包落库：程控报告单（C_PROGRAM）、纸质随访表（C_PAPER_FORM）、植入手术记录
--     （C_IMPLANT_RECORD）、心电图/动态心电图（C_ECG）、胸片（C_CHEST）、心脏超声（C_ECHO）、检验
--     （C_LAB）、知情同意书（C_CONSENT），各带 REPORT 字段集（主要字段列）与名称字典（名称字典示例列）；
--   * 「通话记录」「其他」复用 A 预置 CALL / OTHER（V62，不改编码）：CALL 被前后端契约固定（后端
--     CallReportService.CALL_TYPE_CODE = 'CALL' 沟通记录联动、前端 report-dialog-logic CALL_TYPE_CODE /
--     DATE_CODES call_time），字段集（通话时间 / 通话说明 / 音频）与 C 规格主要字段一致；复用 A 预置 OTHER
--     同理（时间 / 说明 / 附件）。C 侧差异（名称选填、默认电子化状态、名称字典「随访电话 / 失访追踪」）是
--     库内数据动作，随激活 runbook 调整（docs/ops/hospital-c-report-preset.md §3）；
--   * 随包状态 = DISABLED（OQ-142①）：对医院 A / B / 其他租户惰性（添加报告单选不到、catalogTypes 只出
--     ENABLED），医院 C 侧按 runbook 激活——与 V91 计划草稿（OQ-128②）同「随包惰性 + 院内激活」口径，
--     不写租户分支（AGENTS §5.7）。名称与指标模板随包 ENABLED：只经所属类型露出，类型停用即不可达；
--   * 程控映射 C_PROGRAM_TO_FU（C-SCAN-005）：程控报告单指标模板 21 项（映射示例表：电池 3、起搏阈值与
--     脉宽 4、感知 2、阻抗 2、起搏比例 2、程控参数 4、ICD 项目 4）一一对应 C_PROGRAM_FU 字段（V90 键位
--     契约 f3nn）；目标 = FOLLOWUP_RECORD（位置中该表单的随访记录整条或程控分组，E16-T07 写入侧）；映射
--     ENABLED——类型停用下无 C_PROGRAM 报告单，对 A / B 天然惰性。无 REPORT_DATE 行：随访日期 f303 由
--     转录 / 补录流程落（OQ-131②：位置中无随访记录只存附件，新建补录记录在 C-SCAN-012 / E21-T17）；
--   * ICD 恰当 / 不恰当治疗是多选字段：写入值转换随 E23-T21 支持（MappingValues 多选 → 编码数组）；
--   * 检验「凝血四项」指标模板按规格示例预置 6 项（项目名称、代码、单位），参考范围以本院检验科为准、
--     不预置（C-SCAN-004 租户管理员维护）。
-- 版本快照：8 个 REPORT 表单建 fe_form_version / fe_form_version_field v1 快照（V90 同款；报告单类型字段
--   写入走 writeGateData 按版本取字段。A 预置表单未建快照是 V62 既有口径，不在本脚本补齐）。

-- ---------------------------------------------------------------- ① 按类型字段集（fe_form，usage_type = REPORT）

INSERT INTO fe_form (code, name, usage_type, current_version, categories, status) VALUES
  ('RPT_C_PROGRAM', '程控报告单（医院 C）', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_C_PAPER', '纸质随访表（医院 C）', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_C_IMPLANT', '植入手术记录（医院 C）', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_C_ECG', '心电图/动态心电图报告单（医院 C）', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_C_CHEST', '胸片报告单（医院 C）', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_C_ECHO', '心脏超声报告单（医院 C）', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_C_LAB', '检验报告单（医院 C）', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_C_CONSENT', '知情同意书（医院 C）', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED');

-- 主要字段列（C 规格预置资料类型表）：时间 = report_date（对话框公共日期槽位）；检查所见 / 检查诊断走
-- findings / conclusion 槽位编码（rpt_report 公共列）；程控参数不设字段——经指标模板行录入并映射写入
-- （C-SCAN-005「报告单不另存一份参数」）；附件是对话框公共部分。
INSERT INTO fe_field (form_code, field_key, field_code, label, category_key, type, required, sort_no, help_text) VALUES
  ('RPT_C_PROGRAM', 'f001', 'report_date', '时间', 'main', 'date', 1, 10, '程控报告单上的程控日期'),
  ('RPT_C_PROGRAM', 'f002', 'hospital', '医院', 'main', 'text', 0, 20, '程控医院名称'),
  ('RPT_C_PROGRAM', 'f003', 'brand', '品牌', 'main', 'text', 0, 30, '器械品牌（免费文本，报告单印刷内容）'),
  ('RPT_C_PAPER', 'f001', 'report_date', '时间', 'main', 'date', 1, 10, '纸质表对应的随访 / 登记日期'),
  ('RPT_C_IMPLANT', 'f001', 'report_date', '时间', 'main', 'date', 1, 10, '手术记录 / 出院小结等资料上的日期'),
  ('RPT_C_IMPLANT', 'f002', 'hospital', '医院', 'main', 'text', 0, 20, '手术医院名称'),
  ('RPT_C_IMPLANT', 'f003', 'summary', '手术记录摘要', 'main', 'textarea', 0, 30, '手术记录摘要（文本）'),
  ('RPT_C_ECG', 'f001', 'report_date', '时间', 'main', 'date', 1, 10, '检查日期'),
  ('RPT_C_ECG', 'f002', 'hospital', '医院', 'main', 'text', 0, 20, '检查医院名称'),
  ('RPT_C_ECG', 'f003', 'result', '测量结果', 'main', 'textarea', 0, 30, '心电图测量结果（文本）'),
  ('RPT_C_ECG', 'f004', 'findings', '检查所见', 'main', 'textarea', 0, 40, NULL),
  ('RPT_C_ECG', 'f005', 'conclusion', '检查诊断', 'main', 'textarea', 0, 50, NULL),
  ('RPT_C_CHEST', 'f001', 'report_date', '时间', 'main', 'date', 1, 10, '检查日期'),
  ('RPT_C_CHEST', 'f002', 'hospital', '医院', 'main', 'text', 0, 20, '检查医院名称'),
  ('RPT_C_CHEST', 'f003', 'part', '检查部位', 'main', 'text', 0, 30, '如胸部正位'),
  ('RPT_C_CHEST', 'f004', 'findings', '检查所见', 'main', 'textarea', 0, 40, NULL),
  ('RPT_C_CHEST', 'f005', 'conclusion', '检查诊断', 'main', 'textarea', 0, 50, NULL),
  ('RPT_C_ECHO', 'f001', 'report_date', '时间', 'main', 'date', 1, 10, '检查日期'),
  ('RPT_C_ECHO', 'f002', 'hospital', '医院', 'main', 'text', 0, 20, '检查医院名称'),
  ('RPT_C_ECHO', 'f003', 'result', '测量结果', 'main', 'textarea', 0, 30, '超声测量结果（文本）'),
  ('RPT_C_ECHO', 'f004', 'findings', '检查所见', 'main', 'textarea', 0, 40, NULL),
  ('RPT_C_ECHO', 'f005', 'conclusion', '检查诊断', 'main', 'textarea', 0, 50, NULL),
  ('RPT_C_LAB', 'f001', 'report_date', '时间', 'main', 'date', 1, 10, '检验日期；指标行由名称绑定的模板带出（C-SCAN-004）'),
  ('RPT_C_LAB', 'f002', 'hospital', '医院', 'main', 'text', 0, 20, '检验医院名称'),
  ('RPT_C_LAB', 'f003', 'device', '设备仪器', 'main', 'text', 0, 30, '检验设备 / 仪器（报告单印刷内容）'),
  ('RPT_C_CONSENT', 'f001', 'report_date', '时间', 'main', 'date', 1, 10, '资料归档时间'),
  ('RPT_C_CONSENT', 'f002', 'sign_date', '签署时间', 'main', 'date', 0, 20, '知情同意书上的签署日期'),
  ('RPT_C_CONSENT', 'f003', 'sign_place', '签署地点', 'main', 'text', 0, 30, '签署地点');

-- v1 版本快照（V90 同款：写入闸门 writeGateData 按版本取字段，字典 / 带入快照列随 V89 列清单）
INSERT INTO fe_form_version (form_code, version, categories, field_count, published_by)
SELECT fm.code, fm.current_version, fm.categories,
       (SELECT COUNT(*) FROM fe_field f WHERE f.form_code = fm.code AND f.status = 'ENABLED'), NULL
FROM fe_form fm
WHERE fm.code IN ('RPT_C_PROGRAM', 'RPT_C_PAPER', 'RPT_C_IMPLANT', 'RPT_C_ECG',
                  'RPT_C_CHEST', 'RPT_C_ECHO', 'RPT_C_LAB', 'RPT_C_CONSENT');

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
WHERE f.form_code IN ('RPT_C_PROGRAM', 'RPT_C_PAPER', 'RPT_C_IMPLANT', 'RPT_C_ECG',
                      'RPT_C_CHEST', 'RPT_C_ECHO', 'RPT_C_LAB', 'RPT_C_CONSENT')
  AND f.status = 'ENABLED';

-- ---------------------------------------------------------------- ② 8 类本院预置（随包 DISABLED，C 侧 runbook 激活）

-- name_required：C-SCAN-003 检查、检验类必填（心电图 / 胸片 / 心脏超声 / 检验），其他类型选填；
-- default_digitize_status：C-SCAN-011 程控报告单 / 纸质随访表 / 植入手术单待录入（PENDING），
-- 其余无需录入（NO_ENTRY，不计入补录进度）；植入手术记录的「出院小结」「起搏器识别卡」由名称覆盖为
-- NO_ENTRY（规格预置资料类型表）；sort_no 接在 A 预置 10–100 之后（110–180），C 激活时可按本院序调整。
INSERT INTO rpt_type (code, name, form_code, name_required, default_digitize_status, sort_no, status, remark) VALUES
  ('C_PROGRAM', '程控报告单', 'RPT_C_PROGRAM', 0, 'PENDING', 110, 'DISABLED', '医院 C 预置（C-SCAN-002）：程控参数经「程控报告单指标」模板行录入、按映射 C_PROGRAM_TO_FU 写入随访程控记录（C-SCAN-005，V94）；随包停用，C 侧激活见 docs/ops/hospital-c-report-preset.md'),
  ('C_PAPER_FORM', '纸质随访表', 'RPT_C_PAPER', 0, 'PENDING', 120, 'DISABLED', '医院 C 预置（C-SCAN-002）：待录入，转录到随访记录后自动已电子化（C-SCAN-011）'),
  ('C_IMPLANT_RECORD', '植入手术记录', 'RPT_C_IMPLANT', 0, 'PENDING', 130, 'DISABLED', '医院 C 预置（C-SCAN-002）：「出院小结」「起搏器识别卡」名称覆盖为无需录入'),
  ('C_ECG', '心电图/动态心电图', 'RPT_C_ECG', 1, 'NO_ENTRY', 140, 'DISABLED', '医院 C 预置（C-SCAN-002 / 003）：检查类名称必填'),
  ('C_CHEST', '胸片', 'RPT_C_CHEST', 1, 'NO_ENTRY', 150, 'DISABLED', '医院 C 预置（C-SCAN-002 / 003）：检查类名称必填'),
  ('C_ECHO', '心脏超声', 'RPT_C_ECHO', 1, 'NO_ENTRY', 160, 'DISABLED', '医院 C 预置（C-SCAN-002 / 003）：检查类名称必填'),
  ('C_LAB', '检验', 'RPT_C_LAB', 1, 'NO_ENTRY', 170, 'DISABLED', '医院 C 预置（C-SCAN-002 / 003）：检验类名称必填，名称绑定指标模板（C-SCAN-004，凝血四项随包）'),
  ('C_CONSENT', '知情同意书', 'RPT_C_CONSENT', 0, 'NO_ENTRY', 180, 'DISABLED', '医院 C 预置（C-SCAN-002）：签署时间与签署地点（C 规格主要字段列）');

-- ---------------------------------------------------------------- ③ 指标模板（C-SCAN-004）

INSERT INTO rpt_indicator_tpl (name, status, remark) VALUES
  ('凝血四项', 'ENABLED', '医院 C 预置（C-SCAN-004 指标模板示例）：项目名称、代码与单位按规格示例；参考范围以本院检验科为准、不预置'),
  ('程控报告单指标', 'ENABLED', '医院 C 预置（C-SCAN-005 映射示例表）：电池 3、起搏阈值与脉宽 4、感知 2、阻抗 2、起搏比例 2、程控参数 4、ICD 项目 4 共 21 项，与随访程控记录字段（V90 C_PROGRAM_FU）一一对应；单位与字段字典一致');

INSERT INTO rpt_indicator_item (tpl_id, item_name, item_code, unit, ref_range, ref_range_male, ref_range_female, sort_no)
SELECT t.id, v.item_name, v.item_code, v.unit, NULL, NULL, NULL, v.sort_no
FROM rpt_indicator_tpl t
JOIN (SELECT '凝血四项' AS tpl_name, '凝血酶原时间' AS item_name, 'PT' AS item_code, 's' AS unit, 10 AS sort_no
      UNION ALL SELECT '凝血四项', '凝血酶原活动度', 'PT%', '%', 20
      UNION ALL SELECT '凝血四项', '国际标准化比值', 'INR', NULL, 30
      UNION ALL SELECT '凝血四项', '活化部分凝血活酶时间', 'APTT', 's', 40
      UNION ALL SELECT '凝血四项', '凝血酶时间', 'TT', 's', 50
      UNION ALL SELECT '凝血四项', '纤维蛋白原', 'Fbg', 'g/L', 60
      UNION ALL SELECT '程控报告单指标', '电池电压(V)', 'battery_voltage', 'V', 10
      UNION ALL SELECT '程控报告单指标', 'ERI状态', 'eri_status', NULL, 20
      UNION ALL SELECT '程控报告单指标', '预计电池寿命(年)', 'battery_life', '年', 30
      UNION ALL SELECT '程控报告单指标', '心房起搏阈值(V)', 'ra_threshold', 'V', 40
      UNION ALL SELECT '程控报告单指标', '心房脉宽(ms)', 'ra_pulse_width', 'ms', 50
      UNION ALL SELECT '程控报告单指标', '心室起搏阈值(V)', 'rv_threshold', 'V', 60
      UNION ALL SELECT '程控报告单指标', '心室脉宽(ms)', 'rv_pulse_width', 'ms', 70
      UNION ALL SELECT '程控报告单指标', '心房感知P波(mV)', 'ra_sensing', 'mV', 80
      UNION ALL SELECT '程控报告单指标', '心室感知R波(mV)', 'rv_sensing', 'mV', 90
      UNION ALL SELECT '程控报告单指标', '心房阻抗(Ω)', 'ra_impedance', 'Ω', 100
      UNION ALL SELECT '程控报告单指标', '心室阻抗(Ω)', 'rv_impedance', 'Ω', 110
      UNION ALL SELECT '程控报告单指标', '心房起搏比例(%)', 'ra_pacing_ratio', '%', 120
      UNION ALL SELECT '程控报告单指标', '心室起搏比例(%)', 'rv_pacing_ratio', '%', 130
      UNION ALL SELECT '程控报告单指标', '程控起搏模式', 'programmed_mode', NULL, 140
      UNION ALL SELECT '程控报告单指标', '下限频率(bpm)', 'lower_rate', 'bpm', 150
      UNION ALL SELECT '程控报告单指标', '上限频率(bpm)', 'upper_rate', 'bpm', 160
      UNION ALL SELECT '程控报告单指标', 'AV间期(ms)', 'av_delay', 'ms', 170
      UNION ALL SELECT '程控报告单指标', '充电时间(s)', 'charge_time', 's', 180
      UNION ALL SELECT '程控报告单指标', '高压阻抗(Ω)', 'hv_impedance', 'Ω', 190
      UNION ALL SELECT '程控报告单指标', 'ICD恰当治疗', 'icd_appropriate', NULL, 200
      UNION ALL SELECT '程控报告单指标', 'ICD不恰当治疗', 'icd_inappropriate', NULL, 210) v
  ON v.tpl_name = t.name;

-- ---------------------------------------------------------------- ④ 名称字典（C-SCAN-003；规格「名称字典示例」列）

-- 程控报告单三个名称绑定「程控报告单指标」模板（选名称带出 21 个项目，只填结果）；检验「凝血四项」绑定
-- 「凝血四项」模板；其余名称不绑定（选填指标行时无模板不可填，符合规格示例）。通话记录 / 其他复用 A 预置
-- 类型行，名称（随访电话 / 失访追踪）随 C 激活 runbook 补充（共享行不随包改，避免影响 A / B）。
INSERT INTO rpt_name (type_code, name, indicator_tpl_id, digitize_status_override, sort_no, status)
SELECT v.type_code, v.name, t.id, v.digitize_override, v.sort_no, 'ENABLED'
FROM (SELECT 'C_PROGRAM' AS type_code, '常规程控' AS name, '程控报告单指标' AS tpl_name, NULL AS digitize_override, 10 AS sort_no
      UNION ALL SELECT 'C_PROGRAM', '术中测试', '程控报告单指标', NULL, 20
      UNION ALL SELECT 'C_PROGRAM', 'ERI 评估', '程控报告单指标', NULL, 30
      UNION ALL SELECT 'C_PAPER_FORM', '随访程控记录单', NULL, NULL, 10
      UNION ALL SELECT 'C_PAPER_FORM', '患者登记表', NULL, NULL, 20
      UNION ALL SELECT 'C_IMPLANT_RECORD', '手术记录', NULL, NULL, 10
      UNION ALL SELECT 'C_IMPLANT_RECORD', '出院小结', NULL, 'NO_ENTRY', 20
      UNION ALL SELECT 'C_IMPLANT_RECORD', '起搏器识别卡', NULL, 'NO_ENTRY', 30
      UNION ALL SELECT 'C_ECG', '常规心电图', NULL, NULL, 10
      UNION ALL SELECT 'C_ECG', '动态心电图（Holter）', NULL, NULL, 20
      UNION ALL SELECT 'C_CHEST', '胸部正位片', NULL, NULL, 10
      UNION ALL SELECT 'C_CHEST', '胸部正侧位片', NULL, NULL, 20
      UNION ALL SELECT 'C_ECHO', '经胸心脏超声', NULL, NULL, 10
      UNION ALL SELECT 'C_LAB', '血常规', NULL, NULL, 10
      UNION ALL SELECT 'C_LAB', '凝血四项', '凝血四项', NULL, 20
      UNION ALL SELECT 'C_LAB', '肝功能', NULL, NULL, 30
      UNION ALL SELECT 'C_LAB', '肾功能', NULL, NULL, 40
      UNION ALL SELECT 'C_LAB', '电解质', NULL, NULL, 50
      UNION ALL SELECT 'C_LAB', 'NT-proBNP', NULL, NULL, 60
      UNION ALL SELECT 'C_CONSENT', '植入知情同意书', NULL, NULL, 10
      UNION ALL SELECT 'C_CONSENT', '程控知情同意书', NULL, NULL, 20) v
LEFT JOIN rpt_indicator_tpl t ON t.name = v.tpl_name;

-- ---------------------------------------------------------------- ⑤ 程控报告单 → 随访程控记录映射（C-SCAN-005）

-- 目标 = 位置中该表单的随访记录（整条或程控分组，E16-T07 预览 / 确认写入）；报告单不另存一份参数；
-- 无附带写入（映射示例表未列）；写入侧值转换：数值直存、ERI 状态按选项名或编码、起搏模式按统一起搏
-- 模式字典（Q08）、ICD 恰当 / 不恰当治疗多选（E23-T21 起支持）。
INSERT INTO rpt_mapping (code, name, type_code, target_type, target_form_code, phase, indicator_tpl_id, extra_writes, sort_no, status, remark)
SELECT v.code, v.name, v.type_code, v.target_type, v.target_form_code, v.phase, t.id, v.extra_writes, v.sort_no, v.status, v.remark
FROM (SELECT 'C_PROGRAM_TO_FU' AS code, '程控报告单 → 随访程控记录' AS name, 'C_PROGRAM' AS type_code, 'FOLLOWUP_RECORD' AS target_type, 'C_PROGRAM_FU' AS target_form_code, NULL AS phase, '程控报告单指标' AS tpl_name, NULL AS extra_writes, 10 AS sort_no, 'ENABLED' AS status, '医院 C 预置（C-SCAN-005 映射示例表）：程控参数录入即写入映射目标随访记录（报告单位置之一，多条时选一条；位置中无随访记录只存附件、参数经转录流程新建补录记录后写入，OQ-131②）；对比标红与 ERI 期切换随写入后的记录值生效（V82 / E21-T06）' AS remark) v
JOIN rpt_indicator_tpl t ON t.name = v.tpl_name;

-- 项目代码 = C_PROGRAM_FU 字段编码（V90），一一对应；排序按映射示例表行序（电池 → ICD 项目）。
INSERT INTO rpt_mapping_item (mapping_id, item_code, target_field_key, extra_writes, sort_no)
SELECT m.id, v.item_code, v.target_field_key, NULL, v.sort_no
FROM (SELECT 'C_PROGRAM_TO_FU' AS mapping_code, 'battery_voltage' AS item_code, 'f322' AS target_field_key, 10 AS sort_no
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'eri_status', 'f323', 20
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'battery_life', 'f324', 30
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'ra_threshold', 'f308', 40
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'ra_pulse_width', 'f309', 50
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'rv_threshold', 'f313', 60
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'rv_pulse_width', 'f314', 70
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'ra_sensing', 'f310', 80
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'rv_sensing', 'f315', 90
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'ra_impedance', 'f311', 100
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'rv_impedance', 'f316', 110
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'ra_pacing_ratio', 'f312', 120
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'rv_pacing_ratio', 'f317', 130
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'programmed_mode', 'f318', 140
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'lower_rate', 'f319', 150
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'upper_rate', 'f320', 160
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'av_delay', 'f321', 170
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'charge_time', 'f340', 180
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'hv_impedance', 'f341', 190
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'icd_appropriate', 'f329', 200
      UNION ALL SELECT 'C_PROGRAM_TO_FU', 'icd_inappropriate', 'f330', 210) v
JOIN rpt_mapping m ON m.code = v.mapping_code;
