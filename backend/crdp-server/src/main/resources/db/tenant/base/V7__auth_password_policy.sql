-- E04-T05 / PLT-AUTH-006、007、010：密码策略、失败锁定与首次登录改密（租户库部分）。
-- 密码有效期：90 天（等保参数表），到期强制修改；password_updated_at 随每次改密刷新。
-- 历史密码：不得与最近 5 次相同；每次改密把被替换的旧哈希追加进历史表（只增不改）。

ALTER TABLE sys_user
  ADD COLUMN password_updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    COMMENT '密码最后修改时间，密码有效期 90 天从此起算';

CREATE TABLE sys_password_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_sys_password_history_user (user_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='历史密码哈希，只追加；改密时校验不得与最近 5 次相同';

-- 租户可在平台下限（8 位）之上加严最小长度（PLT-AUTH-006「平台设下限，租户可加严」）。
-- 取值非法时按平台下限处理（HospitalDirectory.passwordMinLengthOverride）。
INSERT INTO cfg_param (param_key, param_value, remark) VALUES
  ('security.password.min-length', '8',
   '密码最小长度：平台下限 8，租户只能在此之上加严（E04-T05）');

-- 解锁 / 重置权限码（tenant:user:reset，system.md §2）挂在用户管理菜单下的隐藏按钮上；
-- 按钮行在平台库 V10 新增（plt_menu id=311），这里授予内置租户管理员角色。
-- 注意：本脚本对「已在本角色存在」的库生效（老库增量升级）；全新租户库按版本号排序执行，
-- 本脚本先于种子 V100（创建 TENANT_ADMIN）运行、插入 0 行，由种子 V102 补授，
-- 生产（无种子）由开通编排 ensureAdmin 授予。NOT EXISTS 保证三处互不重复。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 311
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN' AND r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 311
  );
