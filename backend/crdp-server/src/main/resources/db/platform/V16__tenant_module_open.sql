-- 按租户开通 / 关闭模块、依赖校验、模块级配置与开通记录（E05-T05 / PLT-MOD-004、005、008、009）。
-- plt_tenant_module 补开通痕迹列：状态枚举扩展为 OPENING / OPEN / OPEN_FAILED / MAINTENANCE / CLOSED
-- （平台规格 §3 关键字段·租户模块开通），迁移版本对齐目录注册值，开通时间 / 关闭时间 / 操作人 / 原因。
-- 存量 OPEN 行（种子演示租户）回填目录里的迁移版本与开通时间。

ALTER TABLE plt_tenant_module
  ADD COLUMN migration_version VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN opened_at DATETIME(3) NULL,
  ADD COLUMN closed_at DATETIME(3) NULL,
  ADD COLUMN operator_id BIGINT NULL,
  ADD COLUMN reason VARCHAR(200) NULL;

UPDATE plt_tenant_module m
  JOIN plt_module p ON p.code = m.module_code
  SET m.migration_version = p.migration_version,
      m.opened_at = CURRENT_TIMESTAMP(3)
  WHERE m.status = 'OPEN' AND m.opened_at IS NULL;

-- 模块级配置（PLT-MOD-008）：每租户的模块参数覆盖值，缺省取模块定义（代码登记的配置项清单）。
CREATE TABLE plt_tenant_module_config (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  module_code VARCHAR(32) NOT NULL,
  config_code VARCHAR(64) NOT NULL,
  config_value VARCHAR(255) NOT NULL,
  updated_by BIGINT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_tenant_module_config (tenant_id, module_code, config_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 模块开通记录（PLT-MOD-009）：开通、关闭（升级 UPGRADE 随 PLT-MOD-007 的灰度批次写入）。
CREATE TABLE plt_tenant_module_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  module_code VARCHAR(32) NOT NULL,
  action VARCHAR(16) NOT NULL,
  reason VARCHAR(200) NULL,
  detail VARCHAR(512) NULL,
  operator_id BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_plt_tenant_module_log (module_code, id),
  KEY idx_plt_tenant_module_log_tenant (tenant_id, module_code, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 权限点（平台规格 §3）：为租户开通 / 关闭模块的按钮权限，授予 PLATFORM_ADMIN（显式快照，见 V14 注释）。
INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (122, 111, 'PLATFORM', 'BUTTON', 'base', '开通模块', NULL, NULL, NULL, 'platform:tenant:module:open', NULL, 10, 1, 'ENABLED'),
  (123, 111, 'PLATFORM', 'BUTTON', 'base', '关闭模块', NULL, NULL, NULL, 'platform:tenant:module:close', NULL, 20, 1, 'ENABLED');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (122, 123);
