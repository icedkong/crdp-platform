-- E18-T02 / tech-plan §12③：患者历史数据导入的作业表与暂存行表（流式解析、大文件不占满内存）。
--
-- imp_job：一次上传一个作业（状态机 UPLOADED → PRECHECKING → PRECHECKED/PRECHECK_FAILED；IMPORTING 及之后随 E18-T05）。
--   * 同一租户同一时间只允许一个导入作业运行（tech-plan §12）：活跃状态（UPLOADED / PRECHECKING / IMPORTING）有行时
--     新作业 40901，由应用层建作业时检查（导入频率低、且上传端点串行派发，检查-插入足够）；
--   * storage_key 指向原始文件的对象存储键（按租户加密存储；上传端点随 E18-T06 接入）；
--   * staged_rows 按批更新进度（写暂存行的批次粒度），预检完成的行数以此为准。
-- imp_row：源工作表逐行暂存（tech-plan §12「每行写入暂存表 imp_row：行号、解析结果 JSON、问题列表」）。
--   * row_type：WIDE 版面一行即一位患者（PATIENT）；LONG 版面基线行（BASELINE）与随访行（FOLLOWUP）分存，
--     跨行「病案号 + 随访年度」关联与病案号查重（按盲索引）属校验阶段（E18-T03），不在此合并——
--     流式逐行入库不需要整本入内存；
--   * payload_cipher：整行解析结果的密文（CryptoService，purpose=imp-row-payload）——行内含姓名 / 病案号等
--     敏感明文，按「敏感字段不落明文列 / 不进明文 JSON」的既有口径（followup V37 pt_patient 注释）整体加密；
--   * mrn_blind：病案号盲索引，purpose 与 pt_patient.mrn_blind 相同（pt-patient-mrn），可与其直接比对查重；
--     病案号为空的行保持 NULL（问题列表记「病案号为空」）；
--   * problems：解析阶段已知的行级问题（非敏感文本：随访年度解析不了、病案号为空），列 / 表头级问题在
--     imp_job.layout_problems（整体报错）。

CREATE TABLE imp_job (
  id BIGINT NOT NULL AUTO_INCREMENT,
  file_name VARCHAR(255) NOT NULL COMMENT '上传原始文件名',
  file_size BIGINT NOT NULL COMMENT '文件字节数',
  file_sha256 VARCHAR(128) NULL COMMENT '文件 SHA-256（上传时计算，经 DigestService）',
  storage_key VARCHAR(255) NULL COMMENT '原始文件对象存储键（按租户加密存储；上传端点随 E18-T06 接入）',
  layout VARCHAR(8) NULL COMMENT '识别出的版面：WIDE / LONG（识别前 NULL）',
  status VARCHAR(24) NOT NULL COMMENT 'UPLOADED 已上传 / PRECHECKING 预检中 / PRECHECKED 预检完成 / PRECHECK_FAILED 预检失败（IMPORTING / PARTIAL_FAILED / DONE / CANCELLED 随 E18-T05）',
  options_json VARCHAR(1024) NULL COMMENT '导入选项（重复患者 UPDATE/SKIP 等，确认提交时固化；E18-T05）',
  staged_rows BIGINT NOT NULL DEFAULT 0 COMMENT '已暂存行数（写库批次粒度更新进度）',
  layout_problems JSON NULL COMMENT '版面 / 表头整体错误清单（预检失败时；含 Excel 列字母）',
  fail_reason VARCHAR(512) NULL COMMENT '预检 / 后续阶段失败原因（非敏感摘要）',
  operator_id BIGINT NOT NULL COMMENT '操作人 sys_user.id',
  operator_name VARCHAR(64) NOT NULL COMMENT '操作人账号',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_imp_job_status (status, updated_at),
  KEY idx_imp_job_operator (operator_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='导入作业（患者历史数据导入 tech-plan §12；一次上传一行）';

CREATE TABLE imp_row (
  id BIGINT NOT NULL AUTO_INCREMENT,
  job_id BIGINT NOT NULL COMMENT '导入作业 imp_job.id',
  sheet_name VARCHAR(64) NOT NULL COMMENT '源工作表名（宽表为该表名；长表为「基线」/「随访记录」）',
  row_no INT NOT NULL COMMENT '源工作表行号（表头 = 1、数据从 2 起；批注版报告引用）',
  row_type VARCHAR(16) NOT NULL COMMENT 'PATIENT 宽表患者行 / BASELINE 长表基线行 / FOLLOWUP 长表随访行',
  mrn_blind VARCHAR(128) NULL COMMENT '病案号盲索引（purpose 与 pt_patient.mrn_blind 相同，可直接比对查重与关联；病案号为空 NULL）',
  payload_cipher MEDIUMTEXT NOT NULL COMMENT '整行解析结果密文（JSON 含单元格原文与行列坐标，供 E18-T03 批注校验；整体加密，明文不落列）',
  problems JSON NULL COMMENT '解析阶段行级问题（非敏感文本：随访年度解析不了、病案号为空）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_imp_row_job (job_id, row_no),
  KEY idx_imp_row_mrn (job_id, mrn_blind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='导入暂存行（流式解析逐行写入 tech-plan §12③；E18-T03 校验报告的数据源）';
