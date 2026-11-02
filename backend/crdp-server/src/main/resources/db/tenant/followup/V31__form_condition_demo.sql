-- E11-T03 / A-FLD-004、C-FLD-005：条件显示与条件必填演示字段（fe_field.visible_when / required_when，V28 建列）。
-- 条件树与高级检索同构（tech-plan §6「条件显示与条件必填」）：节点 {all|any} 可嵌套，叶子 {field|ctx, op, value}
--   （op = eq|ne|in|nin|gt|gte|lt|lte|empty|notEmpty；field 取本表单字段稳定键，ctx 取上下文变量，如医院 C 的
--   device.type / device.hasRaLead，随 E21-T03 器械匹配落地）。
-- 求值语义前后端一致（Java FormConditionEngine / TS packages/rules，form.md §2.4）：
--   条件为空恒真；操作数缺失（未答 / null）除 empty / notEmpty 外一律不成立（未答 ≠ 否）；
--   eq / in 按值编码口径严格同型（1 与 "1" 不等）；隐藏字段不参与必填与范围 / 跨字段，提交时值清空
--   （FormService.stripHiddenFields；清空写 fe_field_history 随 E11-T06）。
-- 演示种子（虚构）：吸烟史（f010）= 是 → 显示并必填「每日吸烟支数」（f012）；真实条件配置随 E23-T01 字段录入。

INSERT INTO fe_field
  (form_code, field_key, field_code, label, category_key, type, unit, decimals, hard_range,
   required, visible_when, required_when, sort_no, help_text) VALUES
  ('A_BASELINE', 'f012', 'cigarettes_per_day', '每日吸烟支数', 'basic_info', 'number', '支', 0,
   JSON_OBJECT('min', 1, 'max', 100), 0,
   JSON_OBJECT('all', JSON_ARRAY(JSON_OBJECT('field', 'f010', 'op', 'eq', 'value', 1))),
   JSON_OBJECT('all', JSON_ARRAY(JSON_OBJECT('field', 'f010', 'op', 'eq', 'value', 1))),
   45, '示例（A-FLD-004）：吸烟史=是才显示并必填；被隐藏时提交清空（E11-T03）');

-- 演示版本 v1 快照补入该字段：新装库 V31 在 V29 之后执行，v1 快照固化于 V29，需同步补齐保持 v1 口径完整
-- （快照插入形态与 V29 一致；field_count 重算）。
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
WHERE f.form_code = 'A_BASELINE' AND f.field_key = 'f012'
  AND NOT EXISTS (
    SELECT 1 FROM fe_form_version_field x
    WHERE x.version_id = v.id AND x.field_key = f.field_key
  );

UPDATE fe_form_version
SET field_count = (SELECT COUNT(*) FROM fe_form_version_field WHERE version_id = fe_form_version.id)
WHERE form_code = 'A_BASELINE';
