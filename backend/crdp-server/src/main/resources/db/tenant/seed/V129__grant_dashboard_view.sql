-- E19-T02 / A-DASH-001、A-DASH-002、C-DASH-001：把「首页统计卡片」按钮（plt_menu 234，权限码
-- followup:dashboard:view，平台库 V59 新增）授予内置 TENANT_ADMIN 与演示 FOLLOWUP_DOCTOR。
-- 为什么放种子目录：平台库 V59 先于全新租户库角色（种子 V100 建 TENANT_ADMIN / FOLLOWUP_DOCTOR）
-- 执行时角色尚不存在——租户库角色在开通编排 / 种子里建，授予放这里对老库与全新库都成立
-- （V125 / V126 同款做法；INSERT…SELECT 幂等不重复插入）。
-- A 规格角色模板（第 12 章）医生 / 随访员 / 护士 / 科主任的主要权限都含「首页」；
-- 生产租户由角色模板与租户管理员按院分配，不依赖本演示授予。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 234
FROM sys_role r
WHERE r.code IN ('TENANT_ADMIN', 'FOLLOWUP_DOCTOR')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 234
  );
