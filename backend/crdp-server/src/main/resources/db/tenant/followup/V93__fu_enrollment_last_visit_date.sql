-- E21-T07 / C-FU-006（接近 ERI 加密随访循环 1–3 个月）：fu_enrollment 增加「上次实际随访日期」快照列
--   last_visit_date，滚动循环节点（fu_plan_node.basis = LAST_VISIT，V91 常规随访 / ERI 期随访）的排期基准。
--
-- 与 next_plan_date / next_open_date（V46 预留）同属调度缓存，不是新的事实源：
--   * 写入：随访记录提交 / 修改已提交记录时单调推进（只增不减——PlanSwitchService.onRecordSubmitted，
--     有效随访日期 = fu_record.visit_date ?? 表单 followup_date 字段，与 eri_since 同口径）；删除 / 改早最新
--     一次记录不回退快照（下次提交自愈，OQ-140②）；
--   * 消费：每日任务创建作业（FollowupTaskCreationService → TaskCreationPlanner LoopBasis）——第 N 次
--     计划日期 = max（last_visit_date，该节点已排最新一次计划日期）+ 节点偏移（C 规格 §5 节点表
--     「上次实际随访日期 + 1 / 6 / 12 个月」）；无任何随访与已排次数时首次回落起算日期、且与固定节点
--     计划日重合时抑制（OQ-139② 首次抑制）。
-- A / B 计划无 LAST_VISIT 节点（V38 / V61 / V80），本列对既有租户惰性（写了不消费）；无存量回填——
--   C 上线前（E23-T23）无滚动入组，V91 两套计划仍为未发布草稿。
ALTER TABLE fu_enrollment
  ADD COLUMN last_visit_date DATE NULL
    COMMENT '上次实际随访日期快照（E21-T07 滚动循环节点排期基准：记录提交时单调推进，只增不减；A / B 无 LAST_VISIT 节点不消费）'
    AFTER doctor_next_date;
