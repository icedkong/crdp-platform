-- E15-T01 / A-FU-003、A-FU-010：任务完成标记三列（验收「逾期完成单独标记」）。
--
-- A 规格「关键字段 · 任务状态」表：已完成 = 随访记录已提交；窗口开启前提交另标「窗口前完成」、
-- 窗口后提交另标「逾期完成」（A-FU-010）——完成方式不是独立状态（状态仍为 COMPLETED，列表与
-- 时间轴展示由它派生「已完成 / 已完成（窗口前完成）/ 已完成（逾期完成）」，components.md §2 task 枚举）。
--   * complete_kind：BEFORE_WINDOW 窗口前完成 / ON_TIME 窗口内完成 / OVERDUE 逾期完成
--     （「停用期间未执行」补做后完成同样落 OVERDUE——窗口已结束，口径一致）；
--   * 补录任务（create_type = BACKFILL，E14-T05）提交后同为 COMPLETED，complete_kind 落提交时点
--     对窗口的相对关系（建任务时窗口已过，通常为 OVERDUE；「已完成（补录）」由 create_type 派生，不占本列）；
--   * completed_at / completed_by：提交时间与提交人（提交流程随 E15-T02 接入本入口时写入）。
-- 逾期（OVERDUE）不另落标记列：进入时间 = window_end 次日（每日刷新，followup.md §2）、事件与审计可追溯。

ALTER TABLE fu_task
  ADD COLUMN complete_kind VARCHAR(16) NULL COMMENT '完成方式（E15-T01 / A-FU-010）：BEFORE_WINDOW 窗口前完成 / ON_TIME 窗口内完成 / OVERDUE 逾期完成（状态仍为 COMPLETED，由本列派生展示）',
  ADD COLUMN completed_at DATETIME(3) NULL COMMENT '完成时间（随访记录提交时点）',
  ADD COLUMN completed_by BIGINT NULL COMMENT '完成操作人 sys_user.id（提交人）';
