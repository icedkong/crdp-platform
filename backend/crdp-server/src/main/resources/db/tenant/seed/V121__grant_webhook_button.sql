-- E09-T06 / PLT-CFG-007：把「维护租户 Webhook」按钮（plt_menu 343，权限码 tenant:webhook:edit，
-- 平台库 V47 新增）授予内置 TENANT_ADMIN 角色。放种子目录的原因与 V108 同理：
-- 全新租户库按版本号排序执行，followup V59（表）与 base V13 先于种子 V100（创建 TENANT_ADMIN 角色），
-- 本脚本在 V100 建好角色之后补授；老库与生产（无种子，由开通编排 ensureAdmin 授予）不会重复插入。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 343
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 343
  );
