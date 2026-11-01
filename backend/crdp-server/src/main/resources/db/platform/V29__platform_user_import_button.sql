-- E06-T08 / PLT-ORG-007：用户管理页「批量导入用户」按钮权限码
-- tenant:user:import（platform-spec §6 权限点表）。挂在用户管理菜单（31）下隐藏按钮 315，
-- 与 311（tenant:user:reset，V10）、314（tenant:user:edit，V23）同款做法。
-- 角色授予在租户库：老库由 base V20 增量授予、全新 local/dev 库由种子 V110 补授、
-- 生产由开通编排 ensureAdmin 授予。base 模块医院端菜单是固定能力（TenantScopeService），
-- 不需要进授权范围模板。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (315, 31, 'HOSPITAL', 'BUTTON', 'base', '批量导入用户', NULL, NULL, NULL, 'tenant:user:import', NULL, 14, 1, 'ENABLED');
