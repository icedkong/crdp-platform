-- E13-T08 / A-WARD-001、A-WARD-005：全新库兜底授予「病区管理」菜单（plt_menu 38，平台库 V40）
-- 与「转病区」按钮（216）。followup V43 在全新库先于种子 V100 执行（角色不存在命中 0 行），
-- 本脚本在角色建立后补授，与 V112–V114 同口径；INSERT 幂等不会重复插。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 38 AS menu_id
  UNION ALL SELECT 'TENANT_ADMIN', 216
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 216
) m ON m.code = r.code
WHERE r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
