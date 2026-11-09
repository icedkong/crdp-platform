-- E19-T07 / A-DASH-009、C-DASH-010（医护负责患者数，P1）：把「统计」页面菜单（plt_menu 42，权限码
-- followup:dashboard:view，平台库 V61 新增）授予内置 TENANT_ADMIN、演示 FOLLOWUP_DOCTOR 与
-- V123 预置的 A 建议角色（病区医生 / 随访员 / 科主任 / 数据录入员——A 规格 §12 的主要权限含首页统计）。
-- 为什么放种子目录：与 V129 / V131 同理——平台库 V61 先于全新租户库角色（种子 V100 / V123 建）执行时
-- 角色尚不存在，授予放这里对老库与全新库都成立（INSERT…SELECT 幂等不重复插入）。
-- 生产租户由角色模板与租户管理员按院分配，不依赖本演示授予。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 42
FROM sys_role r
WHERE r.code IN (
      'TENANT_ADMIN',
      'FOLLOWUP_DOCTOR',
      'A_WARD_DOCTOR',
      'A_FOLLOWUP_NURSE',
      'A_DIRECTOR',
      'A_DATA_CLERK'
    )
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 42
  );
