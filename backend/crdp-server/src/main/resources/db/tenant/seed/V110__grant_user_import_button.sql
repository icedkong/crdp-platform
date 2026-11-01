-- E06-T08 / PLT-ORG-007：把「批量导入用户」按钮（plt_menu 315，权限码
-- tenant:user:import，平台库 V29 新增）授予内置 TENANT_ADMIN 角色。
--
-- 为什么放种子目录：与 V107 同理——全新租户库按版本号排序执行，base V20（含同一授予）先于
-- 种子 V100（创建 TENANT_ADMIN 角色）运行，角色还不存在、INSERT…SELECT 命中 0 行；本脚本在
-- V100 建好角色之后补授。老库与生产（无种子，由开通编排 ensureAdmin 授予）不会重复插入。

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
