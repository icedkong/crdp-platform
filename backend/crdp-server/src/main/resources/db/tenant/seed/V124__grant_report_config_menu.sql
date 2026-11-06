-- E16-T01 / A-ATT-003、A-ATT-011、A-ATT-012：把「报告单设置」菜单（plt_menu 39，权限码
-- followup:scan:config，平台库 V53 新增）授予内置 TENANT_ADMIN 角色。
-- 为什么放种子目录：与 V111 同理——全新租户库按版本号排序执行，followup V62（含同一授予）先于
-- 种子 V100（创建 TENANT_ADMIN 角色）运行，角色还不存在、INSERT…SELECT 命中 0 行；本脚本在
-- V100 建好角色之后补授。老库由 followup V62 授予、生产（无种子，由开通编排 ensureAdmin 授予）不会重复插入。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 39
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 39
  );
