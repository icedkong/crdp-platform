-- E19-T06 / A-DASH-013：给 V123 预置的 A 建议角色补授「首页统计卡片」按钮（plt_menu 234，
-- 权限码 followup:dashboard:view，平台库 V59 新增、种子 V129 只授了 TENANT_ADMIN 与 FOLLOWUP_DOCTOR）。
-- A 规格 §10 建议角色模板：病区医生 / 随访员 / 科主任 / 数据录入员的「主要权限」都含「首页」；
-- E19-T06 的按角色布局要在这些角色上生效（验收「随访员首页只显示这两张卡片」），先补齐卡片权限——
-- 卡片配置只决定显示与顺序，不放宽数据权限（A 规格 §9）。
-- 为什么放种子目录：与 V130 同理——V123 建 A_* 角色先于本脚本按版本序执行，INSERT…SELECT 幂等。

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 234
FROM sys_role r
WHERE r.code IN ('A_WARD_DOCTOR', 'A_FOLLOWUP_NURSE', 'A_DIRECTOR', 'A_DATA_CLERK')
  AND r.status = 'ENABLED'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 234
  );
