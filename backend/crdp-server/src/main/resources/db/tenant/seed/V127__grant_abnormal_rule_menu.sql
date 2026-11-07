-- E17-T01 / A-FU-027：把「对比规则」菜单（plt_menu 40，权限码 followup:abnormal:config，
-- 平台库 V57 新增）授予内置 TENANT_ADMIN 角色（A 规格无单独配置码，按 OQ-16 给 A 也授此码）。
-- 为什么放种子目录：与 V124 同理——全新租户库按版本号排序执行，followup V67（含同一授予）先于
-- 种子 V100（创建 TENANT_ADMIN 角色）运行，角色还不存在、INSERT…SELECT 命中 0 行；本脚本在
-- V100 建好角色之后补授。老库由 followup V67 授予、生产（无种子，由开通编排 ensureAdmin 授予）
-- 不会重复插入。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 40
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 40
  );
