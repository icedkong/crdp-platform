-- E14-T01 / A-FU-016、A-FU-018、A-FU-019、C-FU-001：随访计划引擎的数据模型。
--
-- 三层结构「计划 > 子计划 > 任务」（A-FU-016）落两张表：
--   * fu_plan       计划（每个版本一行，code 跨版本不变 + version 递增；状态 A-FU-018；
--                   「随访中 / RUNNING」不入库——由「PUBLISHED 且有在组入组」派生展示）；
--   * fu_plan_node  子计划（SUBPLAN）与节点（NODE）共表：parent_id 为 NULL 是子计划行，指向子计划行是节点行；
--                   偏移 / 提醒提前量 / 窗口前后（可不对称）/ 基准（起算日期或上次实际随访）/ 重复方式
--                   （固定次数或持续循环）/ 任务类型（表单或站内提醒）/ 负责人类型 / 入组前已过窗口策略
--                   （补建补录或跳过）/ 启用条件（常规或 ERI 期，C 用）——A、B 预置与 C 节点表都用这套参数表达
--                   （tech-plan §8「节点参数」表）。
-- 版本锁定（A-FU-019、C-FU-001）：已发布版本整体只读，只能复制出新版本再发布；患者入组时把版本固定在
--   fu_enrollment.plan_id，之后按需创建的任务也按该版本（E14-T04）。发布新版本时旧已发布版本自动转 DISABLED
--   （不再允许入组 → 新入组必然用新版本；在组患者不迁移，沿用原版本，A-FU-020 迁移首期不做）。
-- fu_enrollment 起算日期快照 / 暂停区间 / 下次计划日期与开启日缓存列由后续任务（E14-T03/T04/T06）填充，
--   本任务先落列与「一名患者在同一计划族内至多一条在组入组」的唯一约束（生成列 active_key：在组时 = plan_code，
--   已移除为 NULL 不占唯一键——与 C「同一患者只有一条在用器械」同口径，tech-plan §7）。

CREATE TABLE fu_plan (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL COMMENT '计划编码（跨版本不变的计划族标识，本院唯一族）',
  name VARCHAR(128) NOT NULL COMMENT '计划名称（如「冠心病术后年度随访（示例）」）',
  version INT NOT NULL COMMENT '计划版本号（v1 起，复制新版本时 +1，A-FU-019）',
  status VARCHAR(16) NOT NULL DEFAULT 'UNPUBLISHED' COMMENT '状态：UNPUBLISHED 未发布 / PUBLISHED 已发布 / PAUSED 已暂停 / DISABLED 已停用（A-FU-018；「随访中」由有在组入组派生，不入库）',
  start_date_field VARCHAR(32) NOT NULL COMMENT '起算日期字段：DISCHARGE_DATE 出院 / SURGERY_DATE 手术 / REGISTRATION_DATE 建档 / IMPLANT_DATE 植入（C）/ CUSTOM 自定义（A-FU-016）',
  cycle_type VARCHAR(16) NOT NULL DEFAULT 'ONGOING' COMMENT '管理周期类型：YEARS 固定 N 年 / ONGOING 长期（A-FU-016）',
  cycle_years INT NULL COMMENT '管理周期年数（cycle_type=YEARS 时必填；ONGOING 为 NULL 不设结束年度）',
  remark VARCHAR(255) NULL COMMENT '备注',
  lock_version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本（可编辑对象带 version，保存带 baseVersion，冲突 409）',
  published_at DATETIME(3) NULL COMMENT '首次发布时间（审计与列表展示）',
  published_by BIGINT NULL COMMENT '发布人 sys_user.id',
  created_by BIGINT NULL COMMENT '创建人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_fu_plan_code_version (code, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='随访计划（每版本一行；A-FU-016/018/019、C-FU-001）';

CREATE TABLE fu_plan_node (
  id BIGINT NOT NULL AUTO_INCREMENT,
  plan_id BIGINT NOT NULL COMMENT '所属计划版本 fu_plan.id',
  parent_id BIGINT NULL COMMENT '父行 id：NULL = 子计划（SUBPLAN）；非 NULL = 子计划下的节点（NODE）',
  node_type VARCHAR(8) NOT NULL COMMENT '行类型：SUBPLAN 子计划（里程碑）/ NODE 节点（任务定义）',
  name VARCHAR(128) NOT NULL COMMENT '名称（如「第 1 年随访」「出院前随访」）',
  repeat_mode VARCHAR(16) NULL COMMENT '子计划重复方式：FIXED 固定次数 / CYCLE 持续循环（直到死亡、失访或人工停止；A-FU-016）',
  repeat_count INT NULL COMMENT 'FIXED 的固定次数（≥1）',
  cycle_value INT NULL COMMENT 'CYCLE 的周期数值（≥1）',
  cycle_unit VARCHAR(8) NULL COMMENT 'CYCLE 的周期单位：DAY / WEEK / MONTH / YEAR',
  basis VARCHAR(16) NULL COMMENT '节点偏移基准：PLAN_START 计划起算日期 / LAST_VISIT 上次实际随访日期（C 常规随访滚动）',
  offset_value INT NOT NULL DEFAULT 0 COMMENT '偏移数值（0 允许；按月 / 年加遇月末取当月最后一天，E14-T03）',
  offset_unit VARCHAR(8) NOT NULL DEFAULT 'YEAR' COMMENT '偏移单位：DAY / WEEK / MONTH / YEAR',
  remind_value INT NOT NULL DEFAULT 0 COMMENT '提醒提前量数值（0 = 计划日期当天开始提醒，A-FU-016）',
  remind_unit VARCHAR(8) NOT NULL DEFAULT 'DAY' COMMENT '提醒提前量单位：DAY / WEEK / MONTH',
  window_before_value INT NOT NULL DEFAULT 0 COMMENT '窗口前数值（可与窗口后不对称，如 C 植入后 72 小时前 3 后 4）',
  window_before_unit VARCHAR(8) NOT NULL DEFAULT 'DAY' COMMENT '窗口前单位：DAY / WEEK / MONTH / YEAR',
  window_after_value INT NOT NULL DEFAULT 0 COMMENT '窗口后数值',
  window_after_unit VARCHAR(8) NOT NULL DEFAULT 'DAY' COMMENT '窗口后单位：DAY / WEEK / MONTH / YEAR',
  task_type VARCHAR(16) NOT NULL DEFAULT 'FORM' COMMENT '任务类型：FORM 电话随访 + 表单 / REMINDER 站内提醒（A-FU-026）',
  owner_type VARCHAR(16) NOT NULL DEFAULT 'WARD_DEFAULT' COMMENT '责任人类型：DOCTOR 责任医生 / WARD_DEFAULT 病区默认负责人（A-FU-009）',
  form_code VARCHAR(64) NULL COMMENT 'FORM 任务使用的随访记录表单 code（FORM 必填、REMINDER 为 NULL）',
  passed_window_action VARCHAR(16) NULL COMMENT '入组前已过窗口策略：BACKFILL 补建补录任务（A、B，Q44）/ SKIP 跳过（C，Q14）',
  enroll_mode VARCHAR(16) NULL COMMENT '启用条件（C）：NORMAL 常规 / ERI ERI 期循环节点；NULL = 全部模式生效',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序（同层按 sort_no、id 升序）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_fu_plan_node_plan (plan_id),
  KEY idx_fu_plan_node_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='计划节点（子计划 SUBPLAN / 节点 NODE 共表；A-FU-016 参数全集）';

CREATE TABLE fu_enrollment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id',
  plan_id BIGINT NOT NULL COMMENT '入组时固定的计划版本 fu_plan.id（患者沿用入组时的版本，A-FU-019）',
  plan_code VARCHAR(64) NOT NULL COMMENT '计划族编码快照（fu_plan.code，唯一键按计划族判「已在组」）',
  start_date DATE NULL COMMENT '起算日期快照（入组时按计划的起算字段取值；E14-T03/T05 计算任务日期用）',
  mode VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT '入组模式：NORMAL 常规 / ERI ERI 期（C）',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE 已启动 / PAUSED 已暂停 / REMOVED 已移除（A-FU-022）',
  pause_reason VARCHAR(255) NULL COMMENT '暂停原因（A-FU-008 须填原因）',
  pause_from DATETIME(3) NULL COMMENT '暂停区间起（恢复时写 pause_to；暂停期间不创建任务不发通知）',
  pause_to DATETIME(3) NULL COMMENT '暂停区间止',
  remove_reason VARCHAR(255) NULL COMMENT '移除原因（拒访等，A-FU-008）',
  next_plan_date DATE NULL COMMENT '下次计划日期缓存（E14-T04 调度维护）',
  next_open_date DATE NULL COMMENT '下次任务开启日缓存（提醒开始日与窗口开始日较早一天，E14-T04）',
  created_by BIGINT NULL COMMENT '入组操作人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  active_key VARCHAR(64) GENERATED ALWAYS AS (IF(status IN ('ACTIVE', 'PAUSED'), plan_code, NULL)) STORED COMMENT '在组标记（已移除为 NULL 不占唯一键）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_fu_enrollment_active (patient_id, active_key),
  KEY idx_fu_enrollment_plan (plan_id),
  KEY idx_fu_enrollment_patient (patient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='患者入组（版本固定在 plan_id；A-FU-019、A-PAT-016、C-FU-003）';

-- 菜单补授（plt_menu 24 随访计划 / 219 编辑 / 220 发布 / 221 入组，平台库 V42 新增）：
--   计划配置由租户管理员维护（A 规格权限点表全部「普通」级）——24/219/220 只授 TENANT_ADMIN；
--   221 入组 / 暂停 / 恢复 / 移出（A-PAT-011、A-PAT-016）是医生日常操作 → 加授演示 FOLLOWUP_DOCTOR。
--   全新库本脚本先于种子 V100 执行、角色不存在，命中 0 行，由种子 V117 兜底。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 24 AS menu_id
  UNION ALL SELECT 'TENANT_ADMIN', 219
  UNION ALL SELECT 'TENANT_ADMIN', 220
  UNION ALL SELECT 'TENANT_ADMIN', 221
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 24
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 221
) m ON m.code = r.code
WHERE r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
