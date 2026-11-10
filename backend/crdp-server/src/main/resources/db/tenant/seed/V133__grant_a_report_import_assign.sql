-- E23-T10 UAT 自查缺陷 UAT-SELF-01（docs/testing/uat-remediation-hospital-a.md §4）：
-- V123 预置角色权限面缺口补授（A 规格 §10 角色模板「主要权限」列）。
--   231 上传文件（followup:scan:upload，plt_menu V34）
--     → A_WARD_DOCTOR / A_FOLLOWUP_NURSE：「报告单添加与查看」（UAT 脚本 H-1 / H-2 / H-3，此前 40300）
--   353 导入向导（followup:patient:import，plt_menu V74）
--     → A_DATA_CLERK：「导入」（UAT 脚本 J-1，此前 40300）
--   224 批量指定责任医生（followup:task:assign，plt_menu V44）
--     → A_DIRECTOR：批量指定 / 改派（UAT 脚本 E-5，A-FU-009 / A-PAT-017）
-- 敏感权限（明文 211 / 导出 212 / 删除 217）仍不预授（§10「敏感权限」列，K-4）。
-- 幂等：NOT EXISTS 守卫重复执行零新增（与 V123 ② 同口径）；只新增不改旧（AGENTS §5.6）。
-- 生产不执行种子（同 V123）；脚本对每个租户库独立执行，无 A_* 角色的库自动空过。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'A_WARD_DOCTOR' AS code, 231 AS menu_id
  UNION ALL SELECT 'A_FOLLOWUP_NURSE', 231
  UNION ALL SELECT 'A_DATA_CLERK', 353
  UNION ALL SELECT 'A_DIRECTOR', 224
) m ON m.code = r.code
WHERE NOT EXISTS (
  SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
);
