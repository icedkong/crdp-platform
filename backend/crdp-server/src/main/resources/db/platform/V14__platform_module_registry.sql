-- 模块目录、注册同步与版本记录（E05-T04 / PLT-MOD-001、002、003）。
-- plt_module 补随发版注册的版本与依赖列；名称、说明、上架状态（status）仍由平台端维护，
-- 注册同步不覆盖。plt_module_version 记录每次注册 / 升级的功能版本、迁移版本与变更说明。

ALTER TABLE plt_module
  ADD COLUMN feature_version VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN migration_version VARCHAR(32) NOT NULL DEFAULT '',
  ADD COLUMN depends_on VARCHAR(255) NOT NULL DEFAULT '',
  ADD COLUMN synced_at DATETIME(3) NULL;

CREATE TABLE plt_module_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  module_code VARCHAR(32) NOT NULL,
  feature_version VARCHAR(32) NOT NULL,
  migration_version VARCHAR(32) NOT NULL,
  change_note VARCHAR(512) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_plt_module_version (module_code, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 维护模块展示信息与上下架的按钮权限（PLT-MOD-002）。
-- 权限码创建后不改名（OQ-11）；显式授予 PLATFORM_ADMIN（种子授权是执行时快照，见 V5/V8 注释）。
INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (121, 112, 'PLATFORM', 'BUTTON', 'base', '维护模块信息', NULL, NULL, NULL, 'platform:module:edit', NULL, 10, 1, 'ENABLED');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id = 121;
