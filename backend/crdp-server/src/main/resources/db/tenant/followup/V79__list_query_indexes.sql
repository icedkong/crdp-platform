-- G8（M3 审查）/ E16-T05、E15-T02 列表查询性能：全院报告单列表按上传时间倒序、
-- 任务列表按计划日期排序，两列此前都没有索引（报告单现有索引以患者打头，帮不上
-- 全院视角的排序；fu_task 只有状态 / 窗口 / 负责人 / 开启日索引）。
--   idx_rpt_report_created_at  全院报告单检索（ReportSearchMapper ORDER BY r.created_at DESC, r.id DESC）
--   idx_fu_task_plan_date      任务列表 / 看板边界查询（FollowupTaskQueryMapper ORDER BY t.plan_date）
-- 只新增索引，不改表结构与既有迁移。

ALTER TABLE rpt_report ADD KEY idx_rpt_report_created_at (created_at);

ALTER TABLE fu_task ADD KEY idx_fu_task_plan_date (plan_date);
