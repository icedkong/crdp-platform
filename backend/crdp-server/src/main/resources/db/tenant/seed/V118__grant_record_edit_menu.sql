-- E25-T01 / NFR-SAVE-001、NFR-SAVE-003：全新库兜底授予「录入随访记录」按钮（plt_menu 222，
-- 权限码 followup:record:edit，平台库 V43）。followup V47 在全新库先于种子 V100 执行（角色不存在
-- 命中 0 行），本脚本在角色建立后补授，与 V112–V117 同口径；INSERT 幂等不会重复插。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 222
FROM sys_role r
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 222
  );
