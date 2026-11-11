-- E21-T01 / C-PAT-004、C-PAT-005：「编辑植入记录」按钮（plt_menu 228，权限码
-- followup:implant:edit，平台库 V69 新增）授予内置 TENANT_ADMIN 与演示 FOLLOWUP_DOCTOR——
-- 植入记录与电极的登记、编辑（更换 / 升级入口随 E21-T02 复用同一权限码）。
-- 全新库：followup V81 早于本种子执行、彼时角色不存在命中 0 行，由本脚本兜底（V113/V118 同口径）。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 228
FROM sys_role r
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 228
  );
