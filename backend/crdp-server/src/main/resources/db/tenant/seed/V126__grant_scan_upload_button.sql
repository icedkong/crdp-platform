-- E10-T01 补授 / E16-T09 顺带修正：把「上传文件」按钮（plt_menu 231，权限码
-- followup:scan:upload，平台库 V33 新增）授予内置 TENANT_ADMIN 角色。
-- 为什么现在才补：followup V25 的同一授予在全新租户库先于种子 V100（创建 TENANT_ADMIN
-- 角色）执行、命中 0 行——全新库上 nurse.demo 无上传权限，IT（FileServiceAcceptanceIT /
-- ReportIT 等）只能在「别的用例先跑过模块开通（open 会补授全部模块菜单）」时才通过，
-- 存在用例顺序依赖；本种子补上后无论顺序如何都成立（与 V125 的 233 同款做法）。
-- 老库已由 followup V25 或模块开通补授，INSERT…SELECT 幂等不重复插入。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 231
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 231
  );
