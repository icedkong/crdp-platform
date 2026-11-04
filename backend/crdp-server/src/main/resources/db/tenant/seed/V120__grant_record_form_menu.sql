-- E15-T02 / A-FU-004、A-FU-005、C-FU-020：全新库兜底授予「随访录入」隐藏路由（plt_menu 223，
-- 平台库 V45 新增；老库补授在 followup V55）。与 V118「录入随访记录」按钮同口径：
-- 内置 TENANT_ADMIN 与演示 FOLLOWUP_DOCTOR。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 223
FROM sys_role r
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 223
  );
