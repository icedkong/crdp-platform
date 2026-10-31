-- E04-T06 / PLT-AUTH-008、009：在线会话管理的会话超时与单端登录参数 + 权限授予（租户库部分）。
--
-- 并发会话策略（platform-spec 第 7 章）：默认允许同一账号多端登录；租户可改为单端登录
-- （新登录使旧会话下线）。会话空闲超时的租户加严项（security.session.idle-minutes，单位分钟，
-- 只能比平台默认 30 分钟更短）默认不插入——未配置即用平台默认。

INSERT INTO cfg_param (param_key, param_value, remark) VALUES
  ('security.session.single-login', 'false',
   '单端登录开关：true 时同一账号新登录会使旧会话全部下线（E04-T06 / PLT-AUTH-008）');

-- 查看在线用户 / 强制下线按钮（plt_menu 312 / 313，平台库 V11）授予内置租户管理员角色。
-- 三处互斥不重复：老库走本脚本；全新租户库本脚本先于种子 V100（角色未建）执行、插 0 行，
-- 由种子 V103 补授；生产（无种子）由开通编排 ensureAdmin 授予。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 312 AS menu_id
  UNION ALL SELECT 'TENANT_ADMIN', 313
) m ON m.code = r.code
WHERE r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
