-- E06-T11 / PLT-CFG-008：医院端「机构设置」菜单（/system/settings，pages.md §0.1 / §10）。
-- 本任务先落个性化（tenant:brand:edit）；参数 / 字典 / 通知模板 / Webhook / 安全设置页签
-- 随 E06-T10 等任务在同一菜单下扩展。base 模块医院端菜单是固定能力（TenantScopeService），
-- 不需要进授权范围模板；角色授予在租户库（老库 base V10、全新 local/dev 库种子 V105、生产 ensureAdmin）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (34, 30, 'HOSPITAL', 'MENU', 'base', '机构设置', 'HospitalSettings', '/system/settings', 'system/settings/index', 'tenant:brand:edit', NULL, 40, 0, 'ENABLED');
