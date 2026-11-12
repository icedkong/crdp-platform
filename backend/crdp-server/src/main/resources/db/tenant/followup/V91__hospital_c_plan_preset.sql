-- E23-T20 / C-FU-002（Q03 节点表；C-FU-003 / fu_plan_mapping V86 预置映射的消费端）：预置医院 C 两套计划
--   「普通起搏器术后随访」（C_PACER_FU_PLAN：单腔、双腔、CRT-P、希浦系统、无导线）与
--   「ICD/CRT-D 术后随访」（C_ICD_FU_PLAN）。
--
-- 产品配置随包发布（与 V38 / V61 / V80 同口径，进入所有租户库；不写租户分支，AGENTS §5.7）：
--   * 计划：植入日期起算（IMPLANT_DATE，解析患者「在用」植入记录的植入日期，E21-T05）、长期（ONGOING
--     不设结束年度）、auto_enroll = 1（保存植入记录后按 fu_plan_mapping 器械类型映射自动入组，起算快照 =
--     植入日期）；**随包状态为未发布草稿（UNPUBLISHED，OQ-128②：C 预置同型复制 B 口径）**——草稿对医院
--     A / B / 其他租户惰性（不参与自动入组、不可入组、不进已发布计划候选与 node_rate 缺省计划），由医院
--     C 侧发布启用（映射行 V86 在族无已发布版本时同样惰性跳过，OQ-135④），生产操作手册
--     docs/ops/hospital-c-plan-preset.md；
--   * 子计划三组（计划 > 子计划 > 任务，A-FU-016 三层结构）：
--     - 「术后随访」FIXED ×1：五个节点各发生一次——术后72小时/出院前（植入日期 +3 天；窗口 = 植入日至
--       植入后 7 天，即前 3 后 4，V46 建表注释即此例）、术后1个月（+1 个月、前后 7 天）、术后3个月（+3 个月、
--       前后 14 天）、术后6个月（+6 个月、前后 14 天）、术后1年（+12 个月、前后 30 天，医院答复未单列此
--       节点、保留原默认（假设），作为常规随访的第一次）；
--     - 「常规随访」CYCLE 持续循环（普通 1 YEAR / ICD 6 MONTH，可设 6–12 / 3–6 个月由租户管理员在新版本
--       调整，A-FU-018 版本锁定）：节点基准 LAST_VISIT（上次实际随访日期滚动，V46「C 常规随访滚动」）、
--       偏移 12 / 6 个月、窗口前后 30 / 14 天、enroll_mode = NORMAL（ERI 模式下不生效）；
--     - 「ERI期随访」CYCLE 1 MONTH：节点基准 LAST_VISIT、偏移 1 个月（可设 1–3 个月）、窗口前后 7 天、
--       enroll_mode = ERI（只在 ERI 模式生效，标记接近 ERI 后由 PlanSwitchService 翻 mode / eri_since，
--       E21-T06；循环任务排期从 eri_since 起算随 E21-T07，OQ-138④）；
--   * 入组前已过窗口策略 = SKIP（Q14 / C-PAT-008：历史患者建档只为今天之后开始的窗口创建任务、之前的
--     节点不算逾期；E14-T05 机制承接，与 A / B 的 BACKFILL 相反）；
--   * 提醒提前量 = 窗口前（提醒开始日与窗口开始日同一天，A-FU-001 同口径）；
--   * 表单任务（FORM）+ 随访程控记录表单 C_PROGRAM_FU（V90 字段字典随包）+ 责任人 = 责任医生；
--   * 通知事件规则无需新增：V22 种子为三家医院共用默认，逐院在「通知设置」页调整（医院 C 无异常通知，Q02）。
-- 已知衔接（OQ-139，随 E21-T07 循环排期落地）：常规随访循环节点第 1 次回落起算日（植入日期 + 间隔）与
--   「术后1年 / 术后6个月」固定节点计划日重合——滚动排期引擎落地时须定义首次抑制（有已完成随访后从该次
--   起滚动，否则跳过与固定节点重合的首次）；计划为未发布草稿，C 上线（E23-T23）前 T07 已落地。
-- 变更记录（A-FU-021 / C-FU-001「计划改动全记审计」，fu_plan_log 只追加）：随包为系统配置草稿，只写新建
--   一条（发布条目由 C 侧激活时的 PlanService.publish 产生，操作人非空并写审计）；快照 JSON 与
--   PlanService.PlanSummary 字段同构。
-- 任务开启日（提醒开始日与窗口开始日较早一天，A-FU-001）起按需创建任务由每日调度与入组即建负责
--   （E14-T04 / E14-T05），不预生成。

-- ---------------------------------------------------------------- ① 普通起搏器术后随访（单腔、双腔、CRT-P、希浦系统、无导线）

INSERT INTO fu_plan (code, name, version, status, start_date_field, cycle_type, cycle_years, auto_enroll, remark, published_at)
VALUES ('C_PACER_FU_PLAN', '普通起搏器术后随访', 1, 'UNPUBLISHED', 'IMPLANT_DATE', 'ONGOING', NULL, 1, '医院 C 预置计划（C-FU-002 / Q03）：植入日期起算；术后72小时、1个月、3个月、6个月、1年，其后常规随访每年一次（上次随访日期滚动，前后 30 天）；ERI 期每 1 个月；入组前已过窗口跳过（Q14）。随包为未发布草稿，C 侧发布启用（OQ-128②）', NULL);

SET @c_pacer_plan_id = LAST_INSERT_ID();

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_pacer_plan_id, NULL, 'SUBPLAN', '术后随访', 'FIXED', 1, NULL, NULL, 'PLAN_START', 0, 'YEAR', 0, 'DAY', 0, 'DAY', 0, 'DAY', 'FORM', 'WARD_DEFAULT', NULL, NULL, NULL, 10);

SET @c_pacer_postop_id = LAST_INSERT_ID();

-- 术后72小时/出院前：计划日期 = 植入 +3 天；窗口 = 植入日至植入后 7 天（前 3 后 4）
INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_pacer_plan_id, @c_pacer_postop_id, 'NODE', '术后72小时/出院前', NULL, NULL, NULL, NULL, 'PLAN_START', 3, 'DAY', 3, 'DAY', 3, 'DAY', 4, 'DAY', 'FORM', 'DOCTOR', 'C_PROGRAM_FU', 'SKIP', NULL, 10);

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_pacer_plan_id, @c_pacer_postop_id, 'NODE', '术后1个月', NULL, NULL, NULL, NULL, 'PLAN_START', 1, 'MONTH', 7, 'DAY', 7, 'DAY', 7, 'DAY', 'FORM', 'DOCTOR', 'C_PROGRAM_FU', 'SKIP', NULL, 20);

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_pacer_plan_id, @c_pacer_postop_id, 'NODE', '术后3个月', NULL, NULL, NULL, NULL, 'PLAN_START', 3, 'MONTH', 14, 'DAY', 14, 'DAY', 14, 'DAY', 'FORM', 'DOCTOR', 'C_PROGRAM_FU', 'SKIP', NULL, 30);

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_pacer_plan_id, @c_pacer_postop_id, 'NODE', '术后6个月', NULL, NULL, NULL, NULL, 'PLAN_START', 6, 'MONTH', 14, 'DAY', 14, 'DAY', 14, 'DAY', 'FORM', 'DOCTOR', 'C_PROGRAM_FU', 'SKIP', NULL, 40);

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_pacer_plan_id, @c_pacer_postop_id, 'NODE', '术后1年', NULL, NULL, NULL, NULL, 'PLAN_START', 12, 'MONTH', 30, 'DAY', 30, 'DAY', 30, 'DAY', 'FORM', 'DOCTOR', 'C_PROGRAM_FU', 'SKIP', NULL, 50);

-- 常规随访：上次实际随访日期 + 12 个月（可设 6–12），窗口前后 30 天，仅常规模式
INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_pacer_plan_id, NULL, 'SUBPLAN', '常规随访', 'CYCLE', NULL, 1, 'YEAR', 'PLAN_START', 0, 'YEAR', 0, 'DAY', 0, 'DAY', 0, 'DAY', 'FORM', 'WARD_DEFAULT', NULL, NULL, NULL, 20);

SET @c_pacer_regular_id = LAST_INSERT_ID();

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_pacer_plan_id, @c_pacer_regular_id, 'NODE', '常规随访', NULL, NULL, NULL, NULL, 'LAST_VISIT', 12, 'MONTH', 30, 'DAY', 30, 'DAY', 30, 'DAY', 'FORM', 'DOCTOR', 'C_PROGRAM_FU', 'SKIP', 'NORMAL', 10);

-- ERI期随访：上次实际随访日期 + 1 个月（可设 1–3），窗口前后 7 天，仅 ERI 模式
INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_pacer_plan_id, NULL, 'SUBPLAN', 'ERI期随访', 'CYCLE', NULL, 1, 'MONTH', 'PLAN_START', 0, 'YEAR', 0, 'DAY', 0, 'DAY', 0, 'DAY', 'FORM', 'WARD_DEFAULT', NULL, NULL, NULL, 30);

SET @c_pacer_eri_id = LAST_INSERT_ID();

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_pacer_plan_id, @c_pacer_eri_id, 'NODE', 'ERI期随访', NULL, NULL, NULL, NULL, 'LAST_VISIT', 1, 'MONTH', 7, 'DAY', 7, 'DAY', 7, 'DAY', 'FORM', 'DOCTOR', 'C_PROGRAM_FU', 'SKIP', 'ERI', 10);

INSERT INTO fu_plan_log (plan_id, plan_code, action, description, before_json, after_json, operator_id) VALUES
(@c_pacer_plan_id, 'C_PACER_FU_PLAN', 'PLAN_CREATE', '预置计划（系统配置，E23-T20）：普通起搏器术后随访（植入日期起算；术后72小时/1个月/3个月/6个月/1年，常规随访每年滚动、ERI 期每月；入组前已过窗口跳过 Q03 / Q14）——随包为未发布草稿，由医院 C 发布启用（OQ-128②）', NULL, '{"code":"C_PACER_FU_PLAN","name":"普通起搏器术后随访","version":1,"status":"UNPUBLISHED","startDateField":"IMPLANT_DATE","cycleType":"ONGOING","cycleYears":null,"autoEnroll":true,"remark":"医院 C 预置计划（C-FU-002 / Q03）：植入日期起算；术后72小时、1个月、3个月、6个月、1年，其后常规随访每年一次（上次随访日期滚动，前后 30 天）；ERI 期每 1 个月；入组前已过窗口跳过（Q14）。随包为未发布草稿，C 侧发布启用（OQ-128②）"}', NULL);

-- ---------------------------------------------------------------- ② ICD/CRT-D 术后随访（植入或升级为 ICD / CRT-D 后转入）

INSERT INTO fu_plan (code, name, version, status, start_date_field, cycle_type, cycle_years, auto_enroll, remark, published_at)
VALUES ('C_ICD_FU_PLAN', 'ICD/CRT-D 术后随访', 1, 'UNPUBLISHED', 'IMPLANT_DATE', 'ONGOING', NULL, 1, '医院 C 预置计划（C-FU-002 / Q03）：植入日期起算；术后72小时、1个月、3个月、6个月、1年，其后常规随访每 6 个月（上次随访日期滚动，前后 14 天，可设 3–6）；ERI 期每 1 个月；入组前已过窗口跳过（Q14）。随包为未发布草稿，C 侧发布启用（OQ-128②）', NULL);

SET @c_icd_plan_id = LAST_INSERT_ID();

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_icd_plan_id, NULL, 'SUBPLAN', '术后随访', 'FIXED', 1, NULL, NULL, 'PLAN_START', 0, 'YEAR', 0, 'DAY', 0, 'DAY', 0, 'DAY', 'FORM', 'WARD_DEFAULT', NULL, NULL, NULL, 10);

SET @c_icd_postop_id = LAST_INSERT_ID();

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_icd_plan_id, @c_icd_postop_id, 'NODE', '术后72小时/出院前', NULL, NULL, NULL, NULL, 'PLAN_START', 3, 'DAY', 3, 'DAY', 3, 'DAY', 4, 'DAY', 'FORM', 'DOCTOR', 'C_PROGRAM_FU', 'SKIP', NULL, 10);

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_icd_plan_id, @c_icd_postop_id, 'NODE', '术后1个月', NULL, NULL, NULL, NULL, 'PLAN_START', 1, 'MONTH', 7, 'DAY', 7, 'DAY', 7, 'DAY', 'FORM', 'DOCTOR', 'C_PROGRAM_FU', 'SKIP', NULL, 20);

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_icd_plan_id, @c_icd_postop_id, 'NODE', '术后3个月', NULL, NULL, NULL, NULL, 'PLAN_START', 3, 'MONTH', 14, 'DAY', 14, 'DAY', 14, 'DAY', 'FORM', 'DOCTOR', 'C_PROGRAM_FU', 'SKIP', NULL, 30);

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_icd_plan_id, @c_icd_postop_id, 'NODE', '术后6个月', NULL, NULL, NULL, NULL, 'PLAN_START', 6, 'MONTH', 14, 'DAY', 14, 'DAY', 14, 'DAY', 'FORM', 'DOCTOR', 'C_PROGRAM_FU', 'SKIP', NULL, 40);

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_icd_plan_id, @c_icd_postop_id, 'NODE', '术后1年', NULL, NULL, NULL, NULL, 'PLAN_START', 12, 'MONTH', 30, 'DAY', 30, 'DAY', 30, 'DAY', 'FORM', 'DOCTOR', 'C_PROGRAM_FU', 'SKIP', NULL, 50);

-- 常规随访：上次实际随访日期 + 6 个月（可设 3–6），窗口前后 14 天，仅常规模式
INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_icd_plan_id, NULL, 'SUBPLAN', '常规随访', 'CYCLE', NULL, 6, 'MONTH', 'PLAN_START', 0, 'YEAR', 0, 'DAY', 0, 'DAY', 0, 'DAY', 'FORM', 'WARD_DEFAULT', NULL, NULL, NULL, 20);

SET @c_icd_regular_id = LAST_INSERT_ID();

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_icd_plan_id, @c_icd_regular_id, 'NODE', '常规随访', NULL, NULL, NULL, NULL, 'LAST_VISIT', 6, 'MONTH', 14, 'DAY', 14, 'DAY', 14, 'DAY', 'FORM', 'DOCTOR', 'C_PROGRAM_FU', 'SKIP', 'NORMAL', 10);

-- ERI期随访：与普通计划同构（仅 ERI 模式）
INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_icd_plan_id, NULL, 'SUBPLAN', 'ERI期随访', 'CYCLE', NULL, 1, 'MONTH', 'PLAN_START', 0, 'YEAR', 0, 'DAY', 0, 'DAY', 0, 'DAY', 'FORM', 'WARD_DEFAULT', NULL, NULL, NULL, 30);

SET @c_icd_eri_id = LAST_INSERT_ID();

INSERT INTO fu_plan_node (plan_id, parent_id, node_type, name, repeat_mode, repeat_count, cycle_value, cycle_unit, basis, offset_value, offset_unit, remind_value, remind_unit, window_before_value, window_before_unit, window_after_value, window_after_unit, task_type, owner_type, form_code, passed_window_action, enroll_mode, sort_no)
VALUES (@c_icd_plan_id, @c_icd_eri_id, 'NODE', 'ERI期随访', NULL, NULL, NULL, NULL, 'LAST_VISIT', 1, 'MONTH', 7, 'DAY', 7, 'DAY', 7, 'DAY', 'FORM', 'DOCTOR', 'C_PROGRAM_FU', 'SKIP', 'ERI', 10);

INSERT INTO fu_plan_log (plan_id, plan_code, action, description, before_json, after_json, operator_id) VALUES
(@c_icd_plan_id, 'C_ICD_FU_PLAN', 'PLAN_CREATE', '预置计划（系统配置，E23-T20）：ICD/CRT-D 术后随访（植入日期起算；术后72小时/1个月/3个月/6个月/1年，常规随访每 6 个月滚动、ERI 期每月；入组前已过窗口跳过 Q03 / Q14）——随包为未发布草稿，由医院 C 发布启用（OQ-128②）', NULL, '{"code":"C_ICD_FU_PLAN","name":"ICD/CRT-D 术后随访","version":1,"status":"UNPUBLISHED","startDateField":"IMPLANT_DATE","cycleType":"ONGOING","cycleYears":null,"autoEnroll":true,"remark":"医院 C 预置计划（C-FU-002 / Q03）：植入日期起算；术后72小时、1个月、3个月、6个月、1年，其后常规随访每 6 个月（上次随访日期滚动，前后 14 天，可设 3–6）；ERI 期每 1 个月；入组前已过窗口跳过（Q14）。随包为未发布草稿，C 侧发布启用（OQ-128②）"}', NULL);
