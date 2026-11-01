-- E06-T08 / PLT-ORG-007：把「批量导入用户」按钮（plt_menu 315，权限码
-- tenant:user:import，平台库 V29 新增）授予内置 TENANT_ADMIN 角色（老库增量；
-- 与 V12 授予 314 同款做法）。全新 local/dev 库由种子 V110 补授（base 先于种子执行时
-- 角色还不存在，INSERT…SELECT 命中 0 行）；生产由开通编排 ensureAdmin 授予。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 315 AS menu_id
) m ON m.code = r.code
WHERE r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
