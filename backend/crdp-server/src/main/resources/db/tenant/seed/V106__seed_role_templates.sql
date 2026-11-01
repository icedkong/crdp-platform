-- E06-T05 / PLT-ROLE-005：演示租户库补授「维护角色与授权」按钮（V100 建角色晚于 base V11，与 V102 / V104 同理），
-- 并补发随访模块的业务角色模板（演示租户的 followup 模块由种子 V100 直接置 OPEN，没有走过开通流程第 4 步；
-- 正式租户由 TenantModuleService 在开通时下发，这里是演示 / IT 环境的对齐兜底，全部为虚构模板角色）。
-- 模板只读：租户复制生成自定义角色后调整（PLT-ORG-005）；模板矩阵与平台库种子 V105 一致（OQ-50 暂行口径）。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 321
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 321
  );

-- 全新 local/dev 库按版本序执行：base V11（建 role_type 列并回填）先于种子 V100 建角色，
-- V11 的回填命中 0 行，这里补一遍（PLT-ROLE-004：租户管理员是内置角色）。
UPDATE sys_role SET role_type = 'BUILT_IN' WHERE built_in = 1 AND role_type <> 'BUILT_IN';

INSERT INTO sys_role (code, name, built_in, status, role_type, module_code, description) VALUES
  ('FOLLOWUP_NURSE', '随访员', 0, 'ENABLED', 'TEMPLATE', 'followup', '执行随访任务、维护患者联系方式（模板，复制后可调整）'),
  ('FOLLOWUP_DUTY_DOCTOR', '责任医生', 0, 'ENABLED', 'TEMPLATE', 'followup', '患者医疗责任医生，查看与处理本人负责患者的随访（模板，复制后可调整）'),
  ('FOLLOWUP_RESEARCHER', '研究者', 0, 'ENABLED', 'TEMPLATE', 'followup', '临床研究视角查看随访与报告数据（模板，复制后可调整）'),
  ('FOLLOWUP_CRC', 'CRC', 0, 'ENABLED', 'TEMPLATE', 'followup', '临床研究协调员，代研究者维护研究资料（模板，复制后可调整）'),
  ('FOLLOWUP_DATA_CLERK', '资料录入员', 0, 'ENABLED', 'TEMPLATE', 'followup', '纸质资料补录与报告单归档（模板，复制后可调整）');

-- 模板默认菜单（与平台库种子 V105 同矩阵）：目录 20 + 患者档案 21 / 随访任务 22 / 报告单 23；资料录入员不含随访任务。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'FOLLOWUP_NURSE' AS code, 20 AS menu_id
  UNION ALL SELECT 'FOLLOWUP_NURSE', 21
  UNION ALL SELECT 'FOLLOWUP_NURSE', 22
  UNION ALL SELECT 'FOLLOWUP_NURSE', 23
  UNION ALL SELECT 'FOLLOWUP_DUTY_DOCTOR', 20
  UNION ALL SELECT 'FOLLOWUP_DUTY_DOCTOR', 21
  UNION ALL SELECT 'FOLLOWUP_DUTY_DOCTOR', 22
  UNION ALL SELECT 'FOLLOWUP_DUTY_DOCTOR', 23
  UNION ALL SELECT 'FOLLOWUP_RESEARCHER', 20
  UNION ALL SELECT 'FOLLOWUP_RESEARCHER', 21
  UNION ALL SELECT 'FOLLOWUP_RESEARCHER', 22
  UNION ALL SELECT 'FOLLOWUP_RESEARCHER', 23
  UNION ALL SELECT 'FOLLOWUP_CRC', 20
  UNION ALL SELECT 'FOLLOWUP_CRC', 21
  UNION ALL SELECT 'FOLLOWUP_CRC', 22
  UNION ALL SELECT 'FOLLOWUP_CRC', 23
  UNION ALL SELECT 'FOLLOWUP_DATA_CLERK', 20
  UNION ALL SELECT 'FOLLOWUP_DATA_CLERK', 21
  UNION ALL SELECT 'FOLLOWUP_DATA_CLERK', 23
) m ON m.code = r.code
WHERE r.role_type = 'TEMPLATE';
