-- E09-T04 / A-NTF-001、C-NTF-001：医院端「通知设置」菜单（/system/notice，pages.md §0.1 / §10）。
-- 逐事件配置开关、提前天数、发送时间、渠道、接收人规则；短信模板只能选平台审核过的（接口随本任务落地）。
-- followup 模块医院端菜单须进授权范围模板（V102 同口径，非敏感项默认可分配）；
-- 租户库 TENANT_ADMIN 的菜单授予在租户库迁移 followup V22（老库补授、全新库先于种子同样命中）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (36, 30, 'HOSPITAL', 'MENU', 'followup', '通知设置', 'FollowupNotice', '/system/notice', 'followup/notice/index', 'followup:notice:config', NULL, 45, 0, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 36 AND m.status = 'ENABLED';
