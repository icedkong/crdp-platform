-- E18-T06 / A-PAT-008、A-PAT-018：医院端「导入向导」隐藏路由（pages.md §0.1：
-- /followup/patient/import，component followup/patient/import，权限码 followup:patient:import，
-- 挂患者管理 21 下 hidden=1，由患者列表「导入」按钮进入；功能开关 wide_import 关闭 → 40301）。
-- followup 模块医院端菜单须进授权范围模板（V37/V41 同口径，非敏感项默认可分配——导入不属
-- plt_sensitive_perm 敏感清单，与导出 212 / 删除 217 不同）；租户库 TENANT_ADMIN /
-- FOLLOWUP_DOCTOR 的菜单授予在租户库迁移 followup V74（老库补授、全新库由种子 V128 兜底）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (353, 21, 'HOSPITAL', 'MENU', 'followup', '导入向导', 'FollowupPatientImport', '/followup/patient/import', 'followup/patient/import', 'followup:patient:import', NULL, 35, 1, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 353 AND m.status = 'ENABLED';
