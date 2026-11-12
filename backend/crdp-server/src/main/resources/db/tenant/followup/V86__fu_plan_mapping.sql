-- E21-T05 / C-FU-003：器械类型 → 随访计划映射表 fu_plan_mapping（医院 C 按器械类型自动入组）。
--
--   * device_type 存业务字典 pacemaker_type 的稳定编码（平台库 V70 预置：SSI / DDD / CRT_P / ICD /
--     CRT_D / HBP_LBBP / LEADLESS；本院经 cfg_dict 可停用平台项 / 新增自有项，字典外编码保存时被
--     DevicePlanMappingService 拒绝——与 ImplantService 的字段校验同口径）；
--   * plan_code 存计划族编码（fu_plan.code，跨版本不变，A-FU-019）：入组时解析该族当前 PUBLISHED
--     版本（新版本发布时同族旧版本已自动转 DISABLED，解析必然落在新版本）；族暂无已发布版本时
--     该类型保存植入记录不自动入组（业务跳过，不报错）；
--   * enabled 关闭 = 该类型不再自动入组（既有入组不受影响）；
--   * 保存映射（PUT /plans/device-mapping）为整体替换：本表行被整组重写，uk 保证一类型一行。
--
-- 预置映射（C-FU-002 的类型划分）：普通起搏器术后随访（单腔 / 双腔 / CRT-P / 希浦系统 / 无导线）与
--   ICD/CRT-D 术后随访。两套预置计划本体随 E23-T20（产品配置）录入，族编码约定为
--   C_PACER_FU_PLAN（普通起搏器术后随访）与 C_ICD_FU_PLAN（ICD/CRT-D 术后随访）——
--   与 V82 预置对比规则先行声明 C_PROGRAM_FU 键契约同法：计划族落地前映射行惰性
--   （解析不到已发布版本即跳过），产品侧如改用其他编码，经 PUT 或新迁移调整映射即可。
--   A / B 租户 impl 开关关闭、不保存植入记录，本表行不参与任何自动入组（与 pt_visit_no_seq 同口径）。
-- 非（患者及其子表等）受控业务表（纯配置行，不含患者面数据），不登记 ControlledTables；
--   配置改动走 PLAN_MAPPING_SAVE 操作审计（审计只追加）。

CREATE TABLE fu_plan_mapping (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  device_type VARCHAR(32) NOT NULL COMMENT '起搏器类型编码（业务字典 pacemaker_type 的稳定编码）',
  plan_code VARCHAR(64) NOT NULL COMMENT '计划族编码（fu_plan.code，入组时解析该族当前已发布版本）',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用（0 = 该类型不再自动入组）',
  created_by BIGINT NULL COMMENT '创建人（系统预置为 NULL）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT NULL COMMENT '最后修改人',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_fu_plan_mapping_device (device_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='器械类型→随访计划映射（C-FU-003，E21-T05）';

-- 预置映射：普通起搏器计划（C-FU-002 单腔、双腔、CRT-P、希浦系统、无导线）
INSERT INTO fu_plan_mapping (device_type, plan_code, enabled) VALUES
  ('SSI',        'C_PACER_FU_PLAN', 1),
  ('DDD',        'C_PACER_FU_PLAN', 1),
  ('CRT_P',      'C_PACER_FU_PLAN', 1),
  ('HBP_LBBP',   'C_PACER_FU_PLAN', 1),
  ('LEADLESS',   'C_PACER_FU_PLAN', 1),
-- ICD / CRT-D 术后随访（植入或升级为 ICD / CRT-D 后转入）
  ('ICD',        'C_ICD_FU_PLAN', 1),
  ('CRT_D',      'C_ICD_FU_PLAN', 1);
