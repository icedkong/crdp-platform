-- E12-T04 / A-FLD-014：配置模板管理页菜单。
--   145 配置模板（/platform/config-template，可见）
--   146 配置模板详情（/platform/config-template/:id，隐藏页，同租户详情 118 做法）。
-- perm_code 置 NULL：plt_menu.perm_code 全库唯一（uk_plt_menu_perm），platform:tenant:view 已被 111 租户管理
-- 占用（V106 同款约束先例）；路由可见性由角色授予（plt_role_menu）决定，接口读权限仍是
-- platform:tenant:view、导出与应用是 platform:tenant:create（OQ-90 ⑤，不新增权限码），前端按钮按后者显隐。
-- 授予 PLATFORM_ADMIN 与 V8/V50 同款做法：主迁移 INSERT..SELECT（全新库此时还没有 PLATFORM_ADMIN 行，
-- 命中 0 行无害；种子 V100 对全部 PLATFORM 侧菜单补授）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status, source) VALUES
  (145, 110, 'PLATFORM', 'MENU', 'base', '配置模板', 'PlatformConfigTemplate', '/platform/config-template', 'platform/config-template/index', NULL, 'AppstoreOutlined', 15, 0, 'ENABLED', 'MANIFEST'),
  (146, 110, 'PLATFORM', 'MENU', 'base', '配置模板详情', 'PlatformConfigTemplateDetail', '/platform/config-template/:id', 'platform/config-template/detail', NULL, NULL, 65, 1, 'ENABLED', 'MANIFEST');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (145, 146);
