-- E14-T06 / A-FU-007、A-FU-008、A-PAT-011、C-FU-021：任务终止原因三列 + 状态列加宽。
--
-- fu_task 终止（A-FU-003「已终止：患者死亡或人工停止」）随本任务落地写入口：
--   * 死亡自动终止（A-FU-007）：随访记录提交「是否死亡=是」或沟通结果「告知已故」后，
--     患者全部未开始 / 未完成任务置 TERMINATED，terminate_reason = 死亡；
--   * 移出计划（A-FU-008 / A-PAT-011）：单个患者从计划移除须填原因（拒绝随访、外地随访、其他），
--     该入组未完成任务置 TERMINATED，terminate_reason = 移除原因。
-- 终止原因按 A 规格「关键字字段 · 随访任务」为字典（死亡 / 拒绝随访 / 外地随访 / 其他），
-- 本任务存操作时原因文本（与 fu_enrollment.remove_reason 同口径），字典化随 E15 展示层。
--
-- 状态列加宽：V48 定义的枚举值 SKIPPED_SUSPENDED（停用 / 暂停期间未执行，PLT-TEN-017、A 的 Q32；
-- create_type = PAUSED_SKIPPED / DISABLED_SKIPPED 区分来源）为 17 字符，超出 status VARCHAR(16)——
-- E14-T04 只写 PENDING 未触发，本任务起补偿任务落此状态，加宽到 VARCHAR(24)（枚举全集最长值留余量）。

ALTER TABLE fu_task
  ADD COLUMN terminate_reason VARCHAR(255) NULL COMMENT '终止原因（终止时必填：死亡 / 拒绝随访 / 外地随访 / 其他；A-FU-003 任务关键字段，存操作时原因文本）',
  ADD COLUMN terminated_at DATETIME(3) NULL COMMENT '终止时间',
  ADD COLUMN terminated_by BIGINT NULL COMMENT '终止操作人 sys_user.id（调度 / 事件自动终止为 NULL）';

ALTER TABLE fu_task
  MODIFY COLUMN status VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING 待随访（初始，A-FU-003）/ IN_PROGRESS 进行中 / COMPLETED 已完成 / OVERDUE 逾期 / LOST 失访 / TERMINATED 已终止 / SKIPPED_SUSPENDED 暂停或停用期间未执行（E14-T06 起写入，补做或关闭的流转随 E15-T01）';
