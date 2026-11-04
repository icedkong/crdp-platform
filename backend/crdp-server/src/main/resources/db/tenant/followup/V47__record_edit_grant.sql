-- E25-T01 / NFR-SAVE-001、NFR-SAVE-003：老库补授「录入随访记录」按钮（plt_menu 222，权限码
-- followup:record:edit，平台库 V43 新增）给内置 TENANT_ADMIN 与演示 FOLLOWUP_DOCTOR——
-- 随访医生日常录入（暂存草稿 / 提交），租户管理员兜底。录入工作台页面随 E15-T02。
-- 全新库本脚本先于种子 V100 执行、角色不存在，命中 0 行，由种子 V118 兜底（V112–V117 同口径）。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 222
FROM sys_role r
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 222
  );
