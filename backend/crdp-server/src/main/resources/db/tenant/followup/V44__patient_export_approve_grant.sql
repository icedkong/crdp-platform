-- E13-T09 / A-PAT-009：老库补授「审批患者导出」按钮（plt_menu 218，权限码
-- followup:patient:export:approve，平台库 V41 新增）给内置 TENANT_ADMIN——租户普通用户导出由
-- 租户管理员审批（platform-spec §9 已确认）。217「删除患者」与 212「导出患者」同为敏感权限
-- （plt_sensitive_perm），默认不授、平台按租户单独放开（PLT-SCOPE-004，与 211 查看明文同口径）。
-- 全新库本脚本先于种子 V100 执行、角色不存在，命中 0 行，由种子 V116 兜底。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 218
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 218
  );
