-- E11-T08 / A-FLD-012、C-FLD-002：可配置业务字典——「患者类型」「术者」为平台预置字典（tech-plan
-- §6「cfg_dict / cfg_dict_item 业务字典：患者类型、术者、品牌、起搏模式、植入指征等；平台字典为
-- 默认，租户可覆盖」）。租户管理员经机构设置「参数 / 字典」维护取值：新增本院项、停用 / 恢复、
-- 排序、别名（租户库 base V21 加列）；「病区 / 中心」字典即病区字典（A-WARD-001，已有独立实现）。
-- 医院 C 的起搏器类型、品牌、起搏模式等业务字典值随 E21-T04 录入，报告单资料类型与名称字典随
-- E16-T01——本任务只落通用能力与 A 的两个预置字典。
-- 项值全部为示例（虚构），正式取值由产品 / 租户管理员录入；幂等（WHERE NOT EXISTS）以便重放。

INSERT INTO plt_dict (code, name, description, status)
SELECT d.code, d.name, d.description, 'ENABLED'
FROM (
  SELECT 'patient_type' AS code, '患者类型' AS name,
         '患者类型业务字典（A-FLD-012）：本院可停用示例项、新增本院项并维护排序与别名' AS description
  UNION ALL
  SELECT 'surgeon', '术者',
         '术者业务字典（A-FLD-012）：平台只建类型不预置取值，由本院维护（新增、停用、排序、别名）'
) d
WHERE NOT EXISTS (SELECT 1 FROM plt_dict x WHERE x.code = d.code);

INSERT INTO plt_dict_item (dict_id, item_code, label, sort_no, status)
SELECT d.id, i.item_code, i.label, i.sort_no, 'ENABLED'
FROM plt_dict d
JOIN (
  SELECT 'patient_type' AS dict_code, 'ELECTIVE' AS item_code, '择期手术（示例）' AS label, 10 AS sort_no
  UNION ALL SELECT 'patient_type', 'URGENT', '限期手术（示例）', 20
  UNION ALL SELECT 'patient_type', 'EMERGENCY', '急诊手术（示例）', 30
) i ON i.dict_code = d.code
WHERE NOT EXISTS (
  SELECT 1 FROM plt_dict_item x WHERE x.dict_id = d.id AND x.item_code = i.item_code
);
