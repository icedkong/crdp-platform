-- E13-T03 / tech-plan §7「患者数据模型」：联系方式版本 pt_contact、随访状态时间线 pt_status_history、
-- 当前状态摘要 pt_patient_summary（hospital-a-spec §2「数据模型与时间区分」：一次性的基线、每年一条的随访
-- 记录、按版本保存的联系方式，再由系统汇总出「患者当前状态」）。
--
-- pt_contact（每个号码一个版本）：
--   * 电话加密 + 盲索引（purpose=pt-contact-phone）；盲索引非唯一，供后续按号码精确检索；
--   * slot 号码槽位（PHONE_1 电话1 / PHONE_2 电话2，A 规格 §2「联系方式：电话1、电话2、是否已加微信」）；
--   * 换号时新增一条并保留旧号，记录生效日期与来源（哪次随访）：source REGISTRATION 登记 / FOLLOWUP 随访
--     （source_ref_id 指向任务 / 记录，E15 沟通记录接入）/ IMPORT 历史导入（E18）/ MANUAL 页面维护；
--   * version 为该槽位内递增版本号（uk 保证不重号）；「当前版本」= 槽位内最大 version，旧版本永久保留——
--     联系方式更新不改动历史沟通记录引用的版本（fu_contact_log.contact_id 随 E15 引用本表 id）；
--   * wechat_added 是否已加微信（A 规格附录字段 213「建议同时记入联系方式」）：作为版本快照随行保存。
--
-- pt_status_history（每次状态变化一行）：status 口径 components.md §2 patient 域
--   ACTIVE/PAUSED/DEAD/TERMINATED/LOST/FINISHED；登记即 ACTIVE（OQ-71②，初始行 source=REGISTRATION）；
--   暂停 / 移除 / 恢复 / 停止 / 失访 / 死亡的流转接口与任务联动随 E14-T06 / A-FU-008（写入口经
--   PatientStatusHistoryService，本表只追加、不修改）。统计判断「窗口开始日是否仍符合条件」以本表为准
--   （tech-plan §7）。
--
-- pt_patient_summary（每名患者一行，派生只读）：
--   * 当前状态摘要：最近随访日期、生存状态、关键指标（A 最近 EF；C 器械与参数随 E21）、终点事件是否
--     发生过、当前用药、下次窗口（hospital-a-spec §2；A「永远取最新一条已提交记录，终点事件按任一记录
--     发生过汇总」）；
--   * 随访派生列（latest_* / endpoint_events / key_indicators）由 E15 提交后重算写入，下次窗口由 E14
--     入组调度写入，两个完整度列分别随 E13-T02 / E15 写入——本任务先建表并在登记时建行（E13-T04 列表
--     读取锚点），无直接修改接口（「每次提交后重算，不可直接修改」）；
--   * key_indicators 键口径：A {"ef": 数值}（最近 EF）+ {"medications": [...]}（当前用药）；C 追加
--     器械键随 E21。endpoint_events 键 = 终点事件字段编码（心梗、再住院等），值 = 最近发生日期。
--
-- 菜单授予：「编辑患者资料」按钮（plt_menu 214，平台库 V38 新增，权限码 followup:patient:edit，patient.md
--   §6 POST /contact-info 用）授予内置 TENANT_ADMIN 与演示 FOLLOWUP_DOCTOR（老库补授；全新库角色由种子
--   V100 建、V113 兜底；生产由开通编排 / 角色模板授予，INSERT 幂等不会重复插）。

CREATE TABLE pt_contact (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id',
  slot VARCHAR(16) NOT NULL COMMENT '号码槽位 PHONE_1 电话1 / PHONE_2 电话2（每个号码一个版本）',
  phone_cipher VARCHAR(512) NOT NULL COMMENT '电话密文（CryptoService，purpose=pt-contact-phone）',
  phone_blind VARCHAR(128) NOT NULL COMMENT '电话盲索引（规范化后 HMAC；非唯一，供按号码精确检索）',
  wechat_added TINYINT NULL COMMENT '是否已加微信（A 规格字段 213；随版本快照，1=是 / 0=否 / NULL=未记录）',
  effective_date DATE NOT NULL COMMENT '生效日期（换号时记录；不得早于当前版本的生效日期）',
  source VARCHAR(32) NOT NULL COMMENT '来源 REGISTRATION 登记时 / FOLLOWUP 随访中 / IMPORT 历史导入 / MANUAL 页面维护',
  source_ref_id BIGINT NULL COMMENT '来源引用（FOLLOWUP 时指向任务 / 记录 id，E15 接入）',
  version INT NOT NULL COMMENT '该槽位版本号（1 起逐次 +1；当前版本 = 槽位内最大 version）',
  remark VARCHAR(255) NULL COMMENT '备注（如「第 2 年随访时换号」）',
  created_by BIGINT NULL COMMENT '登记人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '版本创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pt_contact_version (patient_id, slot, version),
  KEY idx_pt_contact_patient (patient_id),
  KEY idx_pt_contact_phone_blind (phone_blind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='联系方式版本（tech-plan §7；换号新增版本保留旧号，E13-T03）';

CREATE TABLE pt_status_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id',
  status VARCHAR(16) NOT NULL COMMENT '随访状态 ACTIVE/PAUSED/DEAD/TERMINATED/LOST/FINISHED（components.md §2 patient 域）',
  effective_date DATE NOT NULL COMMENT '生效日期（统计按窗口开始日回看本表）',
  reason VARCHAR(255) NULL COMMENT '原因（暂停 / 移除必填，A-PAT-011；流转接口随 E14-T06）',
  source VARCHAR(32) NOT NULL COMMENT '来源 REGISTRATION 登记初始行 / MANUAL 人工流转 / PLAN 计划联动 / SYSTEM 系统判定',
  created_by BIGINT NULL COMMENT '操作人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '写入时间',
  PRIMARY KEY (id),
  KEY idx_pt_status_history_patient (patient_id, effective_date, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='随访状态时间线（tech-plan §7；只追加，E13-T03）';

CREATE TABLE pt_patient_summary (
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id（1:1）',
  latest_followup_date DATE NULL COMMENT '最近随访日期（实际随访日期最晚的已提交记录；E15 提交后重算）',
  survival_status VARCHAR(16) NULL COMMENT '生存状态 ALIVE/DEAD/UNKNOWN（最近已提交记录；E15）',
  key_indicators JSON NULL COMMENT '关键指标 JSON：A {"ef":最近EF,"medications":[当前用药]}；C 器械与最近参数随 E21',
  endpoint_events JSON NULL COMMENT '终点事件是否发生过（键=事件字段编码，值=最近发生日期；任一记录发生过即记，E15）',
  next_plan_date DATE NULL COMMENT '下次计划日期（E14 入组调度写入）',
  next_window_start DATE NULL COMMENT '下次窗口开始（E14）',
  next_window_end DATE NULL COMMENT '下次窗口结束（E14）',
  baseline_completeness TINYINT NULL COMMENT '基线资料完整度 0–100（A-PAT-019；E13-T02 写入）',
  followup_completeness DECIMAL(5,2) NULL COMMENT '随访资料完整度（年度完整度平均值；E15 写入，无记录为 NULL）',
  recomputed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近重算时间（登记建行 / 每次提交后重算）',
  recomputed_by BIGINT NULL COMMENT '最近重算触发人 sys_user.id（系统重算为 NULL）',
  PRIMARY KEY (patient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='患者当前状态摘要（tech-plan §7；派生只读，每次提交后重算，E13-T03）';

-- 老库补授（全新库本脚本先于种子 V100 执行、角色不存在，命中 0 行，由种子 V113 兜底）。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 214
FROM sys_role r
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 214
  );
