-- E13-T05 / A-PAT-004：患者详情隐藏菜单（plt_menu 215，权限码 followup:patient:view，平台库 V39
-- 新增）授予内置 TENANT_ADMIN 与演示 FOLLOWUP_DOCTOR（老库补授；全新库角色由种子 V100 建、V114
-- 兜底；生产由开通编排 / 角色模板授予，INSERT 幂等不会重复插）。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 215
FROM sys_role r
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 215
  );
