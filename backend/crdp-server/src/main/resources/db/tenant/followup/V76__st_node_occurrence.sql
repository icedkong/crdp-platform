-- E19-T01 / A-DASH-012（tech-plan §13「写入时维护的统计结构」）：节点随访事实表。
--
-- 每个入组的每个节点的第 N 次一行（uk 与 fu_task / fu_record 锚点同粒度：入组 + 节点 + 第 N 次），
-- 由每日 00:10 调度（任务创建与状态刷新之后，tech-plan §8 步骤 W「统计事实表」）整租户重建：
--   * 应随访（due）= 窗口已结束（window_end 早于统计日）且条件判定基准日（窗口开始日，入组更晚取入组日）
--     按状态时间线回看仍符合条件（存活 / 暂停——暂停仍计入，Q38）；due_reason 记 DUE / WINDOW_OPEN（窗口
--     未结束，行保留待窗口结束后转应随访）/ NOT_QUALIFIED（基准日已死亡 / 已终止 / 失访）；
--   * 已随访（followed）= 该次随访记录已提交（窗口内 / 逾期 / 补录 / 历史导入 / 窗口外日期都算，
--     随访中确认死亡并提交也算，Q38）；complete_kind 落完成方式（ON_TIME 窗口内 / OVERDUE 逾期 /
--     BEFORE_WINDOW 窗口前 / BACKFILL 补录 / HIST_IMPORT 历史导入）；in_window 供「窗口内完成率（参考）」；
--   * lost = 该次任务确认失访；death_confirmed = 死亡生效日在该次窗口内（Q38「第 N 年随访时确认已死亡」；
--     死亡日期修正由次日刷新重算，暂行口径见 OQ-106）；
--   * followup_year 只在年度循环节点（CYCLE + YEAR）填 = 第 N 次（与 E18 导入锚点同口径，V73）；
--     cohort_year = 起算日期年份（队列筛选：A 出院年份、B 手术年份，A-DASH-012）；
--   * 入组前已过窗口：有任务（A、B 补录，Q44）或锚点记录（E18）才出行；医院 C 的 SKIP 无任务无记录
--     不出行、不计应随访（Q14）——由计算侧（NodeOccurrenceFactCalculator）保证。
-- 表登记在 ControlledTables（E07-T01 名单已含）：查询侧（E19-T04 节点随访率卡片）按 @DataScope(resource =
-- patient) 出行，刷新写入侧按 @IgnoreScope 放行（无用户上下文）；本表为派生只读数据，每日重建、不可人工编辑。

CREATE TABLE st_node_occurrence (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id（数据范围 EXISTS 锚点，ControlledTables）',
  enrollment_id BIGINT NOT NULL COMMENT '入组 fu_enrollment.id（uk 成分，与 fu_task 同粒度）',
  plan_id BIGINT NOT NULL COMMENT '计划版本快照 fu_plan.id（随入组固定，A-FU-019）',
  plan_code VARCHAR(64) NOT NULL COMMENT '计划族编码快照 fu_plan.code（按计划族筛选）',
  plan_version INT NOT NULL COMMENT '计划版本号快照 fu_plan.version',
  node_id BIGINT NOT NULL COMMENT '节点 fu_plan_node.id',
  occurrence INT NOT NULL COMMENT '子计划内第 N 次（从 1 起；uk 成分）',
  followup_year INT NULL COMMENT '随访年度 N（年度循环节点 = 第 N 次；非年度节点为 NULL，E18-T05 同口径）',
  plan_date DATE NOT NULL COMMENT '计划日期（基准 + 偏移 + (N−1)×周期）',
  window_start DATE NOT NULL COMMENT '窗口开始日（条件判定基准日成分）',
  window_end DATE NOT NULL COMMENT '窗口结束日（早于统计日即「窗口已结束」）',
  cohort_year INT NULL COMMENT '队列年份 = 起算日期年份（A 出院 / B 手术；A-DASH-012 队列筛选）',
  due TINYINT NOT NULL DEFAULT 0 COMMENT '是否应随访（窗口已结束且基准日仍符合条件，Q38）',
  due_reason VARCHAR(16) NOT NULL COMMENT 'DUE 应随访 / WINDOW_OPEN 窗口未结束 / NOT_QUALIFIED 基准日不符合条件',
  task_id BIGINT NULL COMMENT '关联任务 fu_task.id（计划推算行可能无任务）',
  record_id BIGINT NULL COMMENT '关联随访记录 fu_record.id（已随访时指向提交的记录）',
  followed TINYINT NOT NULL DEFAULT 0 COMMENT '是否已随访（该次记录已提交，含逾期 / 补录 / 历史导入）',
  complete_kind VARCHAR(16) NULL COMMENT '完成方式：ON_TIME 窗口内 / OVERDUE 逾期 / BEFORE_WINDOW 窗口前 / BACKFILL 补录 / HIST_IMPORT 历史导入',
  in_window TINYINT NOT NULL DEFAULT 0 COMMENT '实际随访在窗口内（含首尾；「窗口内完成率」参考口径）',
  lost TINYINT NOT NULL DEFAULT 0 COMMENT '该次任务确认失访（失访人数）',
  death_confirmed TINYINT NOT NULL DEFAULT 0 COMMENT '随访时确认死亡（死亡生效日在该次窗口内，Q38）',
  refreshed_date DATE NOT NULL COMMENT '刷新统计日（每日重建整表，asOf 逻辑日）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_st_node_occurrence (enrollment_id, node_id, occurrence),
  KEY idx_st_node_patient (patient_id),
  KEY idx_st_node_plan_node (plan_id, node_id, occurrence),
  KEY idx_st_node_due (due, plan_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='节点随访事实表（每个入组每个节点第 N 次一行；每日重建，A-DASH-012 / tech-plan §13；E19-T01）';
