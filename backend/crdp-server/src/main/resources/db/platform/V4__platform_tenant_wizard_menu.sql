-- 新建租户向导（E02-T07 / PLT-TEN-002）：隐藏菜单页，入口按钮随 E05-T01 租户列表提供。
-- PLATFORM_ADMIN 经既有 plt_role_menu 规则（side='PLATFORM' 全量）自动获得本菜单与权限码。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (115, 110, 'PLATFORM', 'MENU', 'base', '新建租户', 'PlatformTenantNew', '/platform/tenant/new', 'platform/tenant/new', 'platform:tenant:create', NULL, 50, 1, 'ENABLED');
