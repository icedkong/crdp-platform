-- E18-T06 / A-PAT-008、A-PAT-018：把「导入向导」菜单（plt_menu 353，权限码
-- followup:patient:import，平台库 V58 新增）授予内置 TENANT_ADMIN 与演示 FOLLOWUP_DOCTOR
-- 角色（与新建患者 213 同口径：医生可见病区外的行在确认提交时按冻结范围跳过并计数，E18-T05）。
-- 全新库本脚本先于种子 V100 执行、角色不存在，命中 0 行，由种子 V128 兜底。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 353
FROM sys_role r
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 353
  );
