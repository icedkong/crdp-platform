-- E13-T05 / A-PAT-004、A-PAT-013、C-PAT-010：医院端「患者详情」隐藏路由（pages.md §0.1：
-- /followup/patient/:id，component followup/patient/detail）。
-- 挂在患者档案（plt_menu 21）下，hidden=1 不进侧栏、由列表「详情」打开为新标签页；
-- 接管前端样板期的临时路由（apps/web-hospital/src/api/core/menu.ts 的 patientDetailRoute 随本脚本移除）。
-- perm_code 置 NULL：plt_menu.perm_code 全库唯一（uk_plt_menu_perm），followup:patient:view
-- 已由菜单 21 持有；详情接口的权限校验在 @RequiresPermission(followup:patient:view)，
-- 路由可见性随父菜单 21 的授予（持有患者列表即持有详情入口），与工作台（10）等
-- 无权限码路由同口径。
-- followup 模块医院端菜单须进授权范围模板（V37/V38 同口径，非敏感项默认可分配）；
-- 租户库 TENANT_ADMIN / FOLLOWUP_DOCTOR 的菜单授予在租户库迁移 followup V41（老库补授、
-- 全新库由种子 V114 兜底）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (215, 21, 'HOSPITAL', 'MENU', 'followup', '患者详情', 'FollowupPatientDetail', '/followup/patient/:id', 'followup/patient/detail', NULL, NULL, 20, 1, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 215 AND m.status = 'ENABLED';
