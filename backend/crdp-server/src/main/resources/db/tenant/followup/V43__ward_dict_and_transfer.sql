-- E13-T08 / A-WARD-001、A-WARD-005、A-WARD-008：病区字典补列、转病区历史表与菜单补授。
--
-- ① 病区字典（A-WARD-001 关键字段全集，ward 表由 followup V2 建、default_owner_id 由 V22 加）：
--   ward_type  病区 / 中心（WARD / CENTER，C-WARD-001 同一字典）；
--   alias      别名多值（CSV「;」分隔；A-WARD-001「用于匹配历史数据中的不同写法」——导入值映射
--              接口（A-WARD-006）随 E18 导入任务落地，本任务先落字段与维护入口）；
--   sort_no    排序（列表与候选下拉统一）。
-- ② pt_ward_history 转病区历史（A-WARD-005「保留转病区历史」；只追加，已在 ControlledTables 登记，
--   查询经 pt_patient 锚点按数据范围改写）。任务与附件经 patient_id 关联患者，转病区随患者迁移，
--   无需同步子表（E07-T02 改写口径）。
-- ③ 菜单补授（plt_menu 38 病区管理 / 216 转病区按钮，平台库 V40 新增）：A-WARD-001 病区字典由
--   租户管理员维护 → 38 只授 TENANT_ADMIN；216 转病区为普通权限点（A 规格权限点表）→ 授
--   TENANT_ADMIN 与演示 FOLLOWUP_DOCTOR（生产角色模板由开通编排 / 平台端授予）。
--   全新库本脚本先于种子 V100 执行、角色不存在，命中 0 行，由种子 V115 兜底。

ALTER TABLE ward
  ADD COLUMN ward_type VARCHAR(16) NOT NULL DEFAULT 'WARD' COMMENT '类型：病区 WARD / 中心 CENTER（A-WARD-001、C-WARD-001）',
  ADD COLUMN alias VARCHAR(255) NULL COMMENT '别名（; 分隔多值，匹配历史写法；值映射接口随 A-WARD-006）',
  ADD COLUMN sort_no INT NOT NULL DEFAULT 0 COMMENT '排序（升序，同号按 id）';

CREATE TABLE pt_ward_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id',
  from_ward_id BIGINT NULL COMMENT '原病区 ward.id',
  to_ward_id BIGINT NOT NULL COMMENT '新病区 ward.id',
  reason VARCHAR(255) NOT NULL COMMENT '转病区原因（必填，A-WARD-005）',
  transferred_by BIGINT NULL COMMENT '操作人 sys_user.id',
  transferred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_pt_ward_history_patient (patient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='转病区历史（只追加；A-WARD-005、C-WARD-006）';

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 38 AS menu_id
  UNION ALL SELECT 'TENANT_ADMIN', 216
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 216
) m ON m.code = r.code
WHERE r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
