-- E09-T06 / PLT-CFG-007：医院端「机构设置」下新增「维护租户 Webhook」按钮（plt_menu 343，父 34，
-- 权限码 tenant:webhook:edit，platform-spec §10 权限点表，OQ-11 创建后不改名）。
-- Webhook 配置表与投递台账在租户库（followup V59）；接口 /api/hospital/system/webhooks（notify.md §6）。
-- base 模块医院端按钮是固定能力（TenantScopeService），不进授权范围模板；
-- 租户库 TENANT_ADMIN 的授予在种子 V121（老库补授、全新库 V100 建好角色后同样命中）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (343, 34, 'HOSPITAL', 'BUTTON', 'base', '维护租户 Webhook', NULL, NULL, NULL, 'tenant:webhook:edit', NULL, 30, 1, 'ENABLED');
