-- E14-T01 / A-FU-016、A-FU-018、A-FU-019、C-FU-001：医院端「随访计划」页面菜单与操作按钮。
--   * 菜单 24（/followup/plan，component followup/plan/index，权限码 followup:plan:view，pages.md §8）：
--     计划列表与配置入口，挂随访目录（20）下、患者档案（21）之后（线框定稿菜单顺序：患者管理 → 随访计划）。
--     页面本身随 E14-T02 前端任务落地，本任务先落数据面接口与菜单资源。
--   * 菜单 25（/followup/plan/:planId，隐藏，无权限码，与患者详情 215 同口径）：计划详情页
--     （页签：基本信息 / 节点 / 患者列表 / 变更记录；患者列表与变更记录随 E14-T08）。
--   * 按钮 219 编辑计划（followup:plan:edit：新建、修改仅未发布、复制为新版本）、
--     220 发布计划（followup:plan:publish：发布、暂停、恢复、停用）、
--     221 计划入组（followup:plan:enroll：入组、批量入组、暂停、恢复、移出）——A 规格权限点表均为「普通」级；
--     C 没有这三个码，计划相关一律用 followup:plan:edit（OQ-13），接口按「任一码命中即放行」声明。
-- followup 模块医院端菜单须进授权范围模板（V39/V40 同口径，非敏感项默认可分配，
--   含 NULL 权限码的隐藏详情路由 25——路由可见性随父菜单 24 的授予，与患者详情 215 同口径）；
--   租户库内置角色的菜单授予在租户库迁移 followup V46（老库补授、全新库由种子 V117 兜底）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (24, 20, 'HOSPITAL', 'MENU', 'followup', '随访计划', 'FollowupPlan', '/followup/plan', 'followup/plan/index', 'followup:plan:view', NULL, 15, 0, 'ENABLED'),
  (25, 24, 'HOSPITAL', 'MENU', 'followup', '计划详情', 'FollowupPlanDetail', '/followup/plan/:planId', 'followup/plan/detail', NULL, NULL, 20, 1, 'ENABLED'),
  (219, 24, 'HOSPITAL', 'BUTTON', 'followup', '编辑计划', NULL, NULL, NULL, 'followup:plan:edit', NULL, 10, 1, 'ENABLED'),
  (220, 24, 'HOSPITAL', 'BUTTON', 'followup', '发布计划', NULL, NULL, NULL, 'followup:plan:publish', NULL, 20, 1, 'ENABLED'),
  (221, 24, 'HOSPITAL', 'BUTTON', 'followup', '计划入组', NULL, NULL, NULL, 'followup:plan:enroll', NULL, 30, 1, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id IN (24, 25, 219, 220, 221) AND m.status = 'ENABLED';
