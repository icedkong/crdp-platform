-- E13-T05 / A-PAT-004：把「患者详情」隐藏菜单（plt_menu 215，权限码 followup:patient:view，
-- 平台库 V39 新增）授予内置 TENANT_ADMIN 与演示 FOLLOWUP_DOCTOR 角色。
-- 为什么放种子目录：与 V112 同理——全新租户库按版本号排序执行，followup V41（含同一授予）先于
-- 种子 V100（创建角色）运行，角色还不存在、INSERT…SELECT 命中 0 行；本脚本在 V100 建好角色之后
-- 补授。老库由 followup V41 授予、生产（无种子，由开通编排 / 角色模板授予）不会重复插入。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 215
FROM sys_role r
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 215
  );
