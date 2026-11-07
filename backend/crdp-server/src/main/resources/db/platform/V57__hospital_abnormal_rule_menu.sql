-- E17-T01 / A-FU-027（Q41）、C-FU-011（随 E21）：
-- 医院端「对比规则」菜单（/system/abnormal-rule，pages.md §0.1 / §10、docs/api/followup.md §4）。
-- 维护录入时对比标红规则：每条规则可开关、调阈值（A 的 Q41 全量规则随 E17-T05 预置、C 的默认随 E21-T20）。
-- 权限码 followup:abnormal:config（C 规格原有码；A 规格无单独配置码，按 OQ-16 暂行做法给 A 的
-- 租户管理员也授此码，创建后不改名，OQ-11）。
-- 内置角色只授 TENANT_ADMIN（与报告单设置 V53 同口径：A-ATT-003 同类「租户管理员可维护」语义）；
-- followup 模块医院端菜单须进授权范围模板（V35 同口径，非敏感项默认可分配）；
-- 租户库 TENANT_ADMIN 的菜单授予在租户库迁移 followup V67（老库补授、全新库由种子 V127 兜底）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (40, 30, 'HOSPITAL', 'MENU', 'followup', '对比规则', 'FollowupAbnormalRule', '/system/abnormal-rule', 'followup/rule/index', 'followup:abnormal:config', NULL, 56, 0, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 40 AND m.status = 'ENABLED';
