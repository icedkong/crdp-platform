-- 租户状态变更与到期续期按钮（E05-T02 / PLT-TEN-010、013）。两个权限点分开（docs/api/platform.md §1）：
--   platform:tenant:status —— 转正、手动停用、恢复（POST /tenants/{id}/status）
--   platform:tenant:renew  —— 到期设置与续期（PUT /tenants/{id}/expire）
-- 权限码创建后不改名（OQ-11）；与 V8 做法一致，显式授予 PLATFORM_ADMIN（种子授权是执行时快照）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (119, 111, 'PLATFORM', 'BUTTON', 'base', '租户状态变更', NULL, NULL, NULL, 'platform:tenant:status', NULL, 20, 1, 'ENABLED'),
  (120, 111, 'PLATFORM', 'BUTTON', 'base', '到期设置与续期', NULL, NULL, NULL, 'platform:tenant:renew', NULL, 30, 1, 'ENABLED');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (119, 120);
