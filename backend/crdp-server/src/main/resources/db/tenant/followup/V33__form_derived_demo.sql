-- E11-T05 / A-FLD-005、C-FLD-006：派生字段演示（fe_field.derived_def {fn, args}，V28 建列）。
-- 内置函数库 9 个函数（tech-plan §6「派生字段」；Java FormDerivedEngine / TS packages/rules 同语义，
-- 共享用例 shared-cases/form-derived-cases.json 双端锁定，form.md §2.6）：
--   DAYS_INCLUSIVE(起, 止) 术后住院天数｜MINUTES_BETWEEN(起, 止) 手术时长min｜HOURS_BETWEEN_1DP(起, 止) ICU时间h
--   BMI(体重kg, 身高cm)｜AGE_YEARS(出生, 参考日?) 周岁｜SUM(...) 桥血管数量
--   YEARS_SINCE(起, 止?) 随访年限｜DURATION_YM(起, 止?) 植入时长「X 年 Y 个月」｜CHANGE_PCT(本次, 上次) 变化率
-- 口径（A-FLD-005）：来源改动实时重算（前端拓扑顺序）、后端保存时再算一遍并以后端结果为准
--   （FormService 写入闸门：来源齐全以计算值为准、来源缺失保留手工值 data_meta.manual、
--    结果无效不允许手工值 42200 rule=derived）；留空并提示原因（缺少身高 / 出院日期早于手术日期…）。
-- 演示种子（虚构）：身高（f011，V28）+ 体重（f013）→ BMI（f031）；手术（f020，V28）+ 出院（f021）
--   → 术后住院天数（f030，V28 已建 derived_def 引用 f021，本迁移补齐来源字段）；真实派生配置随
--   E23-T01 / C 字段字典录入。

INSERT INTO fe_field
  (form_code, field_key, field_code, label, category_key, type, unit, decimals, hard_range, sort_no, help_text) VALUES
  ('A_BASELINE', 'f013', 'weight_kg', '体重', 'basic_info', 'number', 'kg', 1,
   JSON_OBJECT('min', 10, 'max', 400), 55,
   '示例：BMI 的来源字段（A-FLD-005，计算引擎 E11-T05）');

INSERT INTO fe_field
  (form_code, field_key, field_code, label, category_key, type, sort_no, help_text) VALUES
  ('A_BASELINE', 'f021', 'discharge_date', '出院日期', 'surgery', 'date', 15,
   '示例：术后住院天数 = 出院日期 − 手术日期 + 1 的止日（含当天）');

INSERT INTO fe_field
  (form_code, field_key, field_code, label, category_key, type, decimals, derived_def, sort_no, help_text) VALUES
  ('A_BASELINE', 'f031', 'bmi', 'BMI', 'basic_info', 'derived', 1,
   JSON_OBJECT('fn', 'BMI', 'args', JSON_ARRAY('f013', 'f011')), 60,
   '示例：派生 BMI = 体重 kg ÷ (身高 cm ÷ 100)²，1 位小数（身高 175 体重 70 → 22.9；来源缺失留空提示并允许手工录入）');

-- 演示版本 v1 快照补入新字段：新装库 V33 在 V29 之后执行，v1 快照固化于 V29，需同步补齐保持 v1 口径完整
-- （快照插入形态与 V29 / V31 一致；field_count 重算）。
INSERT INTO fe_form_version_field
  (version_id, form_code, version, field_key, field_code, label, category_key, type,
   unit, decimals, hard_range, soft_range, option_set_code, required,
   visible_when, required_when, derived_def, sensitive_flag, storage_type, help_text, sort_no)
SELECT v.id, f.form_code, v.version, f.field_key, f.field_code, f.label, f.category_key, f.type,
       f.unit, f.decimals, f.hard_range, f.soft_range, f.option_set_code, f.required,
       f.visible_when, f.required_when, f.derived_def, f.sensitive_flag, f.storage_type,
       f.help_text, f.sort_no
FROM fe_field f
JOIN fe_form fm ON fm.code = f.form_code
JOIN fe_form_version v ON v.form_code = fm.code AND v.version = fm.current_version
WHERE f.form_code = 'A_BASELINE' AND f.field_key IN ('f013', 'f021', 'f031')
  AND NOT EXISTS (
    SELECT 1 FROM fe_form_version_field x
    WHERE x.version_id = v.id AND x.field_key = f.field_key
  );

UPDATE fe_form_version
SET field_count = (SELECT COUNT(*) FROM fe_form_version_field WHERE version_id = fe_form_version.id)
WHERE form_code = 'A_BASELINE';
