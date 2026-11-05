-- E15-T05 / A-FU-012 失访判定、A-FU-024 未联系到自动跟进。
--
-- 失访候选（A-FU-012「窗口内不同日期连续 3 次『未联系到 / 号码错误』且窗口已结束」）：新增状态
-- LOST_CANDIDATE——每日 00:10 调度在逾期刷新后判定（tech-plan §8 步骤 W、§9「每日状态刷新时判定，
-- 标失访候选，医生确认后任务为失访」），医生确认 → LOST（POST /tasks/{id}/confirm-lost）。
-- 候选仍是待办（进「我的待办」/ 可改派 / 可在直接录入期内完成），不是终态；
-- 「拒访」不计入连续（改走移出计划）；次数可配置（平台参数 followup.lost.candidate.attempts，
-- 平台库 V46，默认 3）。
--
-- 未联系到自动跟进（A-FU-024「次日自动生成一项跟进任务（间隔天数可配置），每个窗口最多 5 次，
-- 窗口结束后不再生成」；tech-plan §9「写沟通记录时创建，计数存任务上」）：
--   * followup_seq = 窗口内跟进序号：0 = 常规任务（NORMAL / BACKFILL / 期间未执行），
--     1–5 = 该窗口（入组 + 节点 + 第 N 次）的自动跟进任务（create_type = FOLLOWUP）；
--     「每窗口最多 5 次」= 取现有 MAX(followup_seq)，达到 5 不再创建（固定值，医院已确认）；
--   * uk 扩一列（V48 原三列对新值仍唯一：常规任务恒 seq=0），并发重复创建仍被唯一键兜底；
--   * 跟进任务继承源任务的窗口列与负责人，plan_date = remind_start = open_date = 沟通业务日 +
--     间隔天数（平台参数 followup.unreached.followup.interval.days，默认 1），开启日晚于
--     window_end（窗口已结束）不创建；创建于写沟通记录的同一事务（含 UNREACHED_FOLLOWUP 事件）。
-- 起算日期重算（E14-T07）只重排常规任务：findUnstartedTasks 过滤 create_type IN (NORMAL, BACKFILL)。

ALTER TABLE fu_task
  ADD COLUMN followup_seq INT NOT NULL DEFAULT 0 COMMENT '窗口内跟进序号：0 = 常规任务；1–5 = 未联系到自动跟进任务（A-FU-024，每窗口最多 5 次，窗口结束不再生成）';

ALTER TABLE fu_task
  DROP KEY uk_fu_task_occurrence,
  ADD UNIQUE KEY uk_fu_task_occurrence (enrollment_id, node_id, occurrence, followup_seq);

ALTER TABLE fu_task
  MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待随访（初始，A-FU-003）/ IN_PROGRESS 进行中 / COMPLETED 已完成 / OVERDUE 逾期 / LOST_CANDIDATE 失访候选（A-FU-012，E15-T05：窗口结束且连续未联系到，医生确认后 → LOST）/ LOST 失访 / TERMINATED 已终止 / SKIPPED_SUSPENDED 暂停或停用期间未执行';
