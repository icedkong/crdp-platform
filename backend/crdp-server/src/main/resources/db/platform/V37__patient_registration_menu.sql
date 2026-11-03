-- E13-T01 / A-PAT-003、C-PAT-001：医院端「新建患者」隐藏路由（pages.md §0.1：
-- /followup/patient/new，component followup/patient/new，权限码 followup:patient:create，
-- A 出院登记 / C 患者登记）。挂在患者档案（plt_menu 21）下，hidden=1 不进侧栏、由「+ 新建患者」按钮进入。
-- followup 模块医院端菜单须进授权范围模板（V32/V35 同口径，非敏感项默认可分配）；
-- 租户库 TENANT_ADMIN / FOLLOWUP_DOCTOR 的菜单授予在租户库迁移 followup V37（老库补授、
-- 全新库由种子 V112 兜底）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (213, 21, 'HOSPITAL', 'MENU', 'followup', '新建患者', 'FollowupPatientNew', '/followup/patient/new', 'followup/patient/new', 'followup:patient:create', NULL, 30, 1, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 213 AND m.status = 'ENABLED';
