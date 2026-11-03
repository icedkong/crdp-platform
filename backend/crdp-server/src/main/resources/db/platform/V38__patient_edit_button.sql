-- E13-T03 / docs/api/patient.md §6：患者档案（plt_menu 21）下的「编辑患者资料」按钮（权限码
-- followup:patient:edit，A/C 规格权限表；新增联系方式版本等患者资料编辑入口用它）。非敏感项，
-- 进授权范围模板（V32/V35/V37 同口径）。租户库 TENANT_ADMIN / FOLLOWUP_DOCTOR 的菜单授予在
-- 租户库迁移 followup V39（老库补授、全新库由种子 V113 兜底）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (214, 21, 'HOSPITAL', 'BUTTON', 'followup', '编辑患者资料', NULL, NULL, NULL, 'followup:patient:edit', NULL, 40, 1, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 214 AND m.status = 'ENABLED';
