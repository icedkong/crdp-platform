-- E14-T04 / A-FU-001、C-FU-004、A-NFR-006、C-NFR-007：任务实例表（每日 00:10 调度按需创建）。
--
-- fu_task 一行 = 某入组在某节点第 N 次的一次随访任务（tech-plan §7「模型」表、§8 每日调度流程图）：
--   * 按需生成（A-FU-001、C-FU-004）：入组不预生成任务，任务在「任务开启日」= 提醒开始日与窗口开始日中
--     较早的一天（E14-T03 PlanDateCalculator.openDate）由每日调度创建；未来节点只在时间轴上以计算值
--     「计划中」展示，不落库（A-FU-003「待开始」不对应实际任务）。
--   * 幂等：uk（入组 + 节点 + 第 N 次），调度重复执行 / 补跑重放不会重复创建（tech-plan §8）。
--   * 版本跟随入组（A-FU-019）：plan_id / node_id 快照自 fu_enrollment.plan_id 固定的版本，患者不随计划升版迁移。
--   * 创建方式（tech-plan）：NORMAL 正常 / BACKFILL 补录（E14-T05 建档补录）/ PAUSED_SKIPPED 暂停期间未执行、
--     DISABLED_SKIPPED 停用期间未执行（E14-T06 补偿）/ FOLLOWUP 跟进、TEMP 临时（A-FU-013 随 E15）。
--     本任务（E14-T04）只写 NORMAL，其余值先占位。
--   * 状态（A-FU-003）：创建即 PENDING 待随访；进行中 / 已完成 / 逾期 / 失访 / 已终止 / 停用期间未执行的
--     流转随 E15-T01，本任务只落枚举与初始态。
--   * 负责人（A-FU-009）：owner_type 按节点模板；assignee_id 创建时解析——责任医生缺失依次回落病区默认
--     负责人（ward.default_owner_id，OQ-60），仍无则为 NULL 进病区待认领池。
-- fu_task 登记在 ControlledTables（E07-T01 起名单已含），查询侧（E15-T01 列表 / 看板）按 @DataScope 出行，
-- 调度写入侧按 @IgnoreScope 放行（无用户上下文）。created_logical_date 记创建时的逻辑执行日：正常 =
-- 任务开启日，停机补跑时晚于开启日（补跑可追溯）。

CREATE TABLE fu_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  enrollment_id BIGINT NOT NULL COMMENT '入组 fu_enrollment.id（版本固定的来源）',
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id（数据范围 EXISTS 锚点，冗余自入组）',
  plan_id BIGINT NOT NULL COMMENT '计划版本快照 fu_plan.id（随入组固定，A-FU-019）',
  node_id BIGINT NOT NULL COMMENT '节点 fu_plan_node.id（任务定义：偏移 / 提醒 / 窗口 / 表单等快照来源）',
  occurrence INT NOT NULL COMMENT '子计划内第 N 次（从 1 起；幂等键成分，A-FU-001「N 逐年递增」）',
  task_type VARCHAR(16) NOT NULL DEFAULT 'FORM' COMMENT '任务类型：FORM 电话随访 + 表单 / REMINDER 站内提醒（A-FU-026）',
  form_code VARCHAR(64) NULL COMMENT 'FORM 任务使用的随访记录表单 code（REMINDER 为 NULL）',
  owner_type VARCHAR(16) NOT NULL DEFAULT 'WARD_DEFAULT' COMMENT '责任人类型快照：DOCTOR 责任医生 / WARD_DEFAULT 病区默认负责人（A-FU-009）',
  assignee_id BIGINT NULL COMMENT '负责人 sys_user.id（DOCTOR 缺失回落病区默认负责人，仍无为 NULL 进待认领池，A-FU-009）',
  plan_date DATE NOT NULL COMMENT '计划日期（基准 + 偏移 + (N−1)×周期，E14-T03）',
  remind_start DATE NOT NULL COMMENT '提醒开始日（计划日期 − 提醒提前量）',
  window_start DATE NOT NULL COMMENT '窗口开始日（计划日期 − 窗口前）',
  window_end DATE NOT NULL COMMENT '窗口结束日（计划日期 + 窗口后）',
  open_date DATE NOT NULL COMMENT '任务开启日 = 提醒开始日与窗口开始日中较早的一天（A-FU-001、C-FU-004）',
  create_type VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '创建方式：NORMAL 正常 / BACKFILL 补录 / PAUSED_SKIPPED 暂停期间未执行 / DISABLED_SKIPPED 停用期间未执行 / FOLLOWUP 跟进 / TEMP 临时',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待随访（初始，A-FU-003）/ IN_PROGRESS 进行中 / COMPLETED 已完成 / OVERDUE 逾期 / LOST 失访 / TERMINATED 已终止 / SKIPPED_SUSPENDED 停用期间未执行（流转随 E15-T01）',
  created_logical_date DATE NOT NULL COMMENT '创建时的逻辑执行日（正常 = 任务开启日；停机补跑时晚于开启日，可追溯）',
  lock_version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本（改派 / 状态流转等编辑随 E15）',
  created_by BIGINT NULL COMMENT '创建人 sys_user.id（调度创建为 NULL；临时任务随 E15 记操作人）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_fu_task_occurrence (enrollment_id, node_id, occurrence),
  KEY idx_fu_task_patient (patient_id),
  KEY idx_fu_task_status_window (status, window_end),
  KEY idx_fu_task_assignee (assignee_id),
  KEY idx_fu_task_open_date (open_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='随访任务实例（按需创建，A-FU-001/003、C-FU-004；E14-T04）';
