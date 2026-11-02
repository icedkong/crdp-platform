-- E11-T01 / A-FLD-001、A-FLD-002、C-FLD-001：医院端「字段字典」菜单（/system/field，pages.md §0.1 / §10）。
-- 按分类列出字段、可搜索；编辑显示名、帮助、软范围、必填、选项；字段可启停（停用不删除）。
-- 权限码 followup:field:edit（docs/api/form.md §3；OQ-11 创建后不改名）。
-- followup 模块医院端菜单须进授权范围模板（V32 同口径，非敏感项默认可分配）；
-- 租户库 TENANT_ADMIN 的菜单授予在租户库迁移 followup V28（老库补授、全新库由种子 V111 兜底）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (37, 30, 'HOSPITAL', 'MENU', 'followup', '字段字典', 'FollowupField', '/system/field', 'followup/field/index', 'followup:field:edit', NULL, 50, 0, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 37 AND m.status = 'ENABLED';
