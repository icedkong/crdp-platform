-- E13-T07 / A-PAT-016、A-PAT-017、C-PAT-016：老库补授「批量指定责任医生」按钮（plt_menu 224，
-- 权限码 followup:task:assign）与「批量入组」隐藏路由（plt_menu 225，平台库 V44 新增）给内置
-- TENANT_ADMIN 与演示 FOLLOWUP_DOCTOR——病区医生为本病区患者批量入组与改派责任医生是日常操作
-- （A-PAT-016/017「在患者列表或批量入组页」；批量入组操作权限沿用 221 followup:plan:enroll）。
-- 全新库本脚本先于种子 V100 执行、角色不存在，命中 0 行，由种子 V119 兜底（V112–V118 同口径）。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 224 AS menu_id
  UNION ALL SELECT 'TENANT_ADMIN', 225
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 224
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 225
) m ON m.code = r.code
WHERE r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
