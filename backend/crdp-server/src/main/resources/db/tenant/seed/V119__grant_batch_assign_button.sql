-- E13-T07 / A-PAT-016、A-PAT-017、C-PAT-016：全新库兜底授予「批量指定责任医生」按钮（plt_menu 224）
-- 与「批量入组」隐藏路由（plt_menu 225，平台库 V44）。followup V52 在全新库先于种子 V100 执行
-- （角色不存在命中 0 行），本脚本在角色建立后补授，与 V112–V118 同口径；INSERT 幂等不会重复插。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 224 AS menu_id
  UNION ALL SELECT 'TENANT_ADMIN', 225
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 224
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 225
) m ON m.code = r.code
WHERE r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
