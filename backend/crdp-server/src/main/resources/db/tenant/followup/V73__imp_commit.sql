-- E18-T05 / tech-plan §12⑥⑦：分批提交与历史随访记录生成（tasks.csv 验收「按年度节点生成历史记录，可按批回滚」）。
--
-- ① fu_record 补「导入年度记录」的锚点列（tech-plan §11 fu_record 字段面：所属任务与入组、节点与第 N 次、随访年度、
--    计划日期、窗口）。任务录入记录经 fu_task 间接携带这些信息（V58 设计）；历史导入不建任务（E14-T04 已定，导入按
--    自身规则生成历史记录），记录直接锚定入组 + 节点 + 第 N 次，供按年度筛选（A-PAT-018「标为历史导入，可在随访记录
--    中筛选」）、时间轴与后续统计事实表（st_node_occurrence 随 E19）取数：
--      * source 扩枚举：HIST_IMPORT 历史导入（有数据的年度，直接 SUBMITTED）、HIST_MISSED 历史未随访
--        （窗口已结束且无数据的年度，Q06：不计入逾期与通知、不计入已随访与完整度，可补录——DRAFT 待医生补填提交）；
--      * uk（入组 + 节点 + 第 N 次）与 fu_task 同粒度幂等：导入年度记录一个年度一条，重复导入走更新路径不撞键
--        （无锚点的历史行 task_id / enrollment_id 均为 NULL，不占唯一键——MySQL 唯一索引 NULL 不去重，与既有口径一致）。
-- ② imp_job.scope_json：确认提交时冻结操作人数据范围快照（与 job_export.scope_json base V20 同口径）——提交在异步
--    线程执行（租户上下文经 TenantContextTaskDecorator 复制，但用户会话不复制），「只能导入本人可见病区」
--    （tech-plan §12①）按冻结范围逐行校验，越范围行跳过并计数。
-- ③ imp_batch / imp_batch_write：批次与批次写数（tech-plan §12⑥「按每批约 200 名患者一个事务写入，失败批次可单独
--    重试」）。imp_batch 一行 = 一批（状态 PENDING → RUNNING → DONE | FAILED；回滚后 ROLLED_BACK），计数（新建 /
--    更新 / 跳过 / 记录数）供向导进度（E18-T06）；imp_batch_write 记录该批写下的实体（类型 + id + 是否本导入新建），
--    「可按批回滚」（tasks.csv 验收）按它反查删除——新建实体整体删除，更新只回滚导入新建的子行（主档更新经
--    fe_field_history 留痕，不自动还原，见 OQ-104）。

ALTER TABLE fu_record
  ADD COLUMN enrollment_id BIGINT NULL COMMENT '入组 fu_enrollment.id（历史导入年度记录锚点，E18-T05；任务录入经 fu_task 间接关联为 NULL）' AFTER task_id,
  ADD COLUMN node_id BIGINT NULL COMMENT '节点 fu_plan_node.id（历史导入年度记录锚点：该年度对应计划节点，E18-T05）' AFTER enrollment_id,
  ADD COLUMN occurrence INT NULL COMMENT '子计划内第 N 次（从 1 起；历史导入年度记录锚点，年度节点 = 随访年度，E18-T05）' AFTER node_id,
  ADD COLUMN followup_year INT NULL COMMENT '随访年度 N（年度节点才填；A 医院年度随访 = occurrence，非年度节点（医院 B 天 / 月节点）为 NULL，E18-T05）' AFTER occurrence,
  ADD COLUMN plan_date DATE NULL COMMENT '该年度计划日期（基准 + 偏移 + (N−1)×周期，E18-T05 历史导入记录锚点）' AFTER followup_year,
  ADD COLUMN window_start DATE NULL COMMENT '该年度窗口开始日（E18-T05 历史导入记录锚点）' AFTER plan_date,
  ADD COLUMN window_end DATE NULL COMMENT '该年度窗口结束日（E18-T05 历史导入记录锚点；窗口外日期照常导入并标历史导入，A-PAT-018）' AFTER window_start,
  ADD UNIQUE KEY uk_fu_record_node_occurrence (enrollment_id, node_id, occurrence),
  ADD KEY idx_fu_record_year (patient_id, followup_year),
  MODIFY COLUMN source VARCHAR(16) NOT NULL DEFAULT 'TASK' COMMENT '来源：TASK 任务录入 / BACKFILL 历史补录（A-FU-014、C-FU-013）/ UNPLANNED 计划外 / REMOTE 远程监测（随 E21）/ HIST_IMPORT 历史导入（E18，有数据年度）/ HIST_MISSED 历史未随访（E18，窗口已结束且无数据，Q06）';

ALTER TABLE imp_job
  ADD COLUMN scope_json VARCHAR(512) NULL COMMENT '确认提交时冻结的操作人数据范围快照（全院 all=true 或病区 id 清单；提交按它逐行校验病区，与 job_export.scope_json 同口径，E18-T05）' AFTER options_json;

CREATE TABLE imp_batch (
  id BIGINT NOT NULL AUTO_INCREMENT,
  job_id BIGINT NOT NULL COMMENT '导入作业 imp_job.id',
  batch_no INT NOT NULL COMMENT '批次号（作业内从 1 起，向导按批展示与回滚）',
  first_patient_row_id BIGINT NOT NULL COMMENT '本批首位患者的暂存行 imp_row.id（PATIENT / BASELINE 行按 id 连续切批）',
  last_patient_row_id BIGINT NOT NULL COMMENT '本批末位患者的暂存行 imp_row.id',
  patient_count INT NOT NULL COMMENT '本批患者行数（宽表 PATIENT 行 / 长表 BASELINE 行）',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待执行 / RUNNING 执行中 / DONE 完成 / FAILED 失败（可单独重试，tech-plan §12⑥）/ ROLLED_BACK 已回滚',
  created_patients INT NOT NULL DEFAULT 0 COMMENT '新建患者数',
  updated_patients INT NOT NULL DEFAULT 0 COMMENT '更新患者数（重复病案号 + UPDATE 策略）',
  skipped_patients INT NOT NULL DEFAULT 0 COMMENT '跳过患者数（SKIP 策略 / 越病区范围 / 病区空且未给兜底病区）',
  records_created INT NOT NULL DEFAULT 0 COMMENT '生成随访记录数（历史导入 + 历史未随访）',
  fail_reason VARCHAR(512) NULL COMMENT '批次失败原因（非敏感摘要；重试成功后保留最近一次失败原因备查）',
  rolled_back_at DATETIME(3) NULL COMMENT '回滚完成时间（状态 ROLLED_BACK）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_imp_batch (job_id, batch_no),
  KEY idx_imp_batch_status (job_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='导入提交批次（每批约 200 名患者一个事务，tech-plan §12⑥；失败批次可单独重试，回滚按批，E18-T05）';

CREATE TABLE imp_batch_write (
  id BIGINT NOT NULL AUTO_INCREMENT,
  batch_id BIGINT NOT NULL COMMENT '批次 imp_batch.id',
  job_id BIGINT NOT NULL COMMENT '导入作业 imp_job.id（冗余，按作业整体回滚取数用）',
  entity_type VARCHAR(24) NOT NULL COMMENT '实体类型：PATIENT 患者 / BASELINE_SECTION 基线分节 / CONTACT 联系方式版本 / ENROLLMENT 入组 / FU_RECORD 随访记录',
  entity_id BIGINT NOT NULL COMMENT '实体行 id',
  created_flag TINYINT NOT NULL DEFAULT 1 COMMENT '1 = 本导入新建（回滚整体删除）/ 0 = 更新既有患者时新建的子行（回滚删除子行，主档不还原，OQ-104）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_imp_batch_write_batch (batch_id, entity_type),
  KEY idx_imp_batch_write_job (job_id, entity_type, entity_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='导入批次写数清单（按批回滚的反查依据，E18-T05；只记本导入写下的行，不记更新前旧值——主档更新经 fe_field_history 留痕）';
