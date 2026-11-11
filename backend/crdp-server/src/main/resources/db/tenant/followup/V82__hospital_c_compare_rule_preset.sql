-- E21-T20 / C-FU-010、C-FU-011（Q02）：预置医院 C 程控随访表单（C_PROGRAM_FU）的默认对比标红规则。
-- 基准是 hospital-c-spec 第 5 章「异常对比规则（医院已确认，阈值可配置，Q02）」表 4 行，拆成 12 条单字段规则：
--   起搏阈值升高（心房 f308 / 心室 f313，CHANGE_PCT RISE > 50%；脉宽不同时注明仅供参考——
--     params.noteField 指向同腔室脉宽（f309 / f314）、noteText 固定文案，命中且脉宽本次与上一次
--     均有值且不同时提示追加「；脉宽不同，仅供参考」，components.md §10.4）；
--   电极阻抗波动（心房 f311 / 心室 f316，CHANGE_PCT ANY > 50%，升高或降低都标红）；
--   阻抗绝对范围 200–2000 Ω 为「可另开」（默认关闭）：每腔室拆 LT 200 / GT 2000 两条，
--     显式写 status = 'DISABLED'，由租户管理员在配置页（/system/abnormal-rule，followup:abnormal:config）
--     逐条开启——严格边界（恰 200 / 2000 不标红）；
--   感知明显下降（P 波 f310 / R 波 f315，CHANGE_PCT DROP > 50%）；
--   电池接近更换（ERI 状态 f323 取值命中 非正常；预计电池寿命 f324 ≤ 1 年，VALUE_HIT）。
-- 只在录入表单内标红提示，不发通知、不生成待办、不影响提交（Q02；「录入标红参数后不产生任何通知」）。
--
-- 字段键为 C_PROGRAM_FU 稳定键契约（附录 A 随访组 #n → f3nn，程控组占用 f301–f334；ERI 状态选项集
--   c_eri_status：0=正常 / 1=接近ERI / 2=已达ERI(EOI)；分类锚点 threshold / sensing / impedance / battery
--   对应程控分组「起搏阈值与脉宽 / 感知 / 阻抗 / 电池」）——fe_form / fe_field 字典随 E23-T20 录入，
--   届时必须使用这些键（OQ-130；E21-T20 先行预置规则，字典落地前配置页字段列显示稳定键）。
-- 求值引擎与 A 共用（E17 CompareEvaluator / packages/rules evaluateCompareRules，七类规则）；
--   正反用例随本任务录入 shared-cases/compare-cases-c.json（后端 CCompareRuleCasesTest +
--   前端 form-compare-c.test.ts 各执行一遍），规则定义与本脚本由 CCompareRulePresetSqlTest 绑定。
-- 首条 INSERT 不写 status / updated_by 列（默认 ENABLED / NULL，与 V67 / V70 同款）；
--   第二条 INSERT 带显式 status = 'DISABLED'（绝对范围默认关闭）；(form_code, rule_code) 唯一键防重复。

INSERT INTO fu_compare_rule
  (form_code, rule_code, name, category_key, target_field, rule_type, params,
   baseline_form_code, baseline_field, message, sort_no) VALUES
  -- 起搏阈值升高（Q02：比上次升高＞50%；脉宽不同时注明仅供参考）
  ('C_PROGRAM_FU', 'ra_threshold_rise', '心房起搏阈值升高', 'threshold', 'f308', 'CHANGE_PCT',
   JSON_OBJECT('direction', 'RISE', 'percent', 50, 'noteField', 'f309', 'noteText', '脉宽不同，仅供参考'),
   NULL, NULL, '心房起搏阈值比上次升高超过 50%', 10),
  ('C_PROGRAM_FU', 'rv_threshold_rise', '心室起搏阈值升高', 'threshold', 'f313', 'CHANGE_PCT',
   JSON_OBJECT('direction', 'RISE', 'percent', 50, 'noteField', 'f314', 'noteText', '脉宽不同，仅供参考'),
   NULL, NULL, '心室起搏阈值比上次升高超过 50%', 11),
  -- 电极阻抗波动（Q02：比上次升高或降低＞50%）
  ('C_PROGRAM_FU', 'ra_impedance_shift', '心房阻抗波动', 'impedance', 'f311', 'CHANGE_PCT',
   JSON_OBJECT('direction', 'ANY', 'percent', 50),
   NULL, NULL, '心房阻抗比上次升高或降低超过 50%', 20),
  ('C_PROGRAM_FU', 'rv_impedance_shift', '心室阻抗波动', 'impedance', 'f316', 'CHANGE_PCT',
   JSON_OBJECT('direction', 'ANY', 'percent', 50),
   NULL, NULL, '心室阻抗比上次升高或降低超过 50%', 21),
  -- 感知明显下降（Q02：比上次下降＞50%）
  ('C_PROGRAM_FU', 'ra_sensing_drop', '心房感知下降', 'sensing', 'f310', 'CHANGE_PCT',
   JSON_OBJECT('direction', 'DROP', 'percent', 50),
   NULL, NULL, '心房感知 P 波比上次下降超过 50%', 30),
  ('C_PROGRAM_FU', 'rv_sensing_drop', '心室感知下降', 'sensing', 'f315', 'CHANGE_PCT',
   JSON_OBJECT('direction', 'DROP', 'percent', 50),
   NULL, NULL, '心室感知 R 波比上次下降超过 50%', 31),
  -- 电池接近更换（Q02：ERI 状态为接近ERI或已达ERI(EOI)，或预计电池寿命 ≤ 1 年）
  ('C_PROGRAM_FU', 'eri_status_abnormal', 'ERI 状态异常', 'battery', 'f323', 'VALUE_HIT',
   JSON_OBJECT('codes', JSON_ARRAY('1', '2')),
   NULL, NULL, 'ERI 状态为接近ERI或已达ERI(EOI)', 40),
  ('C_PROGRAM_FU', 'battery_life_low', '预计电池寿命偏低', 'battery', 'f324', 'VALUE_HIT',
   JSON_OBJECT('op', 'LTE', 'value', 1),
   NULL, NULL, '预计电池寿命 ≤ 1 年', 41);

-- 阻抗绝对范围 200–2000 Ω（Q02「可另开」：默认关闭，租户管理员逐条开启；每腔室两个边界各一条）
INSERT INTO fu_compare_rule
  (form_code, rule_code, name, category_key, target_field, rule_type, params,
   baseline_form_code, baseline_field, message, sort_no, status) VALUES
  ('C_PROGRAM_FU', 'ra_impedance_below_200', '心房阻抗低于 200', 'impedance', 'f311', 'OUT_OF_RANGE',
   JSON_OBJECT('op', 'LT', 'value', 200),
   NULL, NULL, '心房阻抗 ＜ 200 Ω（超出提示范围）', 22, 'DISABLED'),
  ('C_PROGRAM_FU', 'ra_impedance_above_2000', '心房阻抗高于 2000', 'impedance', 'f311', 'OUT_OF_RANGE',
   JSON_OBJECT('op', 'GT', 'value', 2000),
   NULL, NULL, '心房阻抗 ＞ 2000 Ω（超出提示范围）', 23, 'DISABLED'),
  ('C_PROGRAM_FU', 'rv_impedance_below_200', '心室阻抗低于 200', 'impedance', 'f316', 'OUT_OF_RANGE',
   JSON_OBJECT('op', 'LT', 'value', 200),
   NULL, NULL, '心室阻抗 ＜ 200 Ω（超出提示范围）', 24, 'DISABLED'),
  ('C_PROGRAM_FU', 'rv_impedance_above_2000', '心室阻抗高于 2000', 'impedance', 'f316', 'OUT_OF_RANGE',
   JSON_OBJECT('op', 'GT', 'value', 2000),
   NULL, NULL, '心室阻抗 ＞ 2000 Ω（超出提示范围）', 25, 'DISABLED');
