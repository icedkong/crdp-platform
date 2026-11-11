-- E23-T16 / A-FU-016、A-FU-017（第 14 章「随访计划（医院 B 预置）」，Q42 / Q43 / Q44）：预置医院 B 计划「心脏术后随访（医院 B）」。
--
-- 产品配置随包发布（与 E23-T02 字段字典 V38、预置计划 V61 同口径，进入所有租户库；不写租户分支，AGENTS §5.7）：
--   * 计划：手术日期起算（SURGERY_DATE，B 与 A 的核心差异）、长期（cycle_type = ONGOING、cycle_years 为 NULL，
--     术后 1 年起每年一次、长期不设结束，Q42）、auto_enroll = 1（出院登记后自动加入预置计划，Q44 与 A 的 Q35 同口径）；
--   * 随包状态为「未发布草稿」（UNPUBLISHED，OQ-128）：自动入组只认 PUBLISHED（V50），草稿对医院 A / 其他租户惰性
--     （不参与自动入组、不可入组、不进已发布计划候选与 node_rate 缺省计划，OQ-109 ③），由医院 B 侧激活——
--     停用 A 的「冠心病术后年度随访」后发布本计划（V61 注释「本院预置与停用处置」的落地），
--     生产操作手册 docs/ops/hospital-b-plan-preset.md，激活路径演练 BPresetPlanVerifyIT（E23-T16）；
--   * 子计划三组（A-FU-016 三层「计划 > 子计划 > 任务」）：
--     - 「术后早期随访」FIXED ×1：三个节点各发生一次——术后第 1 / 3 / 5 天（手术日期 +1/+3/+5 天、当天提醒（0）、
--       窗口当天至次日（前 0 后 1 天，Q42），随访任务 + 完整随访记录表单（Q43，取消简表），出院建档时窗口已结束
--       照常创建并标「补录」（BACKFILL，Q44）；
--     - 「术后 3 个月随访」FIXED ×1：术后 3 个月节点（非年度，随访率口径见 A-DASH-012）——手术日期 +3 个月、
--       提前 2 周提醒、窗口前后 14 天（Q42），电话随访 + 完整随访记录表单；
--     - 「年度随访」CYCLE 1 YEAR（持续循环，直到患者死亡、失访或人工停止）：年度节点「电话随访」——手术日期 +1 年、
--       提前 1 个月提醒、窗口前后 1 个月（Q42「每个年度节点提前 1 个月提醒；1 年及以后前后 1 个月」），
--       电话随访 + 完整随访记录表单、责任人 = 责任医生（与 A 预置同口径）、已过窗口补建（BACKFILL，Q44）；
--   * 提醒提前量 = 窗口前（各节点提醒开始与窗口开始同一天，A-FU-001 同口径；规格 §14 示例：3 个月节点
--     2026-05-18 开启并开始提醒、窗口至 06-15；1 年节点 2027-02-01 开启、窗口至 04-01），任务在提醒开始日创建；
--   * 通知事件规则无需新增：V22 种子即两家医院共用的默认设置，逐院在「通知设置」页调整；
--     口径由 BPresetPlanSqlTest / BPresetPlanVerifyIT 锁定。
-- 变更记录（A-FU-021「计划改动全记审计」，fu_plan_log 只追加）：随包为系统配置草稿，只写新建一条（无发布动作——
--   发布由 B 侧激活时的 PlanService.publish 产生，操作人非空并写审计）；快照 JSON 与 PlanService.PlanSummary 字段同构。
-- 任务开启日（提醒开始日与窗口开始日较早一天，A-FU-001）起按需创建任务由每日调度与入组即建负责（E14-T04 / E14-T05），不预生成。

INSERT INTO fu_plan (code, name, version, status, start_date_field, cycle_type, cycle_years, auto_enroll, remark, published_at)
VALUES ('B_POSTOP_FU_PLAN', '心脏术后随访（医院 B）', 1, 'UNPUBLISHED', 'SURGERY_DATE', 'ONGOING', NULL, 1, '医院 B 预置计划（E23-T16、第 14 章 Q42）：手术日期起算，术后第 1/3/5 天、3 个月、1 年及每年；完整随访记录表单（Q43）、已过窗口补录（Q44）。随包为未发布草稿，B 侧停用 A 预置后发布启用（OQ-128）', NULL);

SET @b_plan_id = LAST_INSERT_ID();

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@b_plan_id, NULL, 'SUBPLAN', '术后早期随访', 'FIXED', 1, NULL, NULL, 'PLAN_START', 0, 'YEAR', 0, 'DAY', 0, 'DAY', 0, 'DAY', 'FORM', 'WARD_DEFAULT', NULL, NULL, NULL, 10);

SET @b_early_id = LAST_INSERT_ID();

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@b_plan_id, @b_early_id, 'NODE', '术后第 1 天', NULL, NULL, NULL, NULL, 'PLAN_START', 1, 'DAY', 0, 'DAY', 0, 'DAY', 1, 'DAY', 'FORM', 'DOCTOR', 'A_ANNUAL_FU', 'BACKFILL', NULL, 10);

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@b_plan_id, @b_early_id, 'NODE', '术后第 3 天', NULL, NULL, NULL, NULL, 'PLAN_START', 3, 'DAY', 0, 'DAY', 0, 'DAY', 1, 'DAY', 'FORM', 'DOCTOR', 'A_ANNUAL_FU', 'BACKFILL', NULL, 20);

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@b_plan_id, @b_early_id, 'NODE', '术后第 5 天', NULL, NULL, NULL, NULL, 'PLAN_START', 5, 'DAY', 0, 'DAY', 0, 'DAY', 1, 'DAY', 'FORM', 'DOCTOR', 'A_ANNUAL_FU', 'BACKFILL', NULL, 30);

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@b_plan_id, NULL, 'SUBPLAN', '术后 3 个月随访', 'FIXED', 1, NULL, NULL, 'PLAN_START', 0, 'YEAR', 0, 'DAY', 0, 'DAY', 0, 'DAY', 'FORM', 'WARD_DEFAULT', NULL, NULL, NULL, 20);

SET @b_3m_id = LAST_INSERT_ID();

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@b_plan_id, @b_3m_id, 'NODE', '术后 3 个月', NULL, NULL, NULL, NULL, 'PLAN_START', 3, 'MONTH', 2, 'WEEK', 14, 'DAY', 14, 'DAY', 'FORM', 'DOCTOR', 'A_ANNUAL_FU', 'BACKFILL', NULL, 10);

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@b_plan_id, NULL, 'SUBPLAN', '年度随访', 'CYCLE', NULL, 1, 'YEAR', 'PLAN_START', 0, 'YEAR', 0, 'DAY', 0, 'DAY', 0, 'DAY', 'FORM', 'WARD_DEFAULT', NULL, NULL, NULL, 30);

SET @b_year_id = LAST_INSERT_ID();

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@b_plan_id, @b_year_id, 'NODE', '电话随访', NULL, NULL, NULL, NULL, 'PLAN_START', 1, 'YEAR', 1, 'MONTH', 1, 'MONTH', 1, 'MONTH', 'FORM', 'DOCTOR', 'A_ANNUAL_FU', 'BACKFILL', NULL, 10);

INSERT INTO fu_plan_log (plan_id, plan_code, action, description, before_json, after_json, operator_id) VALUES
(@b_plan_id, 'B_POSTOP_FU_PLAN', 'PLAN_CREATE', '预置计划（系统配置，E23-T16）：心脏术后随访（医院 B）（手术日期起算、长期；术后第 1/3/5 天、3 个月、1 年及每年，Q42 / Q43 / Q44）——随包为未发布草稿，由医院 B 发布启用', NULL, '{"code":"B_POSTOP_FU_PLAN","name":"心脏术后随访（医院 B）","version":1,"status":"UNPUBLISHED","startDateField":"SURGERY_DATE","cycleType":"ONGOING","cycleYears":null,"autoEnroll":true,"remark":"医院 B 预置计划（E23-T16、第 14 章 Q42）：手术日期起算，术后第 1/3/5 天、3 个月、1 年及每年；完整随访记录表单（Q43）、已过窗口补录（Q44）。随包为未发布草稿，B 侧停用 A 预置后发布启用（OQ-128）"}', NULL);
