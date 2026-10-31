-- 开通进度页（E02-T08 / PLT-TEN-008）：隐藏菜单，从向导完成页或租户列表进入。
-- perm_code 留空：plt_menu 的权限码唯一（uk_plt_menu_perm），platform:tenant:view 已属租户列表；
-- 接口层仍按 platform:tenant:view 校验。
-- 新菜单必须显式授予 PLATFORM_ADMIN（种子授权是执行时快照，见 V5 的教训）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (116, 110, 'PLATFORM', 'MENU', 'base', '开通进度', 'PlatformTenantProvision', '/platform/tenant/:id/provision', 'platform/tenant/provision', NULL, NULL, 60, 1, 'ENABLED');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.route_name = 'PlatformTenantProvision';
