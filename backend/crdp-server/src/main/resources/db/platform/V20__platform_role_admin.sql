-- E06-T04 / PLT-ROLE-001..003：账号类型模型、平台预置角色与平台自定义角色。
--   plt_role 补角色定义列：职责说明 + 租户视角权限（平台规格 §8「关键字段 · 平台角色视角权限」，
--   docs/api/platform.md §5：canEnter / visibleScope / tenantScope / canConfigure /
--   canResetPassword / canEditBusinessData）。视角会话与「指定租户」清单的执行属 E20-T01
--   （PLT-VIEW-005、006），本任务先落角色上的配置与四个预置角色的默认值（种子 V104）。
-- 新菜单与按钮权限点（platform-spec §1 权限点表，码创建后不改名，OQ-11）：
--   platform:role:view（菜单 130）、platform:role:edit（按钮 131）、
--   platform:account:edit（按钮 141）、platform:account:reset（按钮 142）。
--   （110–126 与 132–134 已被 V4–V18 的平台菜单 / 按钮占用，130 / 131 / 141 / 142 为空闲号段。）
-- 授予 PLATFORM_ADMIN 与 V13/V17 同款做法：主迁移 INSERT..SELECT（全新库此时还没有 PLATFORM_ADMIN
-- 行，命中 0 行无害；种子 V100 会把当时的全部平台端菜单动态授予它；老库由本脚本补授）。

ALTER TABLE plt_role
  ADD COLUMN description VARCHAR(255) NULL AFTER name,
  ADD COLUMN view_can_enter TINYINT NOT NULL DEFAULT 0 COMMENT '可进入租户视角（PLT-VIEW-005）',
  ADD COLUMN view_visible_scope VARCHAR(32) NOT NULL DEFAULT 'CONFIG_ONLY'
    COMMENT '可见范围：CONFIG_ONLY / CONFIG_AND_BUSINESS',
  ADD COLUMN view_tenant_scope VARCHAR(32) NOT NULL DEFAULT 'RESPONSIBLE'
    COMMENT '租户范围：ALL / RESPONSIBLE / SPECIFIED（指定租户清单属 E20-T01）',
  ADD COLUMN view_can_configure TINYINT NOT NULL DEFAULT 0 COMMENT '可代为配置',
  ADD COLUMN view_can_reset_password TINYINT NOT NULL DEFAULT 0 COMMENT '可代重置医院用户密码',
  ADD COLUMN view_can_edit_business TINYINT NOT NULL DEFAULT 0 COMMENT '可编辑业务数据',
  ADD COLUMN created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  ADD COLUMN updated_at DATETIME(3) NULL;

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (130, 110, 'PLATFORM', 'MENU', 'base', '平台角色', 'PlatformRole', '/platform/role', 'platform/role/index', 'platform:role:view', 'TeamOutlined', 60, 0, 'ENABLED'),
  (131, 130, 'PLATFORM', 'BUTTON', 'base', '维护平台角色', NULL, NULL, NULL, 'platform:role:edit', NULL, 10, 1, 'ENABLED'),
  (141, 114, 'PLATFORM', 'BUTTON', 'base', '维护平台账号', NULL, NULL, NULL, 'platform:account:edit', NULL, 10, 1, 'ENABLED'),
  (142, 114, 'PLATFORM', 'BUTTON', 'base', '重置平台账号密码与解锁', NULL, NULL, NULL, 'platform:account:reset', NULL, 20, 1, 'ENABLED');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (130, 131, 141, 142);
