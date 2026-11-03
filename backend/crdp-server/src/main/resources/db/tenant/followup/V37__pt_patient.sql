-- E13-T01 / A-PAT-003、A-PAT-007、C-PAT-001、C-PAT-002：患者表 pt_patient（tech-plan §7「患者数据模型」
-- 第一行：每名患者一行）。
--   * 敏感身份字段走实体加密列 + 盲索引（tech-plan §6 混合存储：姓名、病案号不进 JSON）：
--     mrn_blind 本院唯一（uk 与 delete_token 组合，软删除后病案号可复用、恢复时由 E13-T09 冲突流程兜底）；
--     name_blind 供姓名精确检索（A-PAT-002，非唯一）与「姓名 + 出生日期相同提示可能重复」（C-PAT-002）。
--     证件号 / 住址只存密文列（明文查看 A-PAT-010 用，无检索诉求不建盲索引）；电话随 E13-T03 落 pt_contact。
--   * 系统绑定列（计划、隔离、统计要用）：性别、出生日期、科室、病区、责任医生、随访状态、原系统编号、
--     随访编号（C-PAT-003，生成规则随 E21-T10）、手术 / 入院 / 出院日期、出院状态、患者类型。
--   * 出院登记（A-PAT-003）最小必填集即上面的列，其余基线分类随 E13-T02 分节补录；入组与状态流转随 E14。
--   * 出院状态口径：A 规格 §附录字段 191 —— 1=存活 / 2=病重自动出院 / 3=死亡（TINYINT 编码）；
--     性别 M / F（docs/api/patient.md §1 PatientRow.gender 口径）。
--   * followup_status 登记即「随访中」（StatusTag patient 域 ACTIVE；入组与暂停 / 移出随 E14-T06 接管）。
--   * 软删除（A-PAT-012）：deleted + delete_reason 由 E13-T09 写入；delete_token 删除时置 id，配合 uk 让
--     已删患者不再占用病案号，同时保证库层面一名在册患者一个病案号（并发登记的最终防线）。
-- 菜单授予：新建患者（plt_menu 213，平台库 V37 新增，权限码 followup:patient:create）授予内置
--   TENANT_ADMIN 与演示 FOLLOWUP_DOCTOR（老库补授；全新库角色由种子 V100 建、V112 兜底；生产由开通
--   编排 / 角色模板授予，INSERT 幂等不会重复插）。

CREATE TABLE pt_patient (
  id BIGINT NOT NULL AUTO_INCREMENT,
  mrn_cipher VARCHAR(512) NOT NULL COMMENT '病案号 / 病历号密文（CryptoService，purpose=pt-patient-mrn）',
  mrn_blind VARCHAR(128) NOT NULL COMMENT '病案号盲索引（HMAC 规范化后计算；本院唯一）',
  name_cipher VARCHAR(512) NOT NULL COMMENT '姓名密文（purpose=pt-patient-name）',
  name_blind VARCHAR(128) NOT NULL COMMENT '姓名盲索引（精确检索与同名同生日提示，非唯一）',
  id_card_cipher VARCHAR(512) NULL COMMENT '证件号密文（purpose=pt-patient-idcard；明文查看用）',
  address_cipher VARCHAR(1024) NULL COMMENT '住址密文（purpose=pt-patient-address；明文查看用）',
  gender CHAR(1) NOT NULL COMMENT '性别 M / F（patient.md §1 PatientRow 口径）',
  birth_date DATE NULL COMMENT '出生日期（C-PAT-001 最小必填；A 由基线补录）',
  dept_id BIGINT NULL COMMENT '科室 sys_dept.id（C-PAT-001 最小必填；A 可空）',
  ward_id BIGINT NOT NULL COMMENT '病区 ward.id（A/C 均必填，数据范围改写锚点列）',
  responsible_user_id BIGINT NULL COMMENT '责任医生 sys_user.id（A-PAT-017 批量指定 / 登记后指派）',
  followup_status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '随访状态 ACTIVE/PAUSED/DEAD/TERMINATED/LOST/FINISHED（components.md §2；流转随 E14-T06）',
  surgery_date DATE NULL COMMENT '手术日期（A-PAT-003 最小必填；统计与计划起算）',
  admission_date DATE NULL COMMENT '入院日期（A-PAT-003 最小必填）',
  discharge_date DATE NULL COMMENT '出院日期（A-PAT-003 最小必填；列表与筛选）',
  discharge_status TINYINT NULL COMMENT '出院状态 1=存活/2=病重自动出院/3=死亡（A 规格字段 191）',
  patient_type VARCHAR(32) NULL COMMENT '患者类型（业务字典 patient_type，A-FLD-012；随导入 / 编辑维护）',
  legacy_no VARCHAR(64) NULL COMMENT '原系统编号（A-PAT-018 宽表导入保留）',
  visit_no VARCHAR(64) NULL COMMENT '随访编号（C-PAT-003，前缀-年份-流水号；生成规则随 E21-T10）',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本（编辑接口随 E13-T02+ 递增）',
  created_by BIGINT NULL COMMENT '登记人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT NULL COMMENT '最近修改人 sys_user.id',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记（A-PAT-012，0=在册）',
  delete_token BIGINT NOT NULL DEFAULT 0 COMMENT '删除时置 id：与 mrn_blind 组成唯一键，软删后病案号可复用',
  delete_reason VARCHAR(255) NULL COMMENT '删除原因（敏感操作，必填）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_pt_patient_mrn (mrn_blind, delete_token),
  UNIQUE KEY uk_pt_patient_visit_no (visit_no, delete_token),
  KEY idx_pt_patient_name (name_blind),
  KEY idx_pt_patient_ward (ward_id),
  KEY idx_pt_patient_discharge (discharge_date),
  KEY idx_pt_patient_status (followup_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='患者主表（tech-plan §7；E13-T01）';

-- 老库补授（全新库本脚本先于种子 V100 执行、角色不存在，命中 0 行，由种子 V112 兜底）。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 213
FROM sys_role r
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 213
  );
