-- E11-T02 / A-NFR-002、C-NFR-002：表单模板版本快照 + JSON 数据记录（租户库，tech-plan §6「元数据模型」）。
--   fe_form_version        发布版本：publish 时把 fe_form.categories 与当时的启用字段固化成快照，
--                          fe_form.current_version 指向最新发布版；旧版本快照永久保留（验收「旧版本可读」）。
--   fe_form_version_field  版本内字段快照（下发口径全列）：停用字段不进新版本、旧版本快照里仍在——
--                          旧记录（data JSON 键 = 稳定键）按其保存时版本渲染，不受模板改版影响。
--                          选项（fe_option）不进快照：选项编码创建后不可改、停用不删除（V28），旧值永远
--                          可按编码解析；显示名与停用标记读取时现取（租户可调热点，见 OQ-63）。
--   fe_record              表单数据 JSON 记录：data / data_meta JSON（tech-plan §6「混合存储」——storage=json
--                          的字段进 data；敏感与系统绑定字段走实体列，E13+ 落 pt_/fu_ 表后经同一 FormService
--                          校验写入）、template_version 创建时固化、version 乐观锁（E25 草稿接口复用）。
-- 写入只能经 FormService（tech-plan §6「禁止绕过服务直接写 JSON」）：键必须在记录版本的启用字段内、
--   类型按值编码口径检查（单选存编码整数、多选存编码数组、数值存数字、日期 YYYY-MM-DD、
--   日期时间 YYYY-MM-DDTHH:mm、数值×2 存 {length,width} 对象）。
-- 既有表单（V28 演示种子）按 current_version 补 v1 快照：发布机制上线即有可按版本读取的模板。

CREATE TABLE fe_form_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  form_code VARCHAR(64) NOT NULL COMMENT '表单编码（fe_form.code）',
  version INT NOT NULL COMMENT '模板版本号（发布时 = fe_form.current_version + 1）；表单内唯一，发布后不可变',
  categories JSON NULL COMMENT '分类 / 分节定义快照（发布时的 fe_form.categories，锚点键稳定）',
  field_count INT NOT NULL DEFAULT 0 COMMENT '本版本字段数（发布时的启用字段数，审计与校对用）',
  published_by BIGINT NULL COMMENT '发布人 sys_user.id；V29 补录的历史版本为 NULL',
  published_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_fe_form_version (form_code, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='表单模板发布版本（快照，tech-plan §6 fe_form 版本号）';

CREATE TABLE fe_form_version_field (
  id BIGINT NOT NULL AUTO_INCREMENT,
  version_id BIGINT NOT NULL COMMENT '所属版本（fe_form_version.id）',
  form_code VARCHAR(64) NOT NULL COMMENT '表单编码（冗余，按表单 + 版本直查免回表）',
  version INT NOT NULL COMMENT '模板版本号（冗余，同上）',
  field_key VARCHAR(32) NOT NULL COMMENT '稳定 ASCII 键（如 f019），版本内唯一；JSON 数据键用它',
  field_code VARCHAR(64) NOT NULL COMMENT '字段编码（导入 / 导出 / 对外名称）',
  label VARCHAR(128) NOT NULL COMMENT '显示名（发布时快照；租户改显示名不改历史版本口径）',
  category_key VARCHAR(32) NOT NULL COMMENT '分类锚点键',
  type VARCHAR(16) NOT NULL COMMENT 'number/text/textarea/date/datetime/select/radio/multiselect/boolean/pair_number/derived',
  unit VARCHAR(32) NULL,
  decimals INT NULL,
  hard_range JSON NULL,
  soft_range JSON NULL,
  option_set_code VARCHAR(64) NULL,
  required TINYINT NOT NULL DEFAULT 0,
  visible_when JSON NULL,
  required_when JSON NULL,
  derived_def JSON NULL,
  sensitive_flag TINYINT NOT NULL DEFAULT 0,
  storage_type VARCHAR(16) NOT NULL DEFAULT 'json',
  help_text VARCHAR(500) NULL,
  sort_no INT NOT NULL DEFAULT 0,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_fe_form_version_field (version_id, field_key),
  KEY idx_fe_form_version_field (form_code, version, category_key, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='版本内字段快照（发布时的启用字段，E11-T02）';

CREATE TABLE fe_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  form_code VARCHAR(64) NOT NULL COMMENT '表单编码（fe_form.code）',
  template_version INT NOT NULL COMMENT '保存时的模板版本（创建时固化 = 当时 current_version）；记录按此版本渲染与校验',
  data JSON NULL COMMENT '字段值，键 = 稳定键 f0xx（storage=json 的字段；tech-plan §6 值编码）',
  data_meta JSON NULL COMMENT '字段附加信息：manual 手工录入 / note 备注 / fromReport 报告单带入（components.md §10.2）',
  version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号：保存带 baseVersion，不一致 40900（E25 草稿接口复用）',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT 草稿 / SUBMITTED 已提交（提交流转与校验随 E15）',
  created_by BIGINT NULL COMMENT '创建人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_fe_record_form (form_code, template_version, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='表单数据 JSON 记录（A-NFR-002 / C-NFR-002 JSON 文档存储）';

-- 既有表单按 current_version 补历史版本快照（V28 种子：A_BASELINE v1）。
INSERT INTO fe_form_version (form_code, version, categories, field_count, published_by)
SELECT fm.code, fm.current_version, fm.categories,
       (SELECT COUNT(*) FROM fe_field f
         WHERE f.form_code = fm.code AND f.status = 'ENABLED'),
       NULL
FROM fe_form fm;

INSERT INTO fe_form_version_field
  (version_id, form_code, version, field_key, field_code, label, category_key, type,
   unit, decimals, hard_range, soft_range, option_set_code, required,
   visible_when, required_when, derived_def, sensitive_flag, storage_type, help_text, sort_no)
SELECT v.id, f.form_code, v.version, f.field_key, f.field_code, f.label, f.category_key, f.type,
       f.unit, f.decimals, f.hard_range, f.soft_range, f.option_set_code, f.required,
       f.visible_when, f.required_when, f.derived_def, f.sensitive_flag, f.storage_type,
       f.help_text, f.sort_no
FROM fe_field f
JOIN fe_form fm ON fm.code = f.form_code
JOIN fe_form_version v ON v.form_code = fm.code AND v.version = fm.current_version
WHERE f.status = 'ENABLED';
