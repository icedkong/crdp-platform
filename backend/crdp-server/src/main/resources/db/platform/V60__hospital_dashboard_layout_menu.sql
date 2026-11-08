-- E19-T06 / A-DASH-013（首页卡片配置〔按角色〕）：医院端系统设置「首页卡片配置」菜单
-- （/system/dashboard-layout，pages.md §0.1 / §10、docs/api/system.md §5）。
-- 租户管理员选角色 → 左卡片目录勾选显示 / 右顺序上下移 / 预览 / 恢复默认；
-- 用户隐藏 / 显示（A-DASH-014，P1）走首页右上角「卡片设置」，不占菜单。
-- 权限码 followup:dashboard:config（A 规格 §10 权限点表「按角色配置首页卡片的显示与顺序」，
-- 创建后不改名，OQ-11）；接口 GET/PUT/DELETE /api/hospital/followup/dashboard/role-layouts**
-- 绑定本码，资源由启动扫描登记（SCAN），无需种子 plt_api_resource。
--   * followup 模块医院端菜单须进授权范围模板（V35 同口径，非敏感项默认可分配）；
--   * 内置角色授予随租户库种子 V130（老库补授幂等，V127 同款）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (41, 30, 'HOSPITAL', 'MENU', 'followup', '首页卡片配置', 'FollowupDashboardLayout', '/system/dashboard-layout', 'followup/dashboard/layout', 'followup:dashboard:config', NULL, 57, 0, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 41 AND m.status = 'ENABLED';
