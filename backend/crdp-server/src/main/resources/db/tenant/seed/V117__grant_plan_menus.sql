-- E14-T01 / A-FU-016、A-FU-018、A-FU-019：全新库兜底授予「随访计划」菜单（plt_menu 24/25，平台库 V42）
-- 与操作按钮（219 编辑 / 220 发布 / 221 入组）。followup V46 在全新库先于种子 V100 执行（角色不存在
-- 命中 0 行），本脚本在角色建立后补授，与 V112–V116 同口径；INSERT 幂等不会重复插。
-- 授予口径：计划配置（24/219/220）由租户管理员维护 → TENANT_ADMIN；入组与暂停 / 恢复 / 移出（221）
-- 是医生日常操作 → 加授演示 FOLLOWUP_DOCTOR。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 24 AS menu_id
  UNION ALL SELECT 'TENANT_ADMIN', 25
  UNION ALL SELECT 'TENANT_ADMIN', 219
  UNION ALL SELECT 'TENANT_ADMIN', 220
  UNION ALL SELECT 'TENANT_ADMIN', 221
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 24
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 25
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 221
) m ON m.code = r.code
WHERE r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
