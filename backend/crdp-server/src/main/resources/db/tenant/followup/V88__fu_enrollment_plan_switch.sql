-- E21-T06 / C-FU-007（Q01、Q03）：计划切换规则与触发事件（升级、更换、更正、取出、ERI）。
-- 植入记录新增或变更（首次植入、升级、电池更换、器械类型更正、取出）以及随访中 ERI 标记变化时，
-- 按 C 规格第 5 章「计划切换触发事件」表自动切换计划或循环（docs/reviews/hospital-c-acceptance-review.md
-- §3.3.1 SW-1…SW-10 逐行用例）：
--   切换的统一落法 = 原入组结束（新状态 SWITCHED，switch_reason 记原因）+ 未完成任务自动终止并记录原因
--   + 按新器械类型映射重新入组（起算快照 = 新植入日期；器械类型更正仍按原植入日期，SW-6「否，仍按原植入日期」）；
--   ERI 标记（切换表「接近 ERI / ERI 标记解除」行）不换入组行，只翻 fu_enrollment.mode（NORMAL ↔ ERI），
--   eri_since 记进入 ERI 期的那次随访日期（切换表「否，从该次随访日期起算」；循环任务创建随 E21-T07）；
--   医生指定下次随访时间（切换表末行）不改变计划类型，doctor_next_date 只登记下一次任务的计划日期
--   （窗口围绕展开与任务创建随 E21-T08 / C-FU-005）。
-- SWITCHED 与 REMOVED 同为终态：生成列 active_key 对非 ACTIVE/PAUSED 恒 NULL，结束即释放
-- uk_fu_enrollment_active（同族可重新入组），调度（findSchedulableEnrollments 只取 ACTIVE）不再创建任务。

ALTER TABLE fu_enrollment
  MODIFY COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
    COMMENT '状态：ACTIVE 已启动 / PAUSED 已暂停 / REMOVED 已移除（A-FU-022）/ SWITCHED 已切换（C-FU-007 计划切换结束，原因见 switch_reason）',
  ADD COLUMN switch_reason VARCHAR(255) NULL
    COMMENT '切换原因（C-FU-007：升级 / 更换 / 更正 / 取出 / ERI 切换结束时原入组结束的原因，SWITCHED 行必填）'
    AFTER remove_reason,
  ADD COLUMN eri_since DATE NULL
    COMMENT 'ERI 期起算日（进入 ERI 模式的那次随访日期，C-FU-006 / 切换表「从该次随访日期起算」；恢复正常时清空）'
    AFTER mode,
  ADD COLUMN doctor_next_date DATE NULL
    COMMENT '医生指定下次随访日期（C-FU-005：只影响下一次任务的计划日期，窗口围绕展开随 E21-T08；每次提交覆盖）'
    AFTER next_open_date;
