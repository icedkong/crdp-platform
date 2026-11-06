-- E13-T02 / A-PAT-006（tasks.csv 验收「锁定后只读，解锁需权限并留痕」；tech-plan §7「患者数据模型」）：
-- 基线分节存储 pt_baseline_section 与基线锁定状态 pt_baseline_lock。
--   * pt_baseline_section（每名患者每个分类一行，tech-plan §7）：出院登记的最小必填集落 pt_patient 实体列（E13-T01），
--     其余分类 2–8 的字段值按「模板分类」分节补录——data / data_meta JSON（值编码同 fe_record.data）、
--     template_version 创建时固化（记录按该版本渲染与校验，E11-T02）、version 乐观锁（保存 / 暂存带 baseVersion，
--     不一致 40900，AGENTS §5.10）、has_draft 标记「存在晚于上次正式保存的草稿」（自动保存写入，正式保存清除，
--     tech-plan §6「草稿语义」：草稿不写字段历史、不做必填校验）。
--     页签分别暂存互不冲突（A-PAT-003「可分次补录」）：(patient_id, category_key) 唯一。
--   * pt_baseline_lock（每名患者一行，tech-plan §7）：locked + 最近一次锁定 / 解锁的人、时间与原因。
--     解锁必填原因（A-PAT-006）；锁定 / 解锁各写操作审计（BASELINE_LOCK / BASELINE_UNLOCK，留痕口径 =
--     审计只追加，docs/api/audit.md），当前态与最近一次流转从本行直读。C 的随访记录锁定（A 规格 §2「与基线锁定
--     同一机制」）复用时另立行类型，不写本表。
--   * 病案号 / 病历号等敏感身份字段不经表单 JSON（V37 实体加密列），写入闸门按 storage 拒绝；
--     字段级历史落 fe_field_history（record_type = PT_BASELINE_SECTION，V32 已建表，E11-T06 写入口）。
-- 菜单授予：锁定 / 解锁按钮（plt_menu 226，权限码 followup:patient:lock，A 规格权限表「锁定与解锁基线」）与
--   基线分节编辑隐藏路由（plt_menu 227，平台库 V48 新增）授予内置 TENANT_ADMIN 与演示 FOLLOWUP_DOCTOR
--   （病区医生确认锁定，A-PAT-006；老库补授，全新库由种子 V122 兜底）。

CREATE TABLE pt_baseline_section (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id（数据范围锚点列，E07-02 EXISTS 改写）',
  form_code VARCHAR(64) NOT NULL COMMENT '表单编码 fe_form.code（A_BASELINE；C 基线表单随 E21 接入）',
  category_key VARCHAR(32) NOT NULL COMMENT '分类锚点键（fe_form_version.categories[].key，如 case / echo）',
  template_version INT NOT NULL COMMENT '创建时固化的模板版本（记录按该版本渲染与校验，E11-T02）',
  data JSON NULL COMMENT '字段值，键 = 稳定键 f0xx（storage=json 字段；值编码同 fe_record.data）',
  data_meta JSON NULL COMMENT '字段附加信息（manual / note / fromReport，components.md §10.2）',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号：保存 / 暂存带 baseVersion，不一致 40900（AGENTS §5.10）',
  has_draft TINYINT NOT NULL DEFAULT 0 COMMENT '存在晚于上次正式保存的草稿（自动保存置 1、正式保存清 0）',
  created_by BIGINT NULL COMMENT '创建人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT NULL COMMENT '最近修改人 sys_user.id',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_pt_baseline_section (patient_id, category_key),
  KEY idx_pt_baseline_section_form (form_code, template_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='基线分节（每名患者每个分类一行，A-PAT-003 可分次补录；E13-T02）';

CREATE TABLE pt_baseline_lock (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id（一行 / 患者，数据范围锚点列）',
  locked TINYINT NOT NULL DEFAULT 0 COMMENT '锁定标记：1 = 已锁定（分节保存与暂存一律 40901，只读）',
  lock_reason VARCHAR(255) NULL COMMENT '锁定说明（可选，如：出院 30 天内确认无误）',
  locked_by BIGINT NULL COMMENT '锁定人 sys_user.id',
  locked_at DATETIME(3) NULL COMMENT '锁定时间',
  unlock_reason VARCHAR(255) NULL COMMENT '最近一次解锁原因（解锁必填，A-PAT-006）',
  unlocked_by BIGINT NULL COMMENT '最近一次解锁人 sys_user.id',
  unlocked_at DATETIME(3) NULL COMMENT '最近一次解锁时间',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_pt_baseline_lock (patient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='基线锁定状态（一行 / 患者；锁定后只读、解锁需权限并填写原因，A-PAT-006；E13-T02）';

-- 老库补授（全新库本脚本先于种子 V100 执行、角色不存在，命中 0 行，由种子 V122 兜底）。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.n
FROM sys_role r
JOIN (SELECT 226 AS n UNION SELECT 227 AS n) m
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.n
  );
