-- E23-T03 / A-ATT-013、A-ATT-018（tech-plan §10「rpt_mapping / rpt_mapping_item」；C-SCAN-005 同一机制）：
-- 报告单 → 结构化字段映射的配置表与医院 A 心脏超声预置（验收原文：10 类资料类型与映射可用；10 类随 E16-T01 V62 落地）。
--   rpt_mapping          映射头：来源类型、目标记录（BASELINE 基线按 phase 分时相 / FOLLOWUP_RECORD 随访记录 /
--                        C 程控记录 IMPLANT_RECORD 随 E21）、目标表单、绑定的指标模板、附带写入 JSON、启停。
--   rpt_mapping_item     映射项目：来源项目代码（指标模板 item_code 或固定 REPORT_DATE 报告时间）→ 目标字段键
--                        （fe_field.field_key，目标表单内），本项目写入时的附带写入 JSON。
-- 附带写入 JSON：[{"fieldKey":"f236","value":"1"}]（A-ATT-013「复查超声置为是」「同时填有无…返流」，值为字段
-- 字典取值文本，a_yesno 1 = 是）；「超声检查时间 = 报告时间」按 REPORT_DATE 项目行走（A 规格映射表第一行）。
-- 产品配置随包发布（与 V38 / V61 / V62 同口径，进入所有租户库；医院 B 是 A 的配置差异版、C 的程控报告单映射
-- 随 E23-T21 以本院预置处置，不写租户分支，AGENTS §5.7）。
-- 映射写入（预览、逐项确认覆盖、写入留痕与冲突提示）随 E16-T07 消费本配置；两侧修改同一数据、参数只保存在映射
-- 目标记录中（A-ATT-013），由该写入侧保证。本脚本只落「类型有了、名称有了、模板有了、映射配好了」的配置。

-- ---------------------------------------------------------------- 配置表

CREATE TABLE rpt_mapping (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL COMMENT '映射编码（ASCII，本院唯一，创建后不可改，如 ECHO_PREOP_LAST 心脏超声→基线·术前最后一次）',
  name VARCHAR(128) NOT NULL COMMENT '映射名称（页面显示，如「心脏超声 → 基线（术前最后一次）」）',
  type_code VARCHAR(32) NOT NULL COMMENT '来源资料类型（rpt_type.code，如 ECHO；该类型报告单保存时按映射写入结构化字段，A-ATT-013）',
  target_type VARCHAR(32) NOT NULL COMMENT '映射目标记录类型：BASELINE 基线（按 phase 分时相）/ FOLLOWUP_RECORD 随访记录（写入复查超声分组）/ IMPLANT_RECORD 程控记录（C，随 E21）',
  target_form_code VARCHAR(64) NOT NULL COMMENT '目标表单（fe_form.code，如 A_BASELINE / A_ANNUAL_FU；目标字段键在该表单字段字典内）',
  phase VARCHAR(32) NULL COMMENT '基线时相（A-ATT-013：PREOP_LAST 术前最后一次 / PREDISCHARGE_LAST 出院前最后一次；非基线为 NULL，映射到基线时上传侧须选时相）',
  indicator_tpl_id BIGINT NULL COMMENT '绑定的指标模板（rpt_indicator_tpl.id；心脏超声模板与超声字段一一对应，A-ATT-013；映射项目代码须在模板项目内）',
  extra_writes TEXT NULL COMMENT '映射级附带写入 JSON 数组 [{fieldKey,value}]：写入任一项目时一并写（如随访目标把「复查超声」f236 置为是）',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序号，小者在前',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED 启用 / DISABLED 停用：停用后该类型报告单不再写入映射字段（按院配置，A-ATT-018）',
  remark VARCHAR(500) NULL COMMENT '说明',
  updated_by BIGINT NULL COMMENT '最近维护人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpt_mapping_code (code),
  KEY idx_rpt_mapping_type (type_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报告单字段映射（tech-plan §10 rpt_mapping，A-ATT-013 / C-SCAN-005 同一机制）';

CREATE TABLE rpt_mapping_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  mapping_id BIGINT NOT NULL COMMENT '所属映射（rpt_mapping.id）',
  item_code VARCHAR(64) NOT NULL COMMENT '来源项目代码：指标模板项目（rpt_indicator_item.item_code，如 EF）或固定 REPORT_DATE（报告单的报告时间，A 规格映射表首行）',
  target_field_key VARCHAR(64) NOT NULL COMMENT '目标字段键（fe_field.field_key，须在目标表单字段字典内；A 规格映射表三列的目标字段）',
  extra_writes TEXT NULL COMMENT '本项目写入时附带写入 JSON 数组 [{fieldKey,value}]（如随访「二尖瓣返流程度」写入时同时把「有无二尖瓣返流」置为有）',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '顺序，小者在前（预览与写入时按此列出）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_rpt_mapping_item (mapping_id, item_code),
  KEY idx_rpt_mapping_item_mapping (mapping_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='报告单字段映射项目（tech-plan §10 rpt_mapping_item，项目代码 → 字段键）';

-- ---------------------------------------------------------------- 医院预置：心脏超声指标模板（A-ATT-012 / A-ATT-013）

-- 项目 = A 规格「心脏超声报告单映射」表的报告单项目列（与映射项目一一对应）；超声指标为测量值、
-- 无参考范围（↑ / ↓ 只用于检验类，A 规格验收），单位与目标字段字典一致。

INSERT INTO rpt_indicator_tpl (name, status, remark) VALUES
  ('心脏超声指标', 'ENABLED', '医院 A 预置（A-ATT-013）：心脏超声报告单项目，与超声字段映射一一对应（EF、心腔内径、容积、瓣膜返流、室壁瘤与血栓）');

INSERT INTO rpt_indicator_item (tpl_id, item_name, item_code, unit, ref_range, ref_range_male, ref_range_female, sort_no)
SELECT t.id, v.item_name, v.item_code, v.unit, NULL, NULL, NULL, v.sort_no
FROM rpt_indicator_tpl t
JOIN (SELECT '心脏超声指标' AS tpl_name, 'EF' AS item_name, 'EF' AS item_code, '%' AS unit, 10 AS sort_no
      UNION ALL SELECT '心脏超声指标', 'LVDd 舒张末径', 'LVDD', 'mm', 20
      UNION ALL SELECT '心脏超声指标', 'LVEDV 舒张末容积', 'LVEDV', 'ml', 30
      UNION ALL SELECT '心脏超声指标', 'LVDs 收缩末径', 'LVDS', 'mm', 40
      UNION ALL SELECT '心脏超声指标', 'LVESV 收缩末容积', 'LVESV', 'ml', 50
      UNION ALL SELECT '心脏超声指标', 'LA 前后径', 'LA_DIAMETER', 'mm', 60
      UNION ALL SELECT '心脏超声指标', 'LA 容积', 'LA_VOLUME', 'ml', 70
      UNION ALL SELECT '心脏超声指标', 'IVS 室间隔厚度', 'IVS_THICKNESS', 'mm', 80
      UNION ALL SELECT '心脏超声指标', 'RV 前后径', 'RV_DIAMETER', 'mm', 90
      UNION ALL SELECT '心脏超声指标', 'SV 左室每博量', 'SV', 'ml', 100
      UNION ALL SELECT '心脏超声指标', 'FS 左心室短轴缩短率', 'FS', '%', 110
      UNION ALL SELECT '心脏超声指标', 'CO 左室心排量', 'CO', 'L/min', 120
      UNION ALL SELECT '心脏超声指标', 'E/e''', 'E_E_PRIME', NULL, 130
      UNION ALL SELECT '心脏超声指标', '二尖瓣返流程度', 'MR_DEGREE', NULL, 140
      UNION ALL SELECT '心脏超声指标', '三尖瓣返流程度', 'TR_DEGREE', NULL, 150
      UNION ALL SELECT '心脏超声指标', '主动脉瓣返流程度', 'AR_DEGREE', NULL, 160
      UNION ALL SELECT '心脏超声指标', '室壁瘤大小', 'ANEURYSM_SIZE', 'mm', 170
      UNION ALL SELECT '心脏超声指标', '有无心室血栓', 'VENTRICULAR_THROMBUS', NULL, 180) v
  ON v.tpl_name = t.name;

-- V62 预置的心脏超声名称绑定该模板（A-ATT-011 名称可绑定模板；A-ATT-013 心脏超声指标与超声字段一一对应，
-- 添加心脏超声报告单时按模板带出项目，只需填结果）。

UPDATE rpt_name n
JOIN rpt_indicator_tpl t ON t.name = '心脏超声指标'
SET n.indicator_tpl_id = t.id
WHERE n.type_code = 'ECHO'
  AND n.indicator_tpl_id IS NULL;

-- ---------------------------------------------------------------- 医院预置：心脏超声映射（A 规格映射表三列，A-ATT-013）

-- 术前最后一次 / 出院前最后一次 → A_BASELINE「超声检查」分类；第 N 年随访 → A_ANNUAL_FU「复查超声」分组。
-- 出院前列无报告时间行（规格「—（无对应字段，只存于报告单）」）也无 IVS（规格「—」）。

INSERT INTO rpt_mapping (code, name, type_code, target_type, target_form_code, phase, indicator_tpl_id, extra_writes, sort_no, status, remark)
SELECT v.code, v.name, v.type_code, v.target_type, v.target_form_code, v.phase, t.id, v.extra_writes, v.sort_no, v.status, v.remark
FROM (SELECT 'ECHO_PREOP_LAST' AS code, '心脏超声 → 基线（术前最后一次）' AS name, 'ECHO' AS type_code, 'BASELINE' AS target_type, 'A_BASELINE' AS target_form_code, 'PREOP_LAST' AS phase, NULL AS extra_writes, 10 AS sort_no, 'ENABLED' AS status, '医院 A 预置（A-ATT-013）：写入基线「超声检查」分类的术前最后一次字段；报告时间不得晚于出院日期、基线锁定须先解锁由写入侧（E16-T07）校验' AS remark
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', '心脏超声 → 基线（出院前最后一次）', 'ECHO', 'BASELINE', 'A_BASELINE', 'PREDISCHARGE_LAST', NULL, 20, 'ENABLED', '医院 A 预置（A-ATT-013）：写入基线「超声检查」分类的出院前最后一次字段；无报告时间与 IVS 对应字段（规格「—」）'
      UNION ALL SELECT 'ECHO_FOLLOWUP', '心脏超声 → 随访记录（复查超声）', 'ECHO', 'FOLLOWUP_RECORD', 'A_ANNUAL_FU', NULL, '[{"fieldKey":"f236","value":"1"}]', 30, 'ENABLED', '医院 A 预置（A-ATT-013）：写入该年随访记录「复查超声」分组并把「复查超声」置为是、「超声检查时间」置为报告时间') v
JOIN rpt_indicator_tpl t ON t.name = '心脏超声指标';

INSERT INTO rpt_mapping_item (mapping_id, item_code, target_field_key, extra_writes, sort_no)
SELECT m.id, v.item_code, v.target_field_key, v.extra_writes, v.sort_no
FROM (SELECT 'ECHO_PREOP_LAST' AS mapping_code, 'REPORT_DATE' AS item_code, 'f079' AS target_field_key, NULL AS extra_writes, 10 AS sort_no
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'EF', 'f083', NULL, 20
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'LVDD', 'f084', NULL, 30
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'LVEDV', 'f085', NULL, 40
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'LVDS', 'f086', NULL, 50
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'LVESV', 'f087', NULL, 60
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'LA_DIAMETER', 'f080', NULL, 70
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'LA_VOLUME', 'f081', NULL, 80
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'IVS_THICKNESS', 'f082', NULL, 90
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'RV_DIAMETER', 'f092', NULL, 100
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'SV', 'f088', NULL, 110
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'FS', 'f089', NULL, 120
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'CO', 'f090', NULL, 130
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'E_E_PRIME', 'f091', NULL, 140
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'MR_DEGREE', 'f097', NULL, 150
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'TR_DEGREE', 'f098', NULL, 160
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'AR_DEGREE', 'f099', NULL, 170
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'ANEURYSM_SIZE', 'f093', NULL, 180
      UNION ALL SELECT 'ECHO_PREOP_LAST', 'VENTRICULAR_THROMBUS', 'f094', NULL, 190
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'EF', 'f102', NULL, 10
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'LVDD', 'f103', NULL, 20
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'LVEDV', 'f104', NULL, 30
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'LVDS', 'f105', NULL, 40
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'LVESV', 'f106', NULL, 50
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'LA_DIAMETER', 'f100', NULL, 60
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'LA_VOLUME', 'f101', NULL, 70
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'RV_DIAMETER', 'f107', NULL, 80
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'SV', 'f108', NULL, 90
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'FS', 'f109', NULL, 100
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'CO', 'f110', NULL, 110
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'E_E_PRIME', 'f111', NULL, 120
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'MR_DEGREE', 'f114', NULL, 130
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'TR_DEGREE', 'f115', NULL, 140
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'AR_DEGREE', 'f116', NULL, 150
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'ANEURYSM_SIZE', 'f112', NULL, 160
      UNION ALL SELECT 'ECHO_PREDISCHARGE_LAST', 'VENTRICULAR_THROMBUS', 'f113', NULL, 170
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'REPORT_DATE', 'f237', NULL, 10
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'EF', 'f248', NULL, 20
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'LVDD', 'f249', NULL, 30
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'LVEDV', 'f250', NULL, 40
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'LVDS', 'f251', NULL, 50
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'LVESV', 'f252', NULL, 60
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'LA_DIAMETER', 'f245', NULL, 70
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'LA_VOLUME', 'f246', NULL, 80
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'IVS_THICKNESS', 'f247', NULL, 90
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'RV_DIAMETER', 'f244', NULL, 100
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'SV', 'f253', NULL, 110
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'FS', 'f254', NULL, 120
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'CO', 'f255', NULL, 130
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'E_E_PRIME', 'f256', NULL, 140
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'MR_DEGREE', 'f239', '[{"fieldKey":"f238","value":"1"}]', 150
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'TR_DEGREE', 'f243', '[{"fieldKey":"f242","value":"1"}]', 160
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'AR_DEGREE', 'f241', '[{"fieldKey":"f240","value":"1"}]', 170
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'ANEURYSM_SIZE', 'f257', NULL, 180
      UNION ALL SELECT 'ECHO_FOLLOWUP', 'VENTRICULAR_THROMBUS', 'f258', NULL, 190) v
JOIN rpt_mapping m ON m.code = v.mapping_code;
