-- E15-T07 / A-FU-014、A-FU-023、C-FU-023：随访业务记录表 fu_record（OQ-77① / OQ-83① 预留的迁移点）。
--
-- fu_record = 随访记录的业务载体（患者 / 任务关联 + 数据范围），data / data_meta JSON 列复用表单引擎
-- 同一套校验与版本口径（FormService 写入闸门，OQ-63①「不与 fe_record 双写」）：
--   * source：TASK 任务录入（fu_task.record_id 关联）/ BACKFILL 历史补录（A-FU-014、C-FU-013：不需要任务、
--     不受窗口限制，visit_date 必填实际随访日期）/ UNPLANNED 计划外随访（随 E15-T06）/ REMOTE 远程监测（随 E21）；
--   * visit_date：实际随访日期（历史补录必填；任务录入记录的随访日期在表单字段里，A 规格 f214）；
--   * template_version 创建时固化（OQ-63②）、version 乐观锁（E25 草稿接口口径，与 fe_record 同一套）。
-- fu_record 在 ControlledTables（E07-T01 名单已含），查询按 @DataScope(resource = "patient") 出行。
-- 历史迁移：E15-T02 起任务开启录入的草稿记在 fe_record（fu_task.record_id → fe_record.id，followup V54）；
-- 本迁移把这些行整体搬进 fu_record（source = TASK）并把 fu_task.record_id 改指 fu_record.id——HTTP 契约不变
-- （draft / detail / submit 仍统一用 recordId 路由，pages.md §5）；fe_record 回归 REPORT 字段集 / 引擎参考实现
-- 载体（OQ-63①）。fe_field_history 旧记录（record_type = FE_RECORD）保留作历史溯源，此后随访记录的字段历史
-- record_type = FU_RECORD。

CREATE TABLE fu_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id（数据范围 EXISTS 锚点，ControlledTables）',
  task_id BIGINT NULL COMMENT '关联任务 fu_task.id（任务开启录入时回填；历史补录 / 计划外为 NULL）',
  source VARCHAR(16) NOT NULL DEFAULT 'TASK' COMMENT '来源：TASK 任务录入 / BACKFILL 历史补录（A-FU-014、C-FU-013）/ UNPLANNED 计划外（随 E15-T06）/ REMOTE 远程监测（随 E21）',
  visit_date DATE NULL COMMENT '实际随访日期（历史补录必填：填写实际随访日期，A-FU-014；任务录入为 NULL，随访日期在表单字段）',
  form_code VARCHAR(64) NOT NULL COMMENT '表单编码 fe_form.code',
  template_version INT NOT NULL COMMENT '保存时的模板版本（创建时固化 = 当时 current_version，OQ-63②）',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT 草稿 / SUBMITTED 已提交',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号：保存带 baseVersion，不一致 40900（E25 草稿接口同口径）',
  data JSON NULL COMMENT '字段值，键 = 稳定键 f0xx（storage=json 的字段；tech-plan §6 值编码）',
  data_meta JSON NULL COMMENT '字段附加信息：manual 手工录入 / note 备注 / fromReport 报告单带入（components.md §10.2）',
  created_by BIGINT NULL COMMENT '创建人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_fu_record_task (task_id),
  KEY idx_fu_record_patient (patient_id, visit_date),
  KEY idx_fu_record_form (form_code, template_version, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='随访记录（业务载体：患者 / 任务关联 + JSON 数据，A-FU-014、C-FU-013；E15-T07）';

-- 既有任务关联记录（E15-T02 ~ E15-T05 期间落在 fe_record）整体搬入 fu_record（source = TASK，visit_date 置空）。
-- fu_task.record_id 此时仍指 fe_record.id，按它定位行；搬完再改指 fu_record.id（下方 UPDATE）。
INSERT INTO fu_record (patient_id, task_id, source, visit_date, form_code, template_version,
                       status, version, data, data_meta, created_by, created_at, updated_by, updated_at)
SELECT t.patient_id, t.id, 'TASK', NULL, r.form_code, r.template_version,
       r.status, r.version, r.data, r.data_meta, r.created_by, r.created_at, r.updated_by, r.updated_at
FROM fe_record r
JOIN fu_task t ON t.record_id = r.id;

-- fu_task.record_id 改指 fu_record.id（fu_record.task_id 唯一，一条任务至多一条记录，映射无歧义）。
UPDATE fu_task t
JOIN fu_record fr ON fr.task_id = t.id
SET t.record_id = fr.id;

ALTER TABLE fu_task
  MODIFY COLUMN record_id BIGINT NULL COMMENT '随访记录 fu_record.id（开始录入时创建草稿并回填，pages.md §5 统一用 recordId；E15-T02 建立关联、E15-T07 载体迁至 fu_record）';
