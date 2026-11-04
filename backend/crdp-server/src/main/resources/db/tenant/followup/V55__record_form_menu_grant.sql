-- E15-T02 / A-FU-004、A-FU-005、C-FU-020：老库补授「随访录入」隐藏路由（plt_menu 223，
-- 平台库 V45 新增）给内置 TENANT_ADMIN 与演示 FOLLOWUP_DOCTOR——随访医生日常录入，
-- 租户管理员兜底（与 followup V47 / 平台库 V43 同口径）。
-- 全新库本脚本先于种子 V100 执行、角色不存在，命中 0 行，由种子 V120 兜底（V112–V119 同口径）。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 223
FROM sys_role r
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 223
  );
