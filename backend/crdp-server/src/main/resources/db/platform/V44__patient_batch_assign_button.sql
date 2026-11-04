-- E13-T07 / A-PAT-016、A-PAT-017、C-PAT-016：批量入组页面路由与批量指定责任医生按钮。
--   * 224「批量指定责任医生」（followup:task:assign，患者档案 21 下）：OQ-18 暂行口径——A 规格权限点表
--     无独立「患者改派」码，与随访任务批量改派（A-FU-009）同码；C 沿用 A-PAT-017（C-PAT-016）。
--     入口 = 患者列表勾选（pages.md §3）与批量入组页右侧（A-PAT-016/017 同场），接口
--     POST /api/hospital/followup/patients/batch-assign 与 GET .../doctor-options（docs/api/patient.md §2）。
--   * 普通级（非敏感）：不进 plt_sensitive_perm，进授权范围模板（V41 的 218 同口径，默认可分配）。
--   * 225「批量入组」隐藏路由（/followup/patient/batch-enroll，component followup/patient/batch-enroll，
--     无权限码——路由可见性随父菜单 21，与患者详情 215 / 计划详情 25 同口径）：左侧筛选（含高级检索）、
--     中间结果勾选、右侧选目标计划与责任医生，确认前预检将加入 / 将跳过人数（A-PAT-016）。
--   * 批量入组按钮沿用既有 221「计划入组」（followup:plan:enroll，V42），本任务不新增。
-- 租户库内置角色的菜单授予在租户库迁移 followup V52（老库补授、全新库由种子 V119 兜底）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (224, 21, 'HOSPITAL', 'BUTTON', 'followup', '批量指定责任医生', NULL, NULL, NULL, 'followup:task:assign', NULL, 40, 1, 'ENABLED'),
  (225, 21, 'HOSPITAL', 'MENU', 'followup', '批量入组', 'FollowupPatientBatchEnroll', '/followup/patient/batch-enroll', 'followup/patient/batch-enroll', NULL, NULL, 60, 1, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id IN (224, 225) AND m.status = 'ENABLED';
