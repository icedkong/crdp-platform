-- E04-T06 / PLT-AUTH-008、009：在线会话管理按钮（权限码 tenant:session:view / tenant:session:kick，
-- platform-spec 第 7 章权限点表、docs/api/system.md §8）。挂在用户管理菜单（31）下的隐藏按钮上，
-- 与 E04-T05 的 tenant:user:reset（plt_menu 311）同款做法；在线用户页面（/system/session）属 E06+。
-- 角色授予在租户库：老库由 base V8 增量授予、全新 local/dev 库由种子 V103 补授、生产由开通编排 ensureAdmin 授予。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (312, 31, 'HOSPITAL', 'BUTTON', 'base', '查看在线用户', NULL, NULL, NULL, 'tenant:session:view', NULL, 11, 1, 'ENABLED'),
  (313, 31, 'HOSPITAL', 'BUTTON', 'base', '强制下线', NULL, NULL, NULL, 'tenant:session:kick', NULL, 12, 1, 'ENABLED');
