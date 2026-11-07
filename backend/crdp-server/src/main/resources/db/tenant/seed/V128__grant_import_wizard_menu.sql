-- E18-T06 / A-PAT-008、A-PAT-018：把「导入向导」菜单（plt_menu 353，权限码
-- followup:patient:import，平台库 V58 新增）授予内置 TENANT_ADMIN 与演示 FOLLOWUP_DOCTOR
-- 角色。为什么放种子目录：与 V112 同理——全新租户库按版本号排序执行，followup V74（含同一
-- 授予）先于种子 V100（创建角色）运行，角色还不存在、INSERT…SELECT 命中 0 行；本脚本在
-- V100 建好角色之后补授。老库由 followup V74 授予、生产（无种子，由开通编排 / 角色模板
-- 授予）不会重复插入。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 353
FROM sys_role r
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 353
  );
