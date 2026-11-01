-- E06-T07 / PLT-ORG-006：用户管理所需字段（platform-spec §6「关键字段 · 用户」）。
--   staff_no      工号（姓名之外的身份标识，预留用于后续单点登录映射；可空）
--   main_dept_id  主科室（sys_dept.id，必填由接口层校验；决定「本科室」类数据范围，生效口径 E07-T02）
-- 负责病区（sys_user_ward）与负责科室（sys_user_dept_scope）多选关联表 V1 已建，本任务只落维护入口。
-- 兼职科室、岗位、职称、账号有效期随字典（E06-T10）与数据范围生效计算（E07-T02）落地。

ALTER TABLE sys_user
  ADD COLUMN staff_no VARCHAR(64) NULL COMMENT '工号，预留用于单点登录映射' AFTER display_name,
  ADD COLUMN main_dept_id BIGINT NULL COMMENT '主科室（sys_dept.id），决定本科室类数据范围' AFTER staff_no;

-- 「新建、编辑、停用与离职用户」按钮（plt_menu 314，权限码 tenant:user:edit，平台库 V23 新增）
-- 授予内置 TENANT_ADMIN 角色。三处互斥不重复：老库走本脚本；全新租户库本脚本先于种子 V100
-- （角色未建）执行、插 0 行，由种子 V107 补授；生产（无种子）由开通编排 ensureAdmin 授予。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 314 AS menu_id
) m ON m.code = r.code
WHERE r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
