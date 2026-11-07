-- E17-T04 / A-FU-027（tech-plan §11「结果存 fu_record.highlight」「规则变更重算已提交记录」）：
-- 随访记录表增加对比标红命中列 highlight。
--
--   * 形状：命中数组，元素与前端 CompareHit / 后端 CompareEvaluator.Hit 同形
--     （ruleId / key / categoryKey / message / previousValue / change / changeText，
--     E17-T03 共享用例锁定的那份载荷）；空值键省略。
--   * 语义：后端保存复算结果（提交时用启用规则 + 上一次记录解析再算一次，与前端实时标红同源）；
--     无命中 / 该表单无启用规则 / 功能开关 compare_highlight 关闭 → NULL（不存空数组）。
--   * 重算触发（同一事务，CompareHighlightService）：
--     ① 记录提交（含补录 / 计划外）——本次落库 + 同患者同表单其余已提交记录重算
--        （补录一条更早的记录后，排在它之后的记录「上一次」变化，tech-plan §11）；
--     ② 对比规则变更（PUT /compare-rules/{id}）——该表单全部已提交记录按新阈值重算（停用规则即清除）；
--     ③ 报告单映射写入（E16-T07「录入时对比标红同样生效」）——目标记录为随访记录或基线分节时，
--       受影响患者相关表单的已提交记录重算。
--   * 只在 A 规格口径下是「表单内提示」（不进列表 / 首页）；持久化供 C 首页「异常标红记录」
--     （C-DASH-006，随 E21）与统计读取，本迁移不建读侧索引（读取方随 E21 按需加）。
--   * 写入不触碰 updated_by / updated_at（系统重算不是用户编辑，审计走 COMPARE_RULE_RECALC 汇总条目）。
--   * 老库行 highlight 为 NULL：下次任一重算触发点（提交 / 规则变更 / 映射写入）覆盖到即补齐。

ALTER TABLE fu_record
  ADD COLUMN highlight JSON NULL
  COMMENT '对比标红命中（E17-T04 / A-FU-027，tech-plan §11）：保存复算结果，数组元素同 CompareHit 形；无命中 / 无启用规则 / 功能关闭为 NULL；提交、规则变更、报告单映射写入后重算'
  AFTER data_meta;
