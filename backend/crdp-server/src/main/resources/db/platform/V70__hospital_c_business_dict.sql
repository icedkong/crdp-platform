-- E21-T04 / C-FLD-003、C-FLD-002：医院 C 业务字典预置（平台库，租户继承并增量覆盖，PLT-CFG-002）。
--
-- 统一起搏模式字典（C-FLD-003 / Q08 定稿）：患者登记表 9 项与程控记录表 8 项两套起搏模式选项
-- 合并为一个字典，植入记录（pt_implant.pacing_mode）与程控记录（随 C 字段字典录入绑定同一
-- 字典）共用；取值清单为 Q08 答复定稿的 11 项——SSI、VVI、VVIR、AAI、AAIR、DDD、DDDR、
-- AAI-DDD、AAIR-DDDR、CRT-P、CRT-D；SSI、CRT-P、CRT-D 按建议暂保留，租户可停用
-- （E21-T01 暂行并集中的 VDD / VDDR 不在定稿清单内，不预置）。
-- 其余植入相关业务字典（OQ-69①、OQ-129①）：起搏器类型（附录 A 患者 #8）、品牌（患者 #9）、
-- 植入指征（植入 #11 / Q16，正式取值由租户管理员维护）、电极植入部位按腔室（电极 #3 / Q15：
-- 原表三列共用一组选项，按腔室过滤，各腔室单独建字典，租户新增项自动归入对应腔室下拉）。
-- 库列存稳定字符串编码（与 pt_patient.gender M/F 同口径，编码创建后不可改）；显示名与排序租户
-- 经「机构设置 · 参数 / 字典」维护（停用平台项、新增本院项、排序，V36 同模型）；停用项不进
-- 下拉、历史记录的值保留照常显示（C-FLD-009）。幂等（WHERE NOT EXISTS）以便重放。

INSERT INTO plt_dict (code, name, description, status)
SELECT d.code, d.name, d.description, 'ENABLED'
FROM (
  SELECT 'pacing_mode' AS code, '起搏模式（统一）' AS name,
         '统一起搏模式字典（C-FLD-003 / Q08）：患者登记表与程控记录表两套起搏模式选项合并，植入记录与程控记录共用；SSI、CRT-P、CRT-D 按建议暂保留，本院可停用' AS description
  UNION ALL
  SELECT 'pacemaker_type', '起搏器类型',
         '起搏器类型业务字典（附录 A 患者 #8）：决定随访计划映射（C-FU-003）与条件显示（C-FLD-005）'
  UNION ALL
  SELECT 'pacemaker_brand', '起搏器品牌',
         '起搏器品牌业务字典（附录 A 患者 #9）：本院可停用平台项、新增本院项并维护排序'
  UNION ALL
  SELECT 'implant_indication', '植入指征',
         '植入指征业务字典（附录 A 植入 #11 / Q16，字典 + 文字补充）：预置为暂行取值，正式取值由本院维护；选「其他」须填写补充说明'
  UNION ALL
  SELECT 'lead_position_ra', '电极植入部位（右心房 RA）',
         'RA 电极植入部位业务字典（附录 A 电极 #3 / Q15 按腔室过滤）：本院可停用平台项、新增本院项'
  UNION ALL
  SELECT 'lead_position_rv', '电极植入部位（右心室 RV）',
         'RV 电极植入部位业务字典（附录 A 电极 #3 / Q15 按腔室过滤）：本院可停用平台项、新增本院项'
  UNION ALL
  SELECT 'lead_position_lv', '电极植入部位（左心室 LV）',
         'LV 电极植入部位业务字典（附录 A 电极 #3 / Q15 按腔室过滤，仅 CRT-P、CRT-D 有 LV 电极）：本院可停用平台项、新增本院项'
) d
WHERE NOT EXISTS (SELECT 1 FROM plt_dict x WHERE x.code = d.code);

INSERT INTO plt_dict_item (dict_id, item_code, label, sort_no, status)
SELECT d.id, i.item_code, i.label, i.sort_no, 'ENABLED'
FROM plt_dict d
JOIN (
  -- 统一起搏模式（C-FLD-003 / Q08 定稿 11 项；编码用稳定 ASCII（中横线转下划线），显示名保留原写法）
  SELECT 'pacing_mode' AS dict_code, 'SSI' AS item_code, 'SSI' AS label, 10 AS sort_no
  UNION ALL SELECT 'pacing_mode', 'VVI', 'VVI', 20
  UNION ALL SELECT 'pacing_mode', 'VVIR', 'VVIR', 30
  UNION ALL SELECT 'pacing_mode', 'AAI', 'AAI', 40
  UNION ALL SELECT 'pacing_mode', 'AAIR', 'AAIR', 50
  UNION ALL SELECT 'pacing_mode', 'DDD', 'DDD', 60
  UNION ALL SELECT 'pacing_mode', 'DDDR', 'DDDR', 70
  UNION ALL SELECT 'pacing_mode', 'AAI_DDD', 'AAI-DDD', 80
  UNION ALL SELECT 'pacing_mode', 'AAIR_DDR', 'AAIR-DDDR', 90
  UNION ALL SELECT 'pacing_mode', 'CRT_P', 'CRT-P', 100
  UNION ALL SELECT 'pacing_mode', 'CRT_D', 'CRT-D', 110
  -- 起搏器类型（附录 A 患者 #8）
  UNION ALL SELECT 'pacemaker_type', 'SSI', '单腔起搏器(SSI)', 10
  UNION ALL SELECT 'pacemaker_type', 'DDD', '双腔起搏器(DDD)', 20
  UNION ALL SELECT 'pacemaker_type', 'CRT_P', '三腔起搏器(CRT-P)', 30
  UNION ALL SELECT 'pacemaker_type', 'ICD', 'ICD', 40
  UNION ALL SELECT 'pacemaker_type', 'CRT_D', 'CRT-D', 50
  UNION ALL SELECT 'pacemaker_type', 'HBP_LBBP', '希浦系统起搏(HBP/LBBP)', 60
  UNION ALL SELECT 'pacemaker_type', 'LEADLESS', '无导线起搏器', 70
  -- 品牌（附录 A 患者 #9）
  UNION ALL SELECT 'pacemaker_brand', 'MEDTRONIC', '美敦力', 10
  UNION ALL SELECT 'pacemaker_brand', 'ABBOTT', '雅培', 20
  UNION ALL SELECT 'pacemaker_brand', 'BIOTRONIK', '百多力', 30
  UNION ALL SELECT 'pacemaker_brand', 'BOSTON_SCIENTIFIC', '波士顿科学', 40
  UNION ALL SELECT 'pacemaker_brand', 'CHUANGLING', '创领心律', 50
  UNION ALL SELECT 'pacemaker_brand', 'WEICHUANG', '微创医疗', 60
  UNION ALL SELECT 'pacemaker_brand', 'LEPU', '乐普', 70
  UNION ALL SELECT 'pacemaker_brand', 'JINSHI', '金仕生物', 80
  UNION ALL SELECT 'pacemaker_brand', 'OTHER', '其他', 90
  -- 植入指征（附录 A 植入 #11 / Q16；暂行预置，正式取值由本院维护）
  UNION ALL SELECT 'implant_indication', 'SSS', '病态窦房结综合征', 10
  UNION ALL SELECT 'implant_indication', 'AVB', '房室传导阻滞', 20
  UNION ALL SELECT 'implant_indication', 'AF_PAUSE', '房颤伴长RR间期', 30
  UNION ALL SELECT 'implant_indication', 'CRT_IND', '心脏再同步治疗指征', 40
  UNION ALL SELECT 'implant_indication', 'OTHER', '其他', 50
  -- 电极植入部位（附录 A 电极 #3 / Q15 按腔室；各腔室单独字典，本院新增项自动进对应下拉）
  UNION ALL SELECT 'lead_position_ra', 'RA_APPENDAGE', '右心耳', 10
  UNION ALL SELECT 'lead_position_ra', 'RA_SEPTUM', '右房间隔', 20
  UNION ALL SELECT 'lead_position_ra', 'OTHER', '其他', 90
  UNION ALL SELECT 'lead_position_rv', 'RV_APEX', '右室心尖', 10
  UNION ALL SELECT 'lead_position_rv', 'RV_SEPTUM', '右室间隔', 20
  UNION ALL SELECT 'lead_position_rv', 'HIS', '希氏束', 30
  UNION ALL SELECT 'lead_position_rv', 'LBB', '左束支', 40
  UNION ALL SELECT 'lead_position_rv', 'OTHER', '其他', 90
  UNION ALL SELECT 'lead_position_lv', 'CS', '冠状静脉窦', 10
  UNION ALL SELECT 'lead_position_lv', 'OTHER', '其他', 90
) i ON i.dict_code = d.code
WHERE NOT EXISTS (
  SELECT 1 FROM plt_dict_item x WHERE x.dict_id = d.id AND x.item_code = i.item_code
);
