-- E04-T05 / PLT-AUTH-007：把「解锁 / 重置」按钮（plt_menu 311，权限码 tenant:user:reset，
-- 平台库 V10 新增）授予内置 TENANT_ADMIN 角色。
--
-- 为什么放种子目录：全新租户库按版本号排序执行，base V7（含同一授予）先于种子 V100
-- （创建 TENANT_ADMIN 角色）运行，角色还不存在、INSERT…SELECT 命中 0 行；本脚本在
-- V100 建好角色之后补授。老库（base V7 执行时角色已存在）与生产（无种子，由开通编排
-- ensureAdmin 授予）都不会重复插入（NOT EXISTS 保护）。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 311
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN' AND r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 311
  );
