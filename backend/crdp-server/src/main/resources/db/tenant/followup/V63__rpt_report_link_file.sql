-- E16-T02 / A-ATT-001、A-ATT-002；C-SCAN-001、C-SCAN-010：报告单运行时表（docs/api/report.md §2）。
--   rpt_report            报告单：类型 / 名称快照、报告时间、所见与结论、备注、类型专属字段值
--                         （field_values JSON，按该类型 REPORT 表单的写入闸门校验，OQ-63① 复用口）、
--                         电子化状态（C-SCAN-011，A 为 NULL，消费侧随 E21）、乐观锁 version（编辑随后续任务）。
--   rpt_report_link       位置（必填、可多选，A-ATT-002 / C-SCAN-010）：位置粒度三种——基线某分类
--                         （link_type=baseline + anchor_type=category）、某条随访记录整条
--                         （link_type=followup_record + anchor_type=NULL）、某条随访记录中的某分组
--                         （anchor_type=section）；不细到字段。C 的植入记录锚点（implant_record）
--                         随 E21-T32 开放。同一报告单同一位置只一行（服务层去重；锚点按模板版本固化
--                         template_version，模板改版退回 / 校验随 E16-T13 / T15）。
--   rpt_report_file       附件：sys_file 台账关联（数量上限 ≤ 平台参数 file.upload.max.files.per.report，
--                         A 默认 10），上传本身走 E10 的 POST /files（本表只挂 fileId）。
--   rpt_indicator_value   指标结果快照（A-ATT-012：项目、结果、单位、参考范围、↑/↓ 标记）：
--                         模板后续修改不联动已存报告单（E16-T01 口径），故逐行存显示快照。
-- 四张表都在 ControlledTables（E07-T01 已登记），查询一律 @DataScope(resource="patient")。
-- 软删除列（删除原因 / 删除人 / 删除时间）随 E16-T09 落地，本脚本先建 status 列。

CREATE TABLE rpt_report (
  id BIGINT NOT NULL AUTO_INCREMENT,
  patient_id BIGINT NOT NULL COMMENT '患者 pt_patient.id（数据范围 EXISTS 锚点，ControlledTables）',
  type_code VARCHAR(32) NOT NULL COMMENT '资料类型 rpt_type.code（快照；类型停用不影响已存报告单）',
  type_name VARCHAR(128) NOT NULL COMMENT '类型名称快照（列表 / 详情显示用）',
  name_id BIGINT NULL COMMENT '名称字典 rpt_name.id（类型要求名称必填时非空；名称删除不联动，OQ-95②）',
  name_snapshot VARCHAR(128) NULL COMMENT '名称快照（如「经胸超声心动图」；字典删除后报告单仍可显示）',
  report_date DATE NOT NULL COMMENT '报告时间（报告上的检查或签署日期；按时间树归档 A-ATT-010）',
  findings TEXT NULL COMMENT '所见（按类型显示，如超声所见 / 检查所见）',
  conclusion TEXT NULL COMMENT '结论（按类型显示，如造影结论 / 心电图诊断）',
  note VARCHAR(500) NULL COMMENT '备注',
  field_values JSON NULL COMMENT '类型专属字段值（键 = 该类型 REPORT 表单稳定键；写入口走 FormService 写入闸门，E15-T07 复用口）',
  digitize_status VARCHAR(16) NULL COMMENT '电子化状态（C-SCAN-011：PENDING / NO_ENTRY / DONE；创建时取名称覆盖 → 类型默认；A 不启用、恒 NULL）',
  status VARCHAR(16) NOT NULL DEFAULT 'NORMAL' COMMENT 'NORMAL 正常 / DELETED 已删除（软删除与原因列随 E16-T09）',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号（编辑 / 修改位置随 E16-T13）',
  created_by BIGINT NULL COMMENT '上传人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_rpt_report_patient (patient_id, report_date),
  KEY idx_rpt_report_type (patient_id, type_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报告单（tech-plan §10 rpt_report，A-ATT-001 / C-SCAN-001）';

CREATE TABLE rpt_report_link (
  id BIGINT NOT NULL AUTO_INCREMENT,
  report_id BIGINT NOT NULL COMMENT '报告单 rpt_report.id',
  patient_id BIGINT NOT NULL COMMENT '冗余患者 id（同一患者位置标签联查免一次 join；与 rpt_report.patient_id 一致，服务层保证）',
  link_type VARCHAR(24) NOT NULL COMMENT 'baseline 基线分类 / followup_record 随访记录（C 的 implant_record 随 E21-T32）',
  link_record_id BIGINT NULL COMMENT '随访记录 fu_record.id（baseline 为 NULL）',
  anchor_type VARCHAR(16) NULL COMMENT 'category 基线分类 / section 随访分组 / NULL 整条记录（不细到字段，A-ATT-002）',
  anchor_key VARCHAR(64) NULL COMMENT '分类 / 分组稳定键（fe_form 模板分类 key；anchor_type=NULL 时为 NULL）',
  template_version INT NOT NULL COMMENT '锚点所属模板版本（baseline=基线表单当前发布版；followup_record=该记录固化版本，OQ-63②）',
  adjusted TINYINT NOT NULL DEFAULT 0 COMMENT '模板改版后位置退回标记（「原位置已调整」提示，A-ATT-021；维护随 E16-T15）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpt_report_link (report_id, link_type, link_record_id, anchor_type, anchor_key),
  KEY idx_rpt_report_link_patient (patient_id, link_type),
  KEY idx_rpt_report_link_record (link_record_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报告单位置（OQ-02 定名 rpt_report_link；A-ATT-002 / C-SCAN-010）';

CREATE TABLE rpt_report_file (
  id BIGINT NOT NULL AUTO_INCREMENT,
  report_id BIGINT NOT NULL COMMENT '报告单 rpt_report.id',
  patient_id BIGINT NOT NULL COMMENT '冗余患者 id（ControlledTables 范围改写按本列 EXISTS，与 rpt_report.patient_id 一致，服务层保证）',
  file_id BIGINT NOT NULL COMMENT '文件台账 sys_file.id（上传走 POST /files，此处只挂 id；状态 ATTACHED）',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '顺序，小者在前',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpt_report_file (report_id, file_id),
  KEY idx_rpt_report_file_file (file_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报告单附件（A-ATT-004 / C-SCAN-006；数量上限保存时校验）';

CREATE TABLE rpt_indicator_value (
  id BIGINT NOT NULL AUTO_INCREMENT,
  report_id BIGINT NOT NULL COMMENT '报告单 rpt_report.id',
  patient_id BIGINT NOT NULL COMMENT '冗余患者 id（ControlledTables 范围改写按本列 EXISTS，与 rpt_report.patient_id 一致，服务层保证）',
  item_code VARCHAR(64) NOT NULL COMMENT '项目代码（来自名称绑定模板 rpt_indicator_item.item_code）',
  item_name VARCHAR(128) NOT NULL COMMENT '项目名称快照',
  unit VARCHAR(32) NULL COMMENT '单位快照',
  ref_range VARCHAR(64) NULL COMMENT '参考范围显示快照（可按性别区分时前端已按患者性别选定后提交）',
  result VARCHAR(128) NULL COMMENT '结果（填写值；未填不落行或落空行由服务层决定——只存已填项目）',
  flag VARCHAR(8) NULL COMMENT 'UP 高于参考范围 ↑ / DOWN 低于参考范围 ↓（服务端按 ref_range 与数值结果计算；解析不出范围则 NULL）',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '顺序（模板项目顺序快照）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpt_indicator_value (report_id, item_code),
  KEY idx_rpt_indicator_value_report (report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报告单指标结果快照（A-ATT-012；模板修改不联动已存报告单）';
