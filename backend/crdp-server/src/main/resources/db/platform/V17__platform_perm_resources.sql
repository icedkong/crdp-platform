-- E05-T06 / PLT-PERM-001..004、011：全局菜单树维护、接口资源登记与清单同步。
--   plt_menu.source —— 菜单项来源：MANIFEST（随模块清单发版同步，V17 前的种子行都是模块清单物化）
--     或 CUSTOM（平台端在菜单管理页新建，清单同步不触碰）；删除为软删（status='DELETED'）。
--   plt_api_resource.source / synced_at —— 接口资源来源（SEED=种子、SCAN=启动扫描登记）与最近同步时间；
--     (http_method, path_pattern) 唯一，保证启动扫描幂等（未登记接口默认拒绝，见 ApiResourceGuardFilter）。
-- 新权限点（platform-spec §4 权限点表）：platform:api:view（菜单 125）、platform:api:edit（按钮 126）、
--   platform:menu:edit（按钮 132）；显式授予 PLATFORM_ADMIN（与 V13 同款做法，种子授权是执行时快照）。

ALTER TABLE plt_menu
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'MANIFEST' AFTER status;

ALTER TABLE plt_api_resource
  ADD COLUMN source VARCHAR(16) NOT NULL DEFAULT 'SEED' AFTER action_type,
  ADD COLUMN synced_at DATETIME(3) NULL AFTER source,
  ADD UNIQUE KEY uk_plt_api_method_path (http_method, path_pattern);

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status, source) VALUES
  (125, 110, 'PLATFORM', 'MENU', 'base', '接口资源', 'PlatformApi', '/platform/api', 'platform/api/index', 'platform:api:view', 'ApiOutlined', 70, 0, 'ENABLED', 'MANIFEST'),
  (126, 125, 'PLATFORM', 'BUTTON', 'base', '标记接口属性', NULL, NULL, NULL, 'platform:api:edit', NULL, 10, 1, 'ENABLED', 'MANIFEST'),
  (132, 113, 'PLATFORM', 'BUTTON', 'base', '维护菜单与按钮', NULL, NULL, NULL, 'platform:menu:edit', NULL, 10, 1, 'ENABLED', 'MANIFEST');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (125, 126, 132);
