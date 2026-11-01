-- E06-T10 / PLT-CFG-002、004：把「维护租户参数 / 维护租户字典」按钮（plt_menu 341 / 342，
-- 权限码 tenant:param:edit / tenant:dict:edit，平台库 V24 新增）授予内置 TENANT_ADMIN 角色。
-- 放种子目录的原因与 V105 同理：全新租户库按版本号排序执行，base V13（含同一授予）先于
-- 种子 V100（创建 TENANT_ADMIN 角色）运行，角色还不存在、INSERT…SELECT 命中 0 行；本脚本
-- 在 V100 建好角色之后补授。老库与生产（无种子，由开通编排 ensureAdmin 授予）不会重复插入。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 341 AS menu_id
  UNION ALL SELECT 'TENANT_ADMIN', 342
) m ON m.code = r.code
WHERE r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
