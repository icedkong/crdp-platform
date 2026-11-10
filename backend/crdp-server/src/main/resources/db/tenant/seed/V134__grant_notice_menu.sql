-- E23-T09 / UAT-SELF-04（A-NTF-001、A 规格 §10）：「通知设置」菜单（plt_menu 36，
-- followup:notice:config，平台库 V32 新增）授予内置 TENANT_ADMIN 角色。
-- 根因：followup V22 的同一授予在全新租户库先于种子 V100（创建 TENANT_ADMIN 角色）执行，
-- 角色尚不存在、INSERT…SELECT 命中 0 行——与 base V7 / V10 的授予在新库落空、由种子
-- V102 / V105 兜底是同一类问题；V123 的医院 A 预置角色按 §10 口径也不持有通知设置。
-- 后果（自查实测）：任何角色（含租户管理员）打开通知设置 40300，G-3 / A-NTF-001
-- 逐事件规则配置页完全不可用。本脚本在 V100 建好角色之后补授；老库已由 V22 命中，
-- 幂等不会重复插。A 规格 §10 租户管理员「主要权限」列明确包含通知设置。
-- 生产不执行种子（同 V102 / V105 口径）：真实租户由开通向导 ensureAdmin + 配置模板
-- （sys_role_menu 随模板导入）落地权限。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (SELECT 'TENANT_ADMIN' AS code, 36 AS menu_id) m ON m.code = r.code
WHERE r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
