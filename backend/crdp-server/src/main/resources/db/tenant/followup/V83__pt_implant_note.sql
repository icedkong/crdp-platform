-- E21-T02 / C-PAT-006：更换、升级与电极修正（医院 C，功能开关 implant）。
--
-- C 规格 §4「关键字段 · 植入类型」：类型选「其他」须填写说明（验收用例 C-ACC-06
-- 「『其他』类型须填说明」）。附录 A 植入 #11 的 indication_note 只承载植入指征
-- 「其他」的补充（Q16），不与植入类型说明混用，故单独加 note 列：
--   * REPLACE / UPGRADE / LEAD_REVISION 不填；OTHER 必填（应用层校验 40001）；
--   * 字段级历史（C-PAT-011）随 E23-T20 C_IMPLANT 表单模板落地（OQ-129②同口径）。
-- 本任务不建新表：更换 / 升级 / 电极修正复用 pt_implant（每次变更一条记录，
-- 旧记录自动结束为已更换、结束日期 = 新植入日期，C 规格 §4 业务规则「每种变更
-- 保留历史」）；电极沿用 / 废弃 / 拔除复用 pt_lead + pt_implant_lead（沿用 =
-- 同一电极行挂到新植入记录下并保留原植入日期，V81 注释预定的 E21-T02 语义）。

ALTER TABLE pt_implant
  ADD COLUMN note VARCHAR(255) NULL COMMENT '植入说明（植入类型选「其他」时必填，C 规格 §4 关键字段表）' AFTER indication_note;
