-- 平台库。只放控制面，不放医院用户与患者数据。
-- 表清单以技术方案第 15 章为准。M0 只建登录、菜单、模块开通和审计需要的表，
-- 其余表在对应 Epic 的新脚本里追加，不在这里猜测字段。

CREATE TABLE plt_tenant (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(32) NOT NULL,
  name VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  remark VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_tenant_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_tenant_resource (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  db_name VARCHAR(64) NOT NULL,
  jdbc_url VARCHAR(512) NOT NULL,
  db_username VARCHAR(64) NOT NULL,
  password_cipher VARCHAR(512) NOT NULL,
  secret_mode VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_tenant_resource (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_module (
  code VARCHAR(32) NOT NULL,
  name VARCHAR(64) NOT NULL,
  description VARCHAR(255) NOT NULL,
  base_module TINYINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_tenant_module (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  module_code VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_tenant_module (tenant_id, module_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_menu (
  id BIGINT NOT NULL AUTO_INCREMENT,
  parent_id BIGINT NOT NULL DEFAULT 0,
  side VARCHAR(16) NOT NULL,
  type VARCHAR(16) NOT NULL,
  module_code VARCHAR(32) NOT NULL,
  title VARCHAR(64) NOT NULL,
  route_name VARCHAR(64) NULL,
  path VARCHAR(128) NULL,
  component VARCHAR(128) NULL,
  perm_code VARCHAR(64) NULL,
  icon VARCHAR(64) NULL,
  sort_no INT NOT NULL DEFAULT 0,
  hidden TINYINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_menu_perm (perm_code),
  KEY idx_plt_menu_side (side, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_api_resource (
  id BIGINT NOT NULL AUTO_INCREMENT,
  http_method VARCHAR(16) NOT NULL,
  path_pattern VARCHAR(160) NOT NULL,
  perm_code VARCHAR(64) NULL,
  module_code VARCHAR(32) NOT NULL,
  public_access TINYINT NOT NULL,
  audit_level VARCHAR(32) NOT NULL,
  sensitive_level VARCHAR(16) NOT NULL,
  action_type VARCHAR(16) NOT NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_account (
  id BIGINT NOT NULL AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL,
  display_name VARCHAR(64) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_account_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_role (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(64) NOT NULL,
  built_in TINYINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_account_role (
  account_id BIGINT NOT NULL,
  role_id BIGINT NOT NULL,
  PRIMARY KEY (account_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 概要清单没有角色与菜单的关联表。平台端动态菜单需要它，M0 补上。见 ADR。
CREATE TABLE plt_role_menu (
  role_id BIGINT NOT NULL,
  menu_id BIGINT NOT NULL,
  PRIMARY KEY (role_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_audit_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_type VARCHAR(64) NOT NULL,
  actor_id BIGINT NULL,
  tenant_id BIGINT NULL,
  action VARCHAR(64) NOT NULL,
  target VARCHAR(128) NULL,
  detail VARCHAR(512) NULL,
  trace_id VARCHAR(64) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_plt_audit_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ShedLock 锁表。调度依赖尚未引入。
CREATE TABLE shedlock (
  name VARCHAR(64) NOT NULL,
  lock_until TIMESTAMP(3) NOT NULL,
  locked_at TIMESTAMP(3) NOT NULL,
  locked_by VARCHAR(255) NOT NULL,
  PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
