-- E21-T21 / C-WARD-006（沿用 A-WARD-005；OQ-74 ②「C 域接入 deptId 时按同一入口扩展并补科室历史表」
-- 在本任务落地）：转科室历史表，结构与 pt_ward_history（followup V43）同构。
--
-- ① pt_dept_history 转科室历史（C-WARD-006「保留转科历史」；只追加，已同步登记进
--   ControlledTables，查询经 pt_patient 锚点按数据范围改写——科室与病区分支 OR）。
--   任务与扫描件经 patient_id 关联患者、随患者迁移，无需同步子表（E07-T02 改写口径，
--   与 pt_ward_history 同一隔离模型）。
-- ② 无预置数据：历史行由转科室动作写入（POST /api/hospital/followup/patients/{id}/transfer
--   携带 deptId），A / B 租户 implant 开关关闭不接收入口（40001），表空置。

CREATE TABLE pt_dept_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id',
  from_dept_id BIGINT NULL COMMENT '原科室 sys_dept.id',
  to_dept_id BIGINT NOT NULL COMMENT '新科室 sys_dept.id',
  reason VARCHAR(255) NOT NULL COMMENT '转科室原因（必填，C-WARD-006）',
  transferred_by BIGINT NULL COMMENT '操作人 sys_user.id',
  transferred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_pt_dept_history_patient (patient_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='转科室历史（只追加；C-WARD-006，医院 C 科室 + 病区双维度）';
