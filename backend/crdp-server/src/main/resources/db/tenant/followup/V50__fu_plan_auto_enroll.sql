-- E14-T05 / A-PAT-003、A-FU-016（Q35 自动入组）：fu_plan 增加自动入组开关。
--
-- 口径（A 规格第 13 章 Q35、tech-plan §8「其他规则」）：
--   * 出院登记后自动加入预置计划；出院状态为死亡（pt_patient.discharge_status = 3）不加入；
--   * 「预置计划」是计划参数（tech-plan §8「入组前已过窗口」同为计划参数、不写死）：auto_enroll = 1
--     且状态 PUBLISHED 的计划参与自动入组；PAUSED（不开启新任务）/ DISABLED（不再允许入组）不参与；
--   * 与版本锁定（A-FU-019）正交：新版本发布时旧版本转 DISABLED → 自动入组必然落在新版本；
--     复制新版本时沿用源版本的开关，发布前可改（仅未发布可编辑）。
-- 入组前已过窗口的补建策略在节点列 fu_plan_node.passed_window_action（V46 已建：BACKFILL / SKIP），
-- 本任务（E14-T05）起入组当天按该策略创建 create_type = BACKFILL 的补录任务（fu_task.create_type，V48 已建）。

ALTER TABLE fu_plan
  ADD COLUMN auto_enroll TINYINT(1) NOT NULL DEFAULT 0 COMMENT '自动入组开关（Q35）：出院登记后自动加入该已发布计划，出院状态为死亡不加入；0 关 / 1 开（E14-T05）' AFTER cycle_years;
