-- E19-T02 / A-DASH-001、A-DASH-002、C-DASH-001（首页指标卡与趋势图）：首页卡片权限码
-- followup:dashboard:view（OQ-19：统计页与首页卡片同码）。pages.md §2「每张卡片按卡片目录登记的权限码（多为
-- followup:dashboard:view）」；卡片接口 GET /api/hospital/followup/dashboard/cards/{cardCode} 绑定本码，
-- 接口资源由启动扫描登记（SCAN），无需种子 plt_api_resource。
--   * BUTTON 挂在医院端首页（plt_menu 10）下：首页菜单本身无页面权限（pages.md §2），卡片按本码显隐；
--     module_code = followup（有效权限三层交集里随模块开通，PLT-ROLE-006）；
--   * 非敏感普通权限 → 进授权范围模板（V55 / V33 同款），租户管理员可按角色分配；
--     演示角色授予随租户库种子 V129（老库补授幂等）。
INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (234, 10, 'HOSPITAL', 'BUTTON', 'followup', '首页统计卡片', NULL, NULL, NULL, 'followup:dashboard:view', NULL, 10, 1, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 234 AND m.status = 'ENABLED';
