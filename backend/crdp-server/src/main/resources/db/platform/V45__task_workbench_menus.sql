-- E15-T02 / A-FU-004、A-FU-005、C-FU-020：我的任务与随访录入工作台的菜单资源
-- （pages.md §0.1、§5、§7）。
--   * 菜单 22「随访任务」→「我的任务」，并按线框定稿提升为一级菜单（sort_no 15：
--     首页（10）之后、随访目录（20）之前；pages.md §0.1 备注为推断待确认，按文档建议实现）。
--     path / component / 权限码不变（followup:task:view），角色授予按 menu_id 不受
--     层级调整影响；按钮 222（录入随访记录，followup:record:edit）随父菜单保持挂靠。
--   * 菜单 223「随访录入」（隐藏路由 /followup/record/:recordId，component
--     followup/record/form）：随访整页表单，新标签页打开（pages.md §5）。
--     perm_code 置 NULL：plt_menu.perm_code 全库唯一（uk_plt_menu_perm），
--     followup:task:view 已由菜单 22 持有；路由可见性随父菜单 22 的授予
--     （持有任务列表即持有录入入口），与患者详情 215 / 计划详情 25 同口径；
--     页面内录入 / 暂存 / 提交按 followup:record:edit（按钮 222）控制。
-- followup 模块医院端菜单须进授权范围模板（V43/V44 同口径，非敏感项默认可分配）；
--   租户库内置角色的菜单授予在租户库迁移 followup V55（老库补授、全新库由种子 V120 兜底）。

UPDATE plt_menu
SET title = '我的任务', parent_id = 0, sort_no = 15
WHERE id = 22 AND side = 'HOSPITAL';

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (223, 22, 'HOSPITAL', 'MENU', 'followup', '随访录入', 'FollowupRecordForm', '/followup/record/:recordId', 'followup/record/form', NULL, NULL, 30, 1, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 223 AND m.status = 'ENABLED';
