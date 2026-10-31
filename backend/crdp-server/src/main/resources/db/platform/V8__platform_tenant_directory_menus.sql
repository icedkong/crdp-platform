-- 租户列表与信息维护（E05-T01）：编辑按钮权限码 + 隐藏的租户详情页。
-- 权限码创建后不改名（OQ-11）；新菜单必须显式授予 PLATFORM_ADMIN（种子授权是执行时快照，见 V5 注释）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (117, 111, 'PLATFORM', 'BUTTON', 'base', '编辑租户信息', NULL, NULL, NULL, 'platform:tenant:edit', NULL, 10, 1, 'ENABLED'),
  (118, 110, 'PLATFORM', 'MENU', 'base', '租户详情', 'PlatformTenantDetail', '/platform/tenant/:id', 'platform/tenant/detail', NULL, NULL, 55, 1, 'ENABLED');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (117, 118);
