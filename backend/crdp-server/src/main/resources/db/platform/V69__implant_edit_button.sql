-- E21-T01 / C-PAT-004、C-PAT-005：登记权限码 followup:implant:edit（C 规格 §10 权限点表
-- 「新增与编辑植入记录、电极，更换 / 升级」，普通级）。植入记录接口
-- POST /api/hospital/followup/patients/{id}/implants、PUT /api/hospital/followup/implants/{implantId}
-- 由接口资源扫描（PLT-PERM-004）按注解自动登记 plt_api_resource，无需种子行。
-- 按钮 228「编辑植入记录」挂在患者档案（21）下 hidden=1（V38 编辑患者资料同口径）；更换 / 升级
-- 与电极修正入口（C-PAT-006）复用同一权限码，随 E21-T02。非敏感项，进授权范围模板
-- （V37/V38/V43 同口径）；租户库内置角色的菜单授予在租户库迁移 followup V81（老库补授、
-- 全新库由种子 V135 兜底）。implant 功能开关（cfg_feature，租户库种子 V100 默认关闭）关闭时
-- 前端隐藏入口、后端 40301（AGENTS §5.7），A / B 租户不受影响。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (228, 21, 'HOSPITAL', 'BUTTON', 'followup', '编辑植入记录', NULL, NULL, NULL, 'followup:implant:edit', NULL, 50, 1, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 228 AND m.status = 'ENABLED';
