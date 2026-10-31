-- 种子的 plt_role_menu 是执行时的快照，新菜单（V4 新建租户向导）需显式授予 PLATFORM_ADMIN。

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.route_name = 'PlatformTenantNew';
