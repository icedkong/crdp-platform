-- E16-T01 / A-ATT-003、A-ATT-011、A-ATT-012、A-ATT-018；C-SCAN-002、C-SCAN-003、C-SCAN-004：
-- 报告单 / 资料的租户配置（平台通用能力、按院配置，tech-plan §10「配置与数据表」）。
--   rpt_type             资料类型：编码、名称、字段集（引用 fe_form，usage_type = REPORT）、
--                        名称是否必填（C-SCAN-003：检查 / 检验类必填、其他选填；A 全部必填）、
--                        默认电子化状态（C-SCAN-011，A 不启用、为 NULL）、排序、启停。
--   rpt_name             名称字典：所属类型、名称、绑定的指标模板、电子化状态覆盖（C）、排序、启停。
--   rpt_indicator_tpl    指标模板：名称（本院唯一）、启停。
--   rpt_indicator_item   模板项目：项目名称、代码、参考范围（可按性别区分）、单位、顺序。
-- 类型可增减、停用、排序（A-ATT-003）；同名报告单可多次添加、按报告时间区分（A-ATT-011），
-- 修改只影响之后新建的报告单（C 规格业务规则）。
-- 产品配置随包发布（与 V38 / V61 同口径，进入所有租户库；医院 B 是 A 的配置差异版、C 的 10 类
-- 随 E23 以本院预置与停用处置，不写租户分支，AGENTS §5.7）。
-- 另：医院端「报告单设置」菜单（平台库 V53，plt_menu 39）授予租户库 TENANT_ADMIN（老库走本脚本；
--   全新库本脚本先于种子 V100 执行、插 0 行，由种子 V124 补授；生产由开通编排 ensureAdmin 授予）。

-- ---------------------------------------------------------------- 配置表

CREATE TABLE rpt_type (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(32) NOT NULL COMMENT '类型编码（ASCII，本院唯一，创建后不可改；报告单与名称字典都存编码，如 ECHO 心脏超声、CALL 通话记录）',
  name VARCHAR(128) NOT NULL COMMENT '类型名称（页面显示，如「心脏超声」「胸片 / CT」）',
  form_code VARCHAR(64) NOT NULL COMMENT '字段集（fe_form.code，usage_type = REPORT；选类型后表单随之变化，A-ATT-003 / C-SCAN-002）',
  name_required TINYINT NOT NULL DEFAULT 1 COMMENT '名称是否必填（C-SCAN-003：检查、检验类必填，其他类型选填；A 医院全部必填）',
  default_digitize_status VARCHAR(16) NULL COMMENT '默认电子化状态（C-SCAN-011：PENDING 待录入 / NO_ENTRY 无需录入；A 医院不启用电子化状态、为 NULL）',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序号，小者在前',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED 启用 / DISABLED 停用：停用不删除，添加报告单不再可选',
  remark VARCHAR(500) NULL COMMENT '说明',
  updated_by BIGINT NULL COMMENT '最近维护人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpt_type_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='资料类型（tech-plan §10 rpt_type，A-ATT-003 / C-SCAN-002）';

CREATE TABLE rpt_name (
  id BIGINT NOT NULL AUTO_INCREMENT,
  type_code VARCHAR(32) NOT NULL COMMENT '所属类型编码（rpt_type.code）',
  name VARCHAR(128) NOT NULL COMMENT '名称（如心脏超声下「经胸超声心动图」、检验下「血常规」；同类型下唯一；同名报告单可多次添加，按报告时间区分 A-ATT-011）',
  indicator_tpl_id BIGINT NULL COMMENT '绑定的指标模板（rpt_indicator_tpl.id；A-ATT-012：选名称自动带出项目，只需填结果）',
  digitize_status_override VARCHAR(16) NULL COMMENT '名称覆盖类型默认电子化状态（C-SCAN-011「名称可覆盖」；A 为 NULL）',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序号，小者在前',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED / DISABLED：停用不删除，下拉不再出现',
  updated_by BIGINT NULL COMMENT '最近维护人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpt_name (type_code, name),
  KEY idx_rpt_name_tpl (indicator_tpl_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='名称字典（tech-plan §10 rpt_name，A-ATT-011 / C-SCAN-003）';

CREATE TABLE rpt_indicator_tpl (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL COMMENT '模板名称（本院唯一，如「血常规」）',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED / DISABLED：停用不删除，停用后新报告单不再带出项目',
  remark VARCHAR(500) NULL COMMENT '说明',
  updated_by BIGINT NULL COMMENT '最近维护人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpt_indicator_tpl_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='指标模板（tech-plan §10 rpt_indicator_tpl，A-ATT-012 / C-SCAN-004）';

CREATE TABLE rpt_indicator_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tpl_id BIGINT NOT NULL COMMENT '所属模板（rpt_indicator_tpl.id）',
  item_name VARCHAR(128) NOT NULL COMMENT '项目名称（如「白细胞」「N 末端 B 型利钠肽原」）',
  item_code VARCHAR(64) NOT NULL COMMENT '项目代码（如 WBC、NT-proBNP）',
  unit VARCHAR(32) NULL COMMENT '单位（如 10^9/L、g/L、mmol/L）',
  ref_range VARCHAR(64) NULL COMMENT '参考范围显示（如 3.5–9.5、≤ 3.4、＜ 125）',
  ref_range_male VARCHAR(64) NULL COMMENT '男性参考范围（可按性别区分，tech-plan §10）',
  ref_range_female VARCHAR(64) NULL COMMENT '女性参考范围（可按性别区分，tech-plan §10）',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '顺序，小者在前（模板按顺序列出项目）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpt_indicator_item (tpl_id, item_code),
  KEY idx_rpt_indicator_item_tpl (tpl_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='指标模板项目（tech-plan §10 rpt_indicator_item，A-ATT-012 / C-SCAN-004）';

-- ---------------------------------------------------------------- 医院预置：按类型字段集（fe_form，usage_type = REPORT）

-- 每类一个 REPORT 表单（tech-plan §10「字段集（引用 fe_form）」）：类型决定添加报告单对话框里
-- 除公共部分（名称、位置、附件、指标表）之外的专属字段，字段清单 = A 规格「关键字段 · 预置资料类型
-- （A 医院）」的「主要字段」列（超声指标走映射 E16-T07、指标表走名称绑定的模板，不设字段）。

INSERT INTO fe_form (code, name, usage_type, current_version, categories, status) VALUES
  ('RPT_ECHO', '心脏超声报告单', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_ANGIO', '冠脉造影报告单', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_ECG', '心电图报告单', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_CHEST', '胸片 / CT 报告单', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_LAB', '检验报告单', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_DISCHARGE', '出院小结', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_OPERATION', '手术记录', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_CONSENT', '知情同意书', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_CALL', '通话记录', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED'),
  ('RPT_OTHER', '其他资料', 'REPORT', 1, '[{"key":"main","label":"报告内容","sortNo":10}]', 'ENABLED');

INSERT INTO fe_field (form_code, field_key, field_code, label, category_key, type, required, sort_no, help_text) VALUES
  ('RPT_ECHO', 'f001', 'report_date', '报告时间', 'main', 'date', 1, 10, '报告上的检查日期'),
  ('RPT_ECHO', 'f002', 'findings', '超声所见', 'main', 'textarea', 0, 20, NULL),
  ('RPT_ECHO', 'f003', 'conclusion', '超声结论', 'main', 'textarea', 0, 30, NULL),
  ('RPT_ANGIO', 'f001', 'report_date', '报告时间', 'main', 'date', 1, 10, '报告上的检查日期'),
  ('RPT_ANGIO', 'f002', 'conclusion', '造影结论', 'main', 'textarea', 0, 20, NULL),
  ('RPT_ECG', 'f001', 'report_date', '报告时间', 'main', 'date', 1, 10, '报告上的检查日期'),
  ('RPT_ECG', 'f002', 'conclusion', '心电图诊断', 'main', 'textarea', 0, 20, NULL),
  ('RPT_CHEST', 'f001', 'report_date', '报告时间', 'main', 'date', 1, 10, '报告上的检查日期'),
  ('RPT_CHEST', 'f002', 'findings', '检查所见', 'main', 'textarea', 0, 20, NULL),
  ('RPT_CHEST', 'f003', 'conclusion', '检查诊断', 'main', 'textarea', 0, 30, NULL),
  ('RPT_LAB', 'f001', 'report_date', '报告时间', 'main', 'date', 1, 10, '报告上的检验日期；指标行由名称绑定的模板带出（A-ATT-012）'),
  ('RPT_DISCHARGE', 'f001', 'report_date', '报告时间', 'main', 'date', 1, 10, '出院小结的签署日期'),
  ('RPT_OPERATION', 'f001', 'surgery_date', '手术日期', 'main', 'date', 1, 10, '手术记录以手术日期归档（A 规格预置资料类型表）'),
  ('RPT_CONSENT', 'f001', 'sign_date', '签署日期', 'main', 'date', 1, 10, '知情同意书以签署日期归档（A 规格预置资料类型表）'),
  ('RPT_CALL', 'f001', 'call_time', '通话时间', 'main', 'datetime', 1, 10, '通话发生时间'),
  ('RPT_CALL', 'f002', 'call_note', '通话说明', 'main', 'textarea', 0, 20, NULL),
  ('RPT_OTHER', 'f001', 'report_date', '报告时间', 'main', 'date', 1, 10, '报告上的检查或签署日期'),
  ('RPT_OTHER', 'f002', 'note', '备注', 'main', 'textarea', 0, 20, NULL);

-- ---------------------------------------------------------------- 医院 A 预置：10 类资料类型（A-ATT-003「A 医院预置 10 类」）

INSERT INTO rpt_type (code, name, form_code, name_required, default_digitize_status, sort_no, status, remark) VALUES
  ('ECHO', '心脏超声', 'RPT_ECHO', 1, NULL, 10, 'ENABLED', '医院 A 预置（A-ATT-003）：指标可映射写入超声字段（A-ATT-013，映射配置随 E16-T07）'),
  ('ANGIO', '冠脉造影', 'RPT_ANGIO', 1, NULL, 20, 'ENABLED', '医院 A 预置（A-ATT-003）'),
  ('ECG', '心电图', 'RPT_ECG', 1, NULL, 30, 'ENABLED', '医院 A 预置（A-ATT-003）'),
  ('CHEST_IMAGING', '胸片 / CT', 'RPT_CHEST', 1, NULL, 40, 'ENABLED', '医院 A 预置（A-ATT-003）'),
  ('LAB', '检验', 'RPT_LAB', 1, NULL, 50, 'ENABLED', '医院 A 预置（A-ATT-003）：名称绑定指标模板（A-ATT-012）'),
  ('DISCHARGE_SUMMARY', '出院小结', 'RPT_DISCHARGE', 1, NULL, 60, 'ENABLED', '医院 A 预置（A-ATT-003）'),
  ('OPERATION_RECORD', '手术记录', 'RPT_OPERATION', 1, NULL, 70, 'ENABLED', '医院 A 预置（A-ATT-003）：以手术日期归档'),
  ('CONSENT', '知情同意书', 'RPT_CONSENT', 1, NULL, 80, 'ENABLED', '医院 A 预置（A-ATT-003）：以签署日期归档'),
  ('CALL', '通话记录', 'RPT_CALL', 1, NULL, 90, 'ENABLED', '医院 A 预置（A-ATT-003）：与沟通记录录音同源（A-ATT-017）'),
  ('OTHER', '其他', 'RPT_OTHER', 1, NULL, 100, 'ENABLED', '医院 A 预置（A-ATT-003）');

-- ---------------------------------------------------------------- 医院预置：指标模板（A 规格「检验指标模板示例」，参考范围为示例、以本院检验科为准）

INSERT INTO rpt_indicator_tpl (name, status, remark) VALUES
  ('血常规', 'ENABLED', '医院 A 预置（A-ATT-012 示例）：参考范围为示例，以本院检验科为准'),
  ('肝肾功能', 'ENABLED', '医院 A 预置（A-ATT-012 示例）：参考范围为示例，以本院检验科为准'),
  ('血脂', 'ENABLED', '医院 A 预置（A-ATT-012 示例）：参考范围为示例，以本院检验科为准'),
  ('NT-proBNP', 'ENABLED', '医院 A 预置（A-ATT-012 示例）：参考范围为示例，以本院检验科为准'),
  ('心肌损伤标志物', 'ENABLED', '医院 A 预置（A-ATT-012 示例）：参考范围为示例，以本院检验科为准');

INSERT INTO rpt_indicator_item (tpl_id, item_name, item_code, unit, ref_range, ref_range_male, ref_range_female, sort_no)
SELECT t.id, v.item_name, v.item_code, v.unit, v.ref_range, v.ref_range_male, v.ref_range_female, v.sort_no
FROM rpt_indicator_tpl t
JOIN (SELECT '血常规' AS tpl_name, '白细胞' AS item_name, 'WBC' AS item_code, '10^9/L' AS unit, '3.5–9.5' AS ref_range, NULL AS ref_range_male, NULL AS ref_range_female, 10 AS sort_no
      UNION ALL SELECT '血常规', '血红蛋白', 'HGB', 'g/L', NULL, '130–175', '115–150', 20
      UNION ALL SELECT '血常规', '血小板', 'PLT', '10^9/L', '125–350', NULL, NULL, 30
      UNION ALL SELECT '肝肾功能', '肌酐', 'Cr', 'μmol/L', NULL, '57–111', '41–81', 10
      UNION ALL SELECT '肝肾功能', '尿酸', 'UA', 'μmol/L', '208–428', NULL, NULL, 20
      UNION ALL SELECT '血脂', '低密度脂蛋白胆固醇', 'LDL-C', 'mmol/L', '≤ 3.4', NULL, NULL, 10
      UNION ALL SELECT '血脂', '甘油三酯', 'TG', 'mmol/L', '≤ 1.7', NULL, NULL, 20
      UNION ALL SELECT 'NT-proBNP', 'N 末端 B 型利钠肽原', 'NT-proBNP', 'pg/mL', '＜ 125', NULL, NULL, 10
      UNION ALL SELECT '心肌损伤标志物', '高敏肌钙蛋白 I', 'hs-cTnI', 'ng/mL', '≤ 0.04', NULL, NULL, 10) v
  ON v.tpl_name = t.name;

-- ---------------------------------------------------------------- 医院预置：名称字典（A 规格「预置资料类型」表的「名称字典示例」列）

INSERT INTO rpt_name (type_code, name, indicator_tpl_id, digitize_status_override, sort_no, status)
SELECT v.type_code, v.name, t.id, NULL, v.sort_no, 'ENABLED'
FROM (SELECT 'ECHO' AS type_code, '经胸超声心动图' AS name, 10 AS sort_no
      UNION ALL SELECT 'ECHO', '床旁超声', 20
      UNION ALL SELECT 'ECHO', '经食道超声', 30
      UNION ALL SELECT 'ANGIO', '冠脉造影', 10
      UNION ALL SELECT 'ANGIO', '冠脉 CTA', 20
      UNION ALL SELECT 'ECG', '常规心电图', 10
      UNION ALL SELECT 'ECG', '动态心电图（Holter）', 20
      UNION ALL SELECT 'CHEST_IMAGING', '胸部正位片', 10
      UNION ALL SELECT 'CHEST_IMAGING', '胸部 CT', 20
      UNION ALL SELECT 'LAB', '血常规', 10
      UNION ALL SELECT 'LAB', '肝肾功能', 20
      UNION ALL SELECT 'LAB', '血脂', 30
      UNION ALL SELECT 'LAB', '血糖与糖化血红蛋白', 40
      UNION ALL SELECT 'LAB', 'NT-proBNP', 50
      UNION ALL SELECT 'LAB', '心肌损伤标志物', 60
      UNION ALL SELECT 'DISCHARGE_SUMMARY', '出院小结', 10
      UNION ALL SELECT 'OPERATION_RECORD', '手术记录', 10
      UNION ALL SELECT 'OPERATION_RECORD', '麻醉记录', 20
      UNION ALL SELECT 'CONSENT', '手术知情同意书', 10
      UNION ALL SELECT 'CONSENT', '随访知情同意书', 20
      UNION ALL SELECT 'CALL', '随访电话录音', 10) v
LEFT JOIN rpt_indicator_tpl t
  ON t.name = v.name
 AND v.type_code = 'LAB'
 AND v.name IN ('血常规', '肝肾功能', '血脂', 'NT-proBNP', '心肌损伤标志物');

-- 「其他」类型不预置名称（A 规格预置资料类型表：名称字典示例「—」）。

-- 「报告单设置」菜单（plt_menu 39，权限码 followup:scan:config，平台库 V53 新增）授予内置租户管理员角色。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 39
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 39
  );
