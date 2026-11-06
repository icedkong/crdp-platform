-- E13-T02 / A-PAT-006：基线锁定与解锁按钮、基线分节编辑隐藏路由（pages.md §0.1、§4.2–4.3）。
--   * 按钮 226「锁定/解锁基线」挂在患者档案（21）下，权限码 followup:patient:lock（A 规格权限表
--     「锁定与解锁基线」，普通级；patient.md §4 POST /patients/{id}/lock 的专用权限——解锁必须有它并填原因）。
--   * 菜单 227「基线分节编辑」hidden=1 不进侧栏，由基线资料页签「编辑」新标签页打开（pages.md §0.1：
--     /followup/patient/:id/baseline/edit，component followup/patient/baseline-edit）。
--     perm_code 置 NULL：plt_menu.perm_code 全库唯一（uk_plt_menu_perm），followup:patient:view
--     已由菜单 21 持有、followup:patient:edit 已由按钮 214 持有；路由可见性随父菜单 21 的授予
--     （与患者详情 215 / 随访录入 223 同口径），页面内保存 / 暂存按 followup:patient:edit（按钮 214）控制。
-- followup 模块医院端菜单须进授权范围模板（V37/V38 同口径，非敏感项默认可分配）；
--   租户库 TENANT_ADMIN / FOLLOWUP_DOCTOR 的菜单授予在租户库迁移 followup V60（老库补授、
--   全新库由种子 V122 兜底）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (226, 21, 'HOSPITAL', 'BUTTON', 'followup', '锁定/解锁基线', NULL, NULL, NULL, 'followup:patient:lock', NULL, 45, 1, 'ENABLED'),
  (227, 21, 'HOSPITAL', 'MENU', 'followup', '基线分节编辑', 'FollowupBaselineEdit', '/followup/patient/:id/baseline/edit', 'followup/patient/baseline-edit', NULL, NULL, 25, 1, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id IN (226, 227) AND m.status = 'ENABLED';
