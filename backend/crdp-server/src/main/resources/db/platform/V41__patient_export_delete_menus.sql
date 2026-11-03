-- E13-T09 / A-PAT-009、A-PAT-012、C-PAT-014、C-PAT-015：患者导出与删除患者的按钮权限。
--   * 217「删除患者」（followup:patient:delete，患者档案 21 下）：C 规格权限点表原有码；A 权限表无删除码，
--     按 OQ-17 新增同名码给 A。敏感权限（C 规格标敏感、A-PAT-012「敏感权限」）→ 登记 plt_sensitive_perm，
--     默认不可分配、平台按租户单独放开（PLT-SCOPE-004，与 211 / 212 同口径，不进授权范围模板）。
--   * 218「审批患者导出」（followup:patient:export:approve，患者档案 21 下，排在 212 导出患者之后）：
--     租户普通用户导出由租户管理员审批（platform-spec §9 已确认），审批本身非敏感 → 进授权范围模板
--     （默认可分配；会话权限交集 = 角色授予 ∩ 可分配范围，followup 模块按钮须在模板内才对内置
--     TENANT_ADMIN 生效——审计审批 352 属 base 固定能力不受模板限制，218 属 followup 须显式登记）。
--     租户库内置角色授予在租户库迁移 followup V44（老库补授、全新库由种子 V116 兜底；生产由开通编排 /
--     角色模板授予）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (217, 21, 'HOSPITAL', 'BUTTON', 'followup', '删除患者', NULL, NULL, NULL, 'followup:patient:delete', NULL, 50, 1, 'ENABLED'),
  (218, 21, 'HOSPITAL', 'BUTTON', 'followup', '审批患者导出', NULL, NULL, NULL, 'followup:patient:export:approve', NULL, 25, 1, 'ENABLED');

-- 敏感权限清单（uk_plt_sensitive_perm 幂等）：命中按钮默认不可分配，平台按租户单独放开。
INSERT INTO plt_sensitive_perm (perm_code, name) VALUES
  ('followup:patient:delete', '删除患者（软删除，数据长期保留）');

-- 218 非敏感进授权范围模板（V40 的 38 / 216 同款做法）。
INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 218 AND m.status = 'ENABLED';
