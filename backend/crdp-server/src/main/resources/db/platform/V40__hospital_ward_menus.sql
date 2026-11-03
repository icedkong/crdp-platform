-- E13-T08 / A-WARD-001、A-WARD-005、A-WARD-008：医院端「病区管理」页面菜单与「转病区」按钮。
--   * 菜单 38（/system/ward，component system/ward/index，权限码 followup:ward:edit）：病区 / 中心
--     字典维护（编码、名称、类型、别名、默认随访负责人、排序、停用——A-WARD-001 关键字段全集），
--     归 followup 模块（病区字典属 crdp-followup ward 子域，system.md §1），挂在系统管理（30）下、
--     科室管理（33）之后；A-WARD-001「租户管理员维护」，内置角色只授 TENANT_ADMIN。
--   * 按钮 216（患者档案 21 下，权限码 followup:patient:transfer）：患者列表 / 详情「转病区」入口
--     （A-WARD-005；pages.md §3 行操作 ⋮ 更多、§6 页面 4 患者详情 · 转病区）。
-- followup 模块医院端菜单须进授权范围模板（V37/V39 同口径，非敏感项默认可分配）；
--   租户库内置角色的菜单授予在租户库迁移 followup V43（老库补授、全新库由种子 V115 兜底）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (38, 30, 'HOSPITAL', 'MENU', 'followup', '病区管理', 'HospitalWard', '/system/ward', 'system/ward/index', 'followup:ward:edit', NULL, 33, 0, 'ENABLED'),
  (216, 21, 'HOSPITAL', 'BUTTON', 'followup', '转病区', NULL, NULL, NULL, 'followup:patient:transfer', NULL, 30, 1, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id IN (38, 216) AND m.status = 'ENABLED';
