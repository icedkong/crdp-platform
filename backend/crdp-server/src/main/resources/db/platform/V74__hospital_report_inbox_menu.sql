-- E21-T16 / C-SCAN-009（待归档区，P1）：医院端「待归档区」菜单（plt_menu 354，挂在随访目录 20 下，
-- 排在「报告单」23 之后）。
-- 权限码新设 followup:inbox:view（plt_menu.perm_code 唯一，不能与菜单 23 的 followup:report:view
-- 复用，OQ-12 只统一到 report 族命名；创建后不改名，AGENTS §5.9 / OQ-11）——待归档区检索
-- POST /report-inbox/search 按本码鉴权；放入与归档动作沿用按钮 231 的 followup:scan:upload
-- （有上传权限的角色即归档角色），接口资源由启动扫描按注解登记 plt_api_resource。
-- 医院差异走租户库 cfg_feature 功能开关 report_inbox（followup V138，默认关闭）：A / B 不打开开关、
-- 不给角色授予本菜单即无入口；医院 C 随租户配置开启（AGENTS §5.7，不写 if tenant）。
-- 非敏感项，进授权范围模板（V33 / V55 / V69 同口径）；租户库内置角色的授予随 followup V138。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (354, 20, 'HOSPITAL', 'MENU', 'followup', '待归档区', 'FollowupReportInbox', '/followup/report-inbox', 'followup/report-inbox/index', 'followup:inbox:view', NULL, 40, 0, 'ENABLED');

-- 非敏感项进授权范围模板（默认可分配；医院端按角色勾选授予）。
INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 354 AND m.status = 'ENABLED';
