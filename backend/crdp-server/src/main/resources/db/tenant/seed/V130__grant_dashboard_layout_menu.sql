-- E19-T06 / A-DASH-013：把「首页卡片配置」菜单（plt_menu 41，权限码 followup:dashboard:config，
-- 平台库 V60 新增）授予内置 TENANT_ADMIN 角色（A 规格 §10：首页卡片配置属租户管理员）。
-- 为什么放种子目录：与 V127 同理——平台库 V60 先于全新租户库角色（种子 V100 建 TENANT_ADMIN）
-- 执行时角色尚不存在，授予放这里对老库与全新库都成立（INSERT…SELECT 幂等不重复插入）。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 41
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 41
  );
