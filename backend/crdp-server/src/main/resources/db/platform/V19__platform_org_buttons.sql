-- E06-T02 / PLT-ORG-001、002：组织架构菜单标题对齐 UI 定稿（pages.md §10「组织架构」，/system/dept），
-- 并新增「维护院区与科室」按钮权限码 tenant:org:edit（platform-spec 权限点表；tenant:org:view 已在菜单 33 上，V103）。
-- 不修改已发布的种子 V100（OQ-07 同款做法：标题改动走新迁移）。
-- 角色授予在租户库：老库由 base V9 增量授予、全新 local/dev 库由种子 V104 补授、生产由开通编排 ensureAdmin 授予。
-- base 模块医院端菜单是固定能力（TenantScopeService），不需要进授权范围模板。

UPDATE plt_menu SET title = '组织架构' WHERE id = 33 AND title = '科室管理';

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (331, 33, 'HOSPITAL', 'BUTTON', 'base', '维护院区与科室', NULL, NULL, NULL, 'tenant:org:edit', NULL, 10, 1, 'ENABLED');
