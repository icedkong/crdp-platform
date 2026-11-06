-- E13-T02 / A-PAT-006：全新库兜底授予「锁定/解锁基线」按钮（plt_menu 226，权限码
-- followup:patient:lock）与「基线分节编辑」隐藏路由（plt_menu 227）——平台库 V48 新增，
-- 老库补授在 followup V60。内置 TENANT_ADMIN（全业务）与演示 FOLLOWUP_DOCTOR
-- （病区医生确认锁定，A-PAT-006；与 V112–V121 同口径）。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.n
FROM sys_role r
JOIN (SELECT 226 AS n UNION SELECT 227 AS n) m
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.n
  );
