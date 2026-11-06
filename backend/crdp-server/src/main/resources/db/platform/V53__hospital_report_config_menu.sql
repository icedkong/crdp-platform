-- E16-T01 / A-ATT-003、A-ATT-011、A-ATT-012、A-ATT-018；C-SCAN-002、C-SCAN-003、C-SCAN-004：
-- 医院端「报告单设置」菜单（/system/report-config，pages.md §0.1 / §10、docs/api/report.md §6）。
-- 维护资料类型及字段集、名称字典、指标模板（字段映射随 E16-T07、格式与大小随 E10-T01 平台参数）。
-- 权限码 followup:scan:config（OQ-12 定论的 scan 族；创建后不改名，OQ-11）。
-- A-ATT-003「租户管理员可增减、停用、排序」，内置角色只授 TENANT_ADMIN（与病区管理 V40 同口径）；
-- followup 模块医院端菜单须进授权范围模板（V35 同口径，非敏感项默认可分配）；
-- 租户库 TENANT_ADMIN 的菜单授予在租户库迁移 followup V62（老库补授、全新库由种子 V124 兜底）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (39, 30, 'HOSPITAL', 'MENU', 'followup', '报告单设置', 'FollowupReportConfig', '/system/report-config', 'followup/report/config', 'followup:scan:config', NULL, 55, 0, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 39 AND m.status = 'ENABLED';
