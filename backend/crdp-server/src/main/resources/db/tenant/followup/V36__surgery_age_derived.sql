-- E11-T09 / A-FLD-013：出生日期（f003，V28 已建）接上手术年龄派生——手术日期 − 出生日期，取周岁
--   （AGE_YEARS，E11-T05 内置函数库；医院 A 规格「派生字段计算规则」表第一行）；基本信息不采集身份证号（A-FLD-013）。
-- 演示种子（虚构表单 A_BASELINE）：真实 266 字段由产品经字段字典 Excel 批量导入录入（E23-T01，
--   POST /api/hospital/form/fields/import，本任务落地的导入工具）；C 的新增字段（C-FLD-008）同样经迁移 / 导入录入，不提供页面。
-- 演示版本 v1 快照补入新字段：与 V31 / V33 同款回填，保持 v1 口径完整（field_count 重算）。

INSERT INTO fe_field
  (form_code, field_key, field_code, label, category_key, type, derived_def, sort_no, help_text) VALUES
  ('A_BASELINE', 'f032', 'surgery_age', '手术年龄', 'surgery', 'derived',
   JSON_OBJECT('fn', 'AGE_YEARS', 'args', JSON_ARRAY('f003', 'f020')), 12,
   '手术日期 − 出生日期，取周岁（A-FLD-013：出生日期 f003、手术日期 f020；不采集身份证号）');

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
WHERE f.form_code = 'A_BASELINE' AND f.field_key = 'f032'
  AND NOT EXISTS (
    SELECT 1 FROM fe_form_version_field x
    WHERE x.version_id = v.id AND x.field_key = f.field_key
  );

UPDATE fe_form_version
SET field_count = (SELECT COUNT(*) FROM fe_form_version_field WHERE version_id = fe_form_version.id)
WHERE form_code = 'A_BASELINE';
