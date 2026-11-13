-- E21-T24 / C-FLD-007（Q04、CL-01）：修正 V90 预置的跨字段硬规则 upper_rate_gt_lower。
--   V90 把规则写成 {"leftField":"f320","op":"gt","rightField":"f319"}，而表单引擎的 COMPARE 契约
--   只支持 op = lt / lte（FormRuleEngine.parseParams 不认识的 op 防御跳过；前后端共享用例
--   shared-cases/form-validation-cases.json 同口径），导致「上限频率 ≤ 下限频率 → 阻止保存」
--   的硬校验从未生效（CProgramEntryApiIT.upperRateNotAboveLowerRateBlocksSubmit 先红后绿锁住）。
--   修正为契约内的等价表达：下限频率 f319 lt 上限频率 f320 须成立（即上限 > 下限；相等也拦截）。
--   rule_id / severity / message / 挂靠字段 f320 不变，不产生新规则行。
UPDATE fe_rule
   SET params = '{"leftField":"f319","op":"lt","rightField":"f320"}'
 WHERE form_code = 'C_PROGRAM_FU'
   AND rule_id = 'upper_rate_gt_lower';
