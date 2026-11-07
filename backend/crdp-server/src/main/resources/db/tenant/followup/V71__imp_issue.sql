-- E18-T03 / tech-plan §12④⑤：导入预检的校验问题表（批注版校验报告的数据面）。
--
-- 一次预检（staging → validation）跑完后的问题清单，供导入向导（E18-T06）分页展示与确认门控（E18-T05：
-- 有 ERROR 时 confirm → 40001）重读；重新校验先清空本作业的问题行。
--   * level：ERROR（阻断提交）/ WARN（重复、软范围、必填为空、Q36/Q37 人工复核等，历史导入口径只提示）；
--   * 坐标：sheet_name + row_no + col_no / col_letters 定位到单元格（批注版 Excel 与问题清单同口径，
--     tech-plan §12⑤「行号、列（含 Excel 列字母）、原值、原因、级别」）；无法定位列的问题 col_no / col_letters / header 为 NULL；
--   * message 只写非敏感文本（OQ-101③同口径）：敏感字段（姓名 / 病案号等）的问题不回显原值，
--     原值列不落库（问题接口按需从暂存密文取并脱敏展示，E18-T06）。

CREATE TABLE imp_issue (
  id BIGINT NOT NULL AUTO_INCREMENT,
  job_id BIGINT NOT NULL COMMENT '导入作业 imp_job.id',
  sheet_name VARCHAR(64) NOT NULL COMMENT '源工作表名（与 imp_row.sheet_name 同口径）',
  row_no INT NOT NULL COMMENT '源工作表行号（表头 = 1、数据从 2 起）',
  col_no INT NULL COMMENT '列号（0 起下标；无法定位列时 NULL）',
  col_letters VARCHAR(4) NULL COMMENT 'Excel 列字母（如 EF；无法定位列时 NULL）',
  header VARCHAR(128) NULL COMMENT '该列表头（字段显示名 / 原表列名；无法定位列时 NULL）',
  level VARCHAR(8) NOT NULL COMMENT 'ERROR 错误（阻断提交）/ WARN 警告（人工复核或按策略处理）',
  code VARCHAR(32) NOT NULL COMMENT '问题类型码（TYPE / HARD_RANGE / SOFT_RANGE / OPTION_MISS / OPTION_CODE_TEXT / BOOLEAN_01 / REQUIRED_EMPTY / DATE_OR_TYPE 见服务端枚举）',
  message VARCHAR(512) NOT NULL COMMENT '原因（非敏感文本，不回显敏感字段原值）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_imp_issue_job (job_id, level, row_no),
  KEY idx_imp_issue_sheet (job_id, sheet_name, row_no, col_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='导入校验问题（E18-T03；预检校验的行 / 列级问题清单，批注版报告与确认门控的数据源）';
