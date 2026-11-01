-- E06-T07 / PLT-ORG-006：用户管理页「新建、编辑、停用、启用与离职」按钮权限码
-- tenant:user:edit（platform-spec §6 权限点表）。挂在用户管理菜单（31）下隐藏按钮 314，
-- 与 311（tenant:user:reset，V10）、312 / 313（在线会话，V11）同款做法。
-- 角色授予在租户库：老库由 base V12 增量授予、全新 local/dev 库由种子 V107 补授、
-- 生产由开通编排 ensureAdmin 授予。base 模块医院端菜单是固定能力（TenantScopeService），
-- 不需要进授权范围模板。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (314, 31, 'HOSPITAL', 'BUTTON', 'base', '新建、编辑、停用与离职用户', NULL, NULL, NULL, 'tenant:user:edit', NULL, 13, 1, 'ENABLED');
