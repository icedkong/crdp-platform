-- E21-T01 / C-PAT-004、C-PAT-005：植入记录（一对多）与电极记录（医院 C，功能开关 implant）。
--
-- 数据模型（tech-plan §7、C 规格 §2 / §4）：原表把器械信息放在患者登记中无法表达更换与升级，
-- 拆成三层——pt_implant 每次植入一行（实体列：计划引擎与统计按器械类型 / 植入日期取数，
-- tech-plan §12「参与业务逻辑的字段存固定表」）；pt_lead 每根电极一行（腔室、部位、型号、
-- 植入日期、状态）；pt_implant_lead 关联表（电极可被新器械沿用、保留原植入日期，C-PAT-006
-- 更换流程随 E21-T02）。
--
--   * 一位患者同一时间只有一条「在用」植入记录（C 规格 §4 业务规则）：active_gen 生成列 +
--     UNIQUE(patient_id, active_gen)——非在用行生成 NULL 不参与唯一约束（tech-plan §13「pt_implant
--     唯一约束上用了生成列」），并发双开在用由 DuplicateKeyException 兜底转 40900；
--   * 状态：在用 ACTIVE / 已更换 REPLACED / 已取出 EXPLANTED（附录 A 植入 #12；取出随 E21-T02
--     explant 端点、状态流转均系统维护，不接受请求体直改）；电极：在用 ACTIVE / 废弃留置
--     ABANDONED / 已拔除 EXTRACTED（附录 A 电极 #6）；
--   * 取值存稳定字符串编码（与 pt_patient.gender M/F 同口径），显示名 / 选项清单由
--     GET /implants/options 下发（暂行，起搏器类型 / 品牌 / 起搏模式等业务字典值随 E21-T04
--     录入 cfg_dict，OQ-69①）；
--   * 三表均在 ControlledTables（E07-T01 名单已含 pt_implant / pt_lead / pt_implant_lead），
--     查询按 @DataScope(resource = "patient") 以「别名.patient_id」EXISTS 改写（OQ-97② 受控子表
--     冗余 patient_id 口径）；
--   * version 乐观锁（AGENTS §5.10：保存带 baseVersion，冲突 409）。
-- 字段级历史（C-PAT-011 沿用 A-PAT-005）随 E21-T04 C_IMPLANT 表单模板与字典落地（本任务先写
-- 操作审计 IMPLANT_CREATE / IMPLANT_UPDATE，见 docs/open-questions.md）。

CREATE TABLE pt_implant (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id（数据范围 EXISTS 锚点，ControlledTables）',
  implant_type VARCHAR(16) NOT NULL COMMENT '植入类型：FIRST 首次植入 / REPLACE 更换（电池耗竭）/ UPGRADE 升级 / LEAD_REVISION 电极修正 / OTHER 其他（附录 A 植入 #2；后四类的切换流程随 E21-T02）',
  implant_date DATE NOT NULL COMMENT '植入日期（附录 A 植入 #3；历史建档允许过去日期，随访窗口起算日 C-FU-003 随 E21-T05）',
  device_type VARCHAR(16) NOT NULL COMMENT '起搏器类型编码：SSI 单腔 / DDD 双腔 / CRT_P 三腔 / ICD / CRT_D / HBP_LBBP 希浦系统 / LEADLESS 无导线（附录 A 患者 #8；计划映射键，E21-T05）',
  brand VARCHAR(32) NULL COMMENT '品牌编码（附录 A 患者 #9；字典值随 E21-T04）',
  model VARCHAR(128) NULL COMMENT '型号（附录 A 植入 #6）',
  serial_no VARCHAR(64) NULL COMMENT '设备序列号（附录 A 植入 #7，选填 Q16）',
  pacing_mode VARCHAR(16) NULL COMMENT '起搏模式编码（统一起搏模式字典 C-FLD-003，字典值随 E21-T04）',
  hospital VARCHAR(128) NULL COMMENT '植入医院（附录 A 植入 #9，默认本院）',
  doctor VARCHAR(64) NULL COMMENT '植入医生（附录 A 植入 #10：本院医生名或外院手填）',
  indication VARCHAR(32) NULL COMMENT '植入指征编码（附录 A 植入 #11，字典 + 文字补充 Q16；字典值随 E21-T04）',
  indication_note VARCHAR(255) NULL COMMENT '植入指征补充说明（选「其他」等须填写，Q16）',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '在用 ACTIVE / 已更换 REPLACED / 已取出 EXPLANTED（附录 A 植入 #12，系统维护）',
  end_date DATE NULL COMMENT '结束日期（更换 / 取出时 = 新植入日期或取出日期，C 规格 §4 业务规则）',
  end_reason VARCHAR(255) NULL COMMENT '结束原因',
  active_gen TINYINT GENERATED ALWAYS AS (IF(status = 'ACTIVE', 1, NULL)) STORED COMMENT '在用判定生成列：非在用为 NULL 不参与唯一约束（一位患者仅一条在用）',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号：保存带 baseVersion，不一致 40900（E25 草稿接口同口径）',
  created_by BIGINT NULL COMMENT '创建人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_pt_implant_active (patient_id, active_gen),
  KEY idx_pt_implant_patient (patient_id, implant_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='植入记录（一对多，C-PAT-004；tech-plan §7 pt_implant）';

CREATE TABLE pt_lead (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id（数据范围 EXISTS 锚点；电极随患者走，可跨植入记录沿用）',
  chamber VARCHAR(4) NOT NULL COMMENT '腔室：RA / RV / LV（附录 A 电极 #2）',
  position VARCHAR(32) NULL COMMENT '植入部位编码，按腔室取值（附录 A 电极 #3、Q15：RA 右心耳/右房间隔；RV 右室心尖/右室间隔/希氏束/左束支；LV 冠状静脉窦；其他 OTHER）',
  model VARCHAR(128) NULL COMMENT '电极型号（附录 A 电极 #4，选填）',
  implant_date DATE NULL COMMENT '电极植入日期（附录 A 电极 #5：默认同植入记录；被沿用时保留原日期）',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '在用 ACTIVE / 废弃留置 ABANDONED / 已拔除 EXTRACTED（附录 A 电极 #6）',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
  created_by BIGINT NULL COMMENT '创建人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_pt_lead_patient (patient_id, chamber)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='电极记录（每根电极一行，C-PAT-005；无导线起搏器不录电极）';

CREATE TABLE pt_implant_lead (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id（数据范围 EXISTS 锚点，冗余列与 rpt_report_link 同口径 OQ-97②）',
  implant_id BIGINT NOT NULL COMMENT '植入记录 pt_implant.id',
  lead_id BIGINT NOT NULL COMMENT '电极 pt_lead.id（沿用 = 同一 lead 行挂到新植入记录下，保留原植入日期）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_pt_implant_lead (implant_id, lead_id),
  KEY idx_pt_implant_lead_lead (lead_id),
  KEY idx_pt_implant_lead_patient (patient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='植入记录-电极关联（电极可跨器械沿用，tech-plan §7 pt_implant_lead）';

-- 权限码 followup:implant:edit（C 规格 §10 权限点表「新增与编辑植入记录、电极，更换 / 升级」，
-- 普通级）按钮 228 挂患者档案（21）下，平台库 V69 新增；老库在此补授内置角色——
-- 全新库本脚本先于种子 V100 执行、角色不存在，命中 0 行，由种子 V135 兜底（V47/V113 同口径）。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 228
FROM sys_role r
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 228
  );
