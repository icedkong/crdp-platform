-- E13-T09 / A-PAT-009：全新库兜底授予「审批患者导出」按钮（plt_menu 218，平台库 V41）给
-- TENANT_ADMIN。followup V44 在全新库先于种子 V100 执行（角色不存在命中 0 行），本脚本在角色
-- 建立后补授，与 V112–V115 同口径；INSERT 幂等不会重复插。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 218
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 218
  );
