-- E23-T02 / A-FU-017（A-FU-001/002/016、A-PAT-003 Q35、A-NTF-001）：预置医院 A 计划「冠心病术后年度随访」。
--
-- 产品配置随包发布（与 E23-T01 字段字典 V38 同口径，进入所有租户库；医院 B / C 的差异随 E23-T16 / E23-T20
-- 以本院预置与停用处置，不写租户分支，AGENTS §5.7）：
--   * 计划：出院日期起算、长期（cycle_type = ONGOING、cycle_years 为 NULL，不设结束年度，Q21）、
--     已发布、auto_enroll = 1（出院登记后自动加入预置计划，出院状态死亡不加入，Q35 / A-PAT-003）；
--   * 子计划「年度随访」：持续循环（CYCLE）每年一次——不设固定次数，直到患者死亡、失访或人工停止（A-FU-016）；
--   * 节点「电话随访」：计划起算日期 +1 年、提前 7 天提醒、窗口前后各 7 天（可对称）、电话随访 + 表单任务
--     （表单 A_ANNUAL_FU，随 V38 字段字典发布）、责任人 = 责任医生（A 规格计划结构图）、
--     入组前已过窗口补建补录任务（BACKFILL，Q44）。提醒提前量 = 窗口前（都为 7 天）→ 提醒开始日与窗口开始日
--     同为计划日期前 7 天（A-FU-001），通知侧按 A-NTF-001 合并为一条（窗口开启不另发）。
--   * 通知事件规则无需新增：V22 的 ntf_rule 种子即 A 规格 §7「关键字字段 · 通知事件默认设置」
--     （窗口预告 7 天 / 即将逾期 2 天 / 已逾期站内信 + 短信可抄送病区负责人 / 每日汇总短信 + 站内信），
--     逐院可在「通知设置」页调整；口径由 APresetPlanSqlTest / APresetPlanVerifyIT 锁定。
-- 变更记录（A-FU-021「计划改动全记审计」，fu_plan_log 只追加）：预置为系统配置，写新建与发布两条，
--   操作人为空（迁移无操作会话）；快照 JSON 与 PlanService.PlanSummary 字段同构。
-- 任务开启日（提醒开始日与窗口开始日较早一天，A-FU-001）起按需创建任务由每日调度负责（E14-T04），不预生成。

INSERT INTO fu_plan (code, name, version, status, start_date_field, cycle_type, cycle_years, auto_enroll, remark, published_at)
VALUES ('A_ANNUAL_FU_PLAN', '冠心病术后年度随访', 1, 'PUBLISHED', 'DISCHARGE_DATE', 'ONGOING', NULL, 1, '医院 A 预置计划（A-FU-017）：出院日期起算、每年一次、提前 7 天提醒、窗口前后 7 天、长期不设结束年度；出院登记后自动入组（Q35）', NOW(3));

SET @a_plan_id = LAST_INSERT_ID();

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@a_plan_id, NULL, 'SUBPLAN', '年度随访', 'CYCLE', NULL, 1, 'YEAR', 'PLAN_START', 0, 'YEAR', 0, 'DAY', 0, 'DAY', 0, 'DAY', 'FORM', 'WARD_DEFAULT', NULL, NULL, NULL, 10);

SET @a_subplan_id = LAST_INSERT_ID();

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@a_plan_id, @a_subplan_id, 'NODE', '电话随访', NULL, NULL, NULL, NULL, 'PLAN_START', 1, 'YEAR', 7, 'DAY', 7, 'DAY', 7, 'DAY', 'FORM', 'DOCTOR', 'A_ANNUAL_FU', 'BACKFILL', NULL, 10);

INSERT INTO fu_plan_log (plan_id, plan_code, action, description, before_json, after_json, operator_id) VALUES
(@a_plan_id, 'A_ANNUAL_FU_PLAN', 'PLAN_CREATE', '预置计划（系统配置，E23-T02）：冠心病术后年度随访（出院日期起算、长期不设结束年度，子计划持续循环每年一次）', NULL, '{"code":"A_ANNUAL_FU_PLAN","name":"冠心病术后年度随访","version":1,"status":"UNPUBLISHED","startDateField":"DISCHARGE_DATE","cycleType":"ONGOING","cycleYears":null,"autoEnroll":true,"remark":"医院 A 预置计划（A-FU-017）：出院日期起算、每年一次、提前 7 天提醒、窗口前后 7 天、长期不设结束年度；出院登记后自动入组（Q35）"}', NULL),
(@a_plan_id, 'A_ANNUAL_FU_PLAN', 'PLAN_PUBLISH', '预置计划发布（系统配置，E23-T02）：冠心病术后年度随访 v1（节点：出院日期 +1 年、提前 7 天提醒、窗口前后 7 天、电话随访 + 随访记录表单 A_ANNUAL_FU、责任医生、已过窗口补建补录）', '{"code":"A_ANNUAL_FU_PLAN","name":"冠心病术后年度随访","version":1,"status":"UNPUBLISHED","startDateField":"DISCHARGE_DATE","cycleType":"ONGOING","cycleYears":null,"autoEnroll":true,"remark":"医院 A 预置计划（A-FU-017）：出院日期起算、每年一次、提前 7 天提醒、窗口前后 7 天、长期不设结束年度；出院登记后自动入组（Q35）"}', '{"code":"A_ANNUAL_FU_PLAN","name":"冠心病术后年度随访","version":1,"status":"PUBLISHED","startDateField":"DISCHARGE_DATE","cycleType":"ONGOING","cycleYears":null,"autoEnroll":true,"remark":"医院 A 预置计划（A-FU-017）：出院日期起算、每年一次、提前 7 天提醒、窗口前后 7 天、长期不设结束年度；出院登记后自动入组（Q35）"}', NULL);
