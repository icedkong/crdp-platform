-- E16-T09 / A-ATT-007、C-SCAN-016：删除报告单按钮权限码 followup:scan:delete（OQ-12 定论的 scan 族；
-- 创建后不改名，OQ-11）。A / C 规格权限表均为「普通」权限（A 第 12 章、C 第 11 章），
-- 不是 plt_sensitive_perm 项（平台规格 §5 敏感 9 项不含本码）→ 进授权范围模板，默认可分配；
-- 医院端按角色勾选授予，接口侧由 ApiResourceScanner 启动扫描登记（DELETE /api/hospital/followup/reports/**
-- 绑定本权限码），无需种子 plt_api_resource。软删除须填原因并写审计（租户库 followup V65 加删除列）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (233, 23, 'HOSPITAL', 'BUTTON', 'followup', '删除报告单', NULL, NULL, NULL, 'followup:scan:delete', NULL, 30, 1, 'ENABLED');

-- 非敏感项进授权范围模板（V33 的 231 / V41 的 218 同款做法）。
INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 233 AND m.status = 'ENABLED';
