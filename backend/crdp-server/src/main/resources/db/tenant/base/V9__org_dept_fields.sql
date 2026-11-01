-- E06-T02 / PLT-ORG-001、002：科室树维护所需的科室字段（platform-spec「关键字段 · 院区与科室」）。
--   dept_type       科室类型：临床 / 医技 / 职能 / 随访中心等（规格为字典，PLT-CFG-001 平台字典随 E06-T10 落地，
--                   先以字符串存储，字典可用后切换为字典项引用）
--   leader_user_id  负责人（sys_user.id，可空）
--   his_code        HIS 科室编码（与院内系统映射；Excel 导入 / HIS 同步属 E06-T03，本任务先落字段）
--   sort_no         排序号，小者在前（同院区同层级内生效）
-- 院区（sys_org）字段不变：编码、名称、状态（V1）。

ALTER TABLE sys_dept
  ADD COLUMN dept_type VARCHAR(32) NULL AFTER name,
  ADD COLUMN leader_user_id BIGINT NULL AFTER dept_type,
  ADD COLUMN his_code VARCHAR(64) NULL AFTER leader_user_id,
  ADD COLUMN sort_no INT NOT NULL DEFAULT 0 AFTER his_code;

-- 「维护院区与科室」按钮（plt_menu 331，平台库 V19）授予内置租户管理员角色。
-- 三处互斥不重复：老库走本脚本；全新租户库本脚本先于种子 V100（角色未建）执行、插 0 行，
-- 由种子 V104 补授；生产（无种子）由开通编排 ensureAdmin 授予。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 331 AS menu_id
) m ON m.code = r.code
WHERE r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
