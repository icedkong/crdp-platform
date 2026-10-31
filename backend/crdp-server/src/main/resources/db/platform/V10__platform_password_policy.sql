-- E04-T05 / PLT-AUTH-006、010：平台账号的首次登录改密与密码策略支撑（平台库部分）。
-- plt_account 与租户库 sys_user 同构：must_change_password（初始 / 重置后强制改密）
-- 与 password_updated_at（90 天有效期起算）；历史密码表只追加。

ALTER TABLE plt_account
  ADD COLUMN must_change_password TINYINT NOT NULL DEFAULT 0
    COMMENT '初始密码或重置后首次登录强制修改（PLT-AUTH-010）',
  ADD COLUMN password_updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    COMMENT '密码最后修改时间，密码有效期 90 天从此起算';

CREATE TABLE plt_password_history (
  id BIGINT NOT NULL AUTO_INCREMENT,
  account_id BIGINT NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_plt_password_history_account (account_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='平台账号历史密码哈希，只追加；改密时校验不得与最近 5 次相同';

-- 解锁 / 重置密码按钮（权限码 tenant:user:reset，docs/api/system.md §2，隐藏按钮）：
-- 用户管理页（E06+）落地前，解锁接口先可用（租户管理员角色由租户库 V7 授予本菜单）。
INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (311, 31, 'HOSPITAL', 'BUTTON', 'base', '重置密码与解锁', NULL, NULL, NULL, 'tenant:user:reset', NULL, 10, 1, 'ENABLED');
