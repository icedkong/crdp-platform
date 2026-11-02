-- E11-T01 / A-FLD-001、A-FLD-002、C-FLD-001：表单引擎字段元数据（租户库，tech-plan §6「元数据模型」）。
--   fe_form        表单定义：编码、用途、版本号、分类 / 分节（JSON，锚点键稳定，模板改版不变）。
--   fe_field       字段：稳定键 + 字段编码（库列名，导入导出与对外名称） + 显示名（两者分离，
--                  A-FLD-002：页面只显示「吸烟史」与选项「否 / 是」，数据库存编码 0 / 1）、
--                  分类、类型（components.md §10.1 FormField['type'] 同口径）、单位、硬 / 软范围、
--                  必填、条件显示与条件必填（E11-T03 求值）、敏感标记、存储方式（tech-plan 混合存储：
--                  json 数据 JSON 列 / column 实体列 / encrypted 加密列）、派生定义（E11-T05）、
--                  帮助说明、排序、状态（ENABLED/DISABLED，停用字段不进模板下发与校验）。
--   fe_option_set / fe_option  选项集与选项：编码（数据库存编码）+ 显示名 + 顺序 + 停用（不删除）+
--                  序数（「返流加重 ≥ 2 级」比较用）+ 互斥（「无」）+ 需补充说明（「其他」）+ 别名（导入匹配）。
--   字典是表单、校验、导入模板、导出表头、统计维度的唯一来源（A 规格「业务规则」）；新增字段 = 改字典
--   （迁移），不改页面（AGENTS.md 规则 8）。跨字段校验表 fe_rule（E11-T04）、字段级历史表
--   fe_field_history（E11-T06）随后续任务落地。
-- 演示种子：一个示例基线表单（全部虚构、带「示例」标记，E23-T01 由产品录入医院 A 的 266 个真实字段），
--   覆盖 A-FLD-002 的关键形态：显示名与编码分离（吸烟史 → 0=否 / 1=是）、数值带单位与范围、
--   敏感列存储、派生字段。
-- 另：医院端「字段字典」菜单（平台库 V35，plt_menu 37）授予租户库 TENANT_ADMIN（老库走本脚本；
--   全新库本脚本先于种子 V100 执行、插 0 行，由种子 V111 补授；生产由开通编排 ensureAdmin 授予）。

CREATE TABLE fe_form (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL COMMENT '表单编码（如 A_BASELINE、A_ANNUAL_FU、C_PROGRAM_FU、C_IMPLANT），本院唯一，创建后不可改',
  name VARCHAR(128) NOT NULL COMMENT '表单名称',
  usage_type VARCHAR(16) NOT NULL COMMENT '用途：BASELINE 基线 / FOLLOWUP 随访 / IMPLANT 植入 / REPORT 报告单字段集',
  current_version INT NOT NULL DEFAULT 1 COMMENT '当前模板版本号（发布机制随 E11-T02）',
  categories JSON NULL COMMENT '分类 / 分节定义 [{key,label,sortNo}]，key 为稳定锚点键（报告单位置、分类条依赖）',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_fe_form_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='表单定义（tech-plan §6 fe_form）';

CREATE TABLE fe_field (
  id BIGINT NOT NULL AUTO_INCREMENT,
  form_code VARCHAR(64) NOT NULL COMMENT '所属表单编码（fe_form.code）',
  field_key VARCHAR(32) NOT NULL COMMENT '稳定 ASCII 键（如 f019），表单内唯一，创建后不可改；JSON 数据键用它（tech-plan 混合存储）',
  field_code VARCHAR(64) NOT NULL COMMENT '字段编码（建议取库列名），表单内唯一，创建后不可改；导入 / 导出 / 对外名称',
  label VARCHAR(128) NOT NULL COMMENT '显示名（与编码分离，A-FLD-002：页面不显示「(否=0，是=1)」这类编码说明）',
  category_key VARCHAR(32) NOT NULL COMMENT '分类锚点键（fe_form.categories[].key）',
  type VARCHAR(16) NOT NULL COMMENT 'number/text/textarea/date/datetime/select/radio/multiselect/boolean/pair_number/derived（components.md §10.1）',
  unit VARCHAR(32) NULL COMMENT '单位显示，如 mm、ml、%、mmol/L',
  decimals INT NULL COMMENT '数值小数位（元数据规定，tech-plan §6 值编码）',
  hard_range JSON NULL COMMENT '取值校验范围（硬）{min,max}：超出不可保存；产品经迁移调整，租户不可改',
  soft_range JSON NULL COMMENT '提示范围（软）{min,max}：超出黄色提示可保存（A-FLD-003），租户管理员可调',
  option_set_code VARCHAR(64) NULL COMMENT '选项集编码（fe_option_set.code），单选 / 多选必填',
  required TINYINT NOT NULL DEFAULT 0 COMMENT '必填（条件必填 required_when 之外的基础必填）',
  visible_when JSON NULL COMMENT '条件显示条件树（E11-T03 求值；被隐藏字段提交时清空并写历史）',
  required_when JSON NULL COMMENT '条件必填条件树（E11-T03 求值）',
  derived_def JSON NULL COMMENT '派生定义 {fn,args}（E11-T05 内置函数库；派生字段只读）',
  sensitive_flag TINYINT NOT NULL DEFAULT 0 COMMENT '敏感标记：姓名、病案号、电话等为 1（字段策略与脱敏）；列名避开 MySQL 保留字 SENSITIVE',
  storage_type VARCHAR(16) NOT NULL DEFAULT 'json' COMMENT 'json=记录 data JSON 列 / column=实体表普通列 / encrypted=实体表加密列（tech-plan 混合存储）',
  help_text VARCHAR(500) NULL COMMENT '帮助说明（保留原表判定口径，如肾功能不全判定标准）',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序号，小者在前',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED 启用 / DISABLED 停用：停用不删除，不进模板下发与校验（字段可启停）',
  updated_by BIGINT NULL COMMENT '最近维护人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_fe_field_key (form_code, field_key),
  UNIQUE KEY uk_fe_field_code (form_code, field_code),
  KEY idx_fe_field_form (form_code, category_key, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字段字典（tech-plan §6 fe_field，A-FLD-001）';

CREATE TABLE fe_option_set (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL COMMENT '选项集编码，本院唯一，创建后不可改',
  name VARCHAR(128) NOT NULL COMMENT '名称（如「是否」「NYHA 分级」）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_fe_option_set_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='选项集（tech-plan §6 fe_option_set）';

CREATE TABLE fe_option (
  id BIGINT NOT NULL AUTO_INCREMENT,
  set_code VARCHAR(64) NOT NULL COMMENT '所属选项集编码（fe_option_set.code）',
  option_code INT NOT NULL COMMENT '选项编码：是否类 0=否 / 1=是；数据库存编码、页面显示显示名（A-FLD-002），集内唯一不可改',
  label VARCHAR(128) NOT NULL COMMENT '选项显示名（租户管理员可改）',
  ordinal INT NULL COMMENT '序数（如「返流加重 ≥ 2 级」按序数比较）',
  exclusive TINYINT NOT NULL DEFAULT 0 COMMENT '互斥项（如「无」）：多选选中时清空其他',
  needs_detail TINYINT NOT NULL DEFAULT 0 COMMENT '选中需补充说明（如「其他」）',
  aliases VARCHAR(255) NULL COMMENT '别名 CSV（导入表头匹配用，E11-T09）',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序号，小者在前',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED：停用不删除（已有数据的选项不能删）',
  updated_by BIGINT NULL COMMENT '最近维护人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_fe_option (set_code, option_code),
  KEY idx_fe_option_set (set_code, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='选项（tech-plan §6 fe_option）';

-- 演示种子（全部虚构，名称带「示例」标记；真实 266 字段随 E23-T01 录入）：
-- 示例基线表单，两个分类，覆盖各存储方式与 A-FLD-002 的「吸烟史」。
INSERT INTO fe_form (code, name, usage_type, current_version, categories) VALUES
  ('A_BASELINE', '基线档案（示例）', 'BASELINE', 1,
   JSON_ARRAY(JSON_OBJECT('key', 'basic_info', 'label', '病例基本信息', 'sortNo', 10),
              JSON_OBJECT('key', 'surgery', 'label', '手术信息', 'sortNo', 20)));

INSERT INTO fe_option_set (code, name) VALUES ('yesno', '是否（示例）');

INSERT INTO fe_option (set_code, option_code, label, sort_no) VALUES
  ('yesno', 0, '否', 10),
  ('yesno', 1, '是', 20);

INSERT INTO fe_field
  (form_code, field_key, field_code, label, category_key, type, sensitive_flag, storage_type,
   option_set_code, required, sort_no, help_text) VALUES
  ('A_BASELINE', 'f001', 'patient_name', '姓名', 'basic_info', 'text', 1, 'encrypted', NULL, 1, 10,
   '示例：敏感字段，实体表加密列存储，页面按字段策略脱敏'),
  ('A_BASELINE', 'f002', 'medical_record_no', '病案号', 'basic_info', 'text', 1, 'encrypted', NULL, 1, 20,
   '示例：敏感字段，盲索引查重（A-PAT-007）');

INSERT INTO fe_field
  (form_code, field_key, field_code, label, category_key, type, required, sort_no, help_text) VALUES
  ('A_BASELINE', 'f003', 'birth_date', '出生日期', 'basic_info', 'date', 1, 30,
   '示例：实体列存储（A-FLD-013），用于计算手术年龄');

INSERT INTO fe_field
  (form_code, field_key, field_code, label, category_key, type, option_set_code, required, sort_no, help_text) VALUES
  ('A_BASELINE', 'f010', 'smoking_history', '吸烟史', 'basic_info', 'radio', 'yesno', 1, 40,
   '示例（A-FLD-002）：原表列名「吸烟史(否=0，是=1)」——显示名与编码分离，页面显示「吸烟史」与选项「否 / 是」，数据库存 0 / 1');

INSERT INTO fe_field
  (form_code, field_key, field_code, label, category_key, type, unit, decimals,
   hard_range, soft_range, required, sort_no, help_text) VALUES
  ('A_BASELINE', 'f011', 'height_cm', '身高', 'basic_info', 'number', 'cm', 1,
   JSON_OBJECT('min', 50, 'max', 250), JSON_OBJECT('min', 130, 'max', 210), 0, 50,
   '示例：数值带单位与范围——软范围外黄色提示、硬范围外不可保存（A-FLD-003）');

INSERT INTO fe_field
  (form_code, field_key, field_code, label, category_key, type, required, sort_no) VALUES
  ('A_BASELINE', 'f020', 'surgery_date', '手术日期', 'surgery', 'date', 1, 10);

INSERT INTO fe_field
  (form_code, field_key, field_code, label, category_key, type, derived_def, required, sort_no, help_text) VALUES
  ('A_BASELINE', 'f030', 'postop_stay_days', '术后住院天数', 'surgery', 'derived',
   JSON_OBJECT('fn', 'DAYS_INCLUSIVE', 'args', JSON_ARRAY('f020', 'f021')), 0, 90,
   '示例：派生字段（出院日期 − 手术日期 + 1），前端灰底只读并显示公式提示（A-FLD-005，计算引擎随 E11-T05）');

-- 「字段字典」菜单（plt_menu 37，权限码 followup:field:edit，平台库 V35 新增）授予内置租户管理员角色。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 37
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 37
  );
