-- E25-T01 / NFR-SAVE-001、NFR-SAVE-003：登记权限码 followup:record:edit（A/C 规格权限表原有码——
-- A「录入随访记录」（followup.md §3）、C「录入、补录随访记录与沟通记录，修改本人草稿」，普通级）。
-- 服务器自动保存草稿接口 PUT /api/hospital/followup/records/{id}/draft 需要「暂存草稿」权限
-- （A 规格 A-FU-005「可暂存草稿」、任务状态「进行中 = 已有联系尝试或暂存草稿」）。
-- 按钮 222「录入随访记录」挂在随访任务（22）下 hidden=1：录入工作台页面随 E15-T02 落地，
-- 本任务先落接口与权限资源（与 V37 新建患者同口径）；不进侧栏。
-- followup 模块医院端菜单须进授权范围模板（V37/V39 同口径，非敏感项默认可分配）；
--   租户库内置角色的菜单授予在租户库迁移 followup V47（老库补授、全新库由种子 V118 兜底）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (222, 22, 'HOSPITAL', 'BUTTON', 'followup', '录入随访记录', NULL, NULL, NULL, 'followup:record:edit', NULL, 10, 1, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 222 AND m.status = 'ENABLED';
