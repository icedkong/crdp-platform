-- E06-T04 / PLT-ROLE-002、003：四个平台预置角色（平台管理员、实施人员、运维人员、客服支持）
-- 及其默认权限，均已确认（platform-spec §1「关键字段 · 角色定义」「§8 预置平台角色的默认视角权限」；
-- 平台管理员后续可在「平台角色」页调整）。
-- 放种子目录：PLT_ROLE 的 PLATFORM_ADMIN 行由种子 V100 创建（与模块目录、演示账号同口径，
-- 生产不含种子、控制面初始数据由平台初始化时登记）；本脚本幂等（INSERT IGNORE + UPDATE），
-- 全新 local/dev 库（V100 之后）与已应用 V100 的老库补跑结果一致。
-- 默认菜单授予是执行时快照（与 V13 同口径）：之后新迁移新增的平台端菜单不自动跟随，
-- 由平台管理员在「平台角色」页调整。预置角色不可删除（接口不提供删除，规格 §1 主要页面）。

INSERT IGNORE INTO plt_role
  (code, name, description, built_in, status,
   view_can_enter, view_visible_scope, view_tenant_scope,
   view_can_configure, view_can_reset_password, view_can_edit_business) VALUES
  ('IMPLEMENTATION', '实施人员',
   '租户开通、模块开通、菜单与授权范围配置；租户视角内代为配置；业务数据脱敏只读',
   1, 'ENABLED', 1, 'CONFIG_AND_BUSINESS', 'ALL', 1, 0, 0),
  ('OPERATIONS', '运维人员',
   '查看租户配置、资源状态、日志与在线会话；租户视角仅看配置类',
   1, 'ENABLED', 1, 'CONFIG_ONLY', 'ALL', 0, 0, 0),
  ('SUPPORT', '客服支持',
   '在负责租户内进入租户视角排查问题；业务数据脱敏只读；代为重置医院用户密码',
   1, 'ENABLED', 1, 'CONFIG_AND_BUSINESS', 'RESPONSIBLE', 0, 1, 0);

-- 平台管理员：补默认视角权限与职责说明（角色行由 V100 创建，本列 V20 才出现）
UPDATE plt_role
SET description = '全部平台权限：租户、模块、菜单与权限资源、授权范围、平台账号与角色、视角规则配置与复核；租户视角内可编辑业务数据',
    view_can_enter = 1,
    view_visible_scope = 'CONFIG_AND_BUSINESS',
    view_tenant_scope = 'ALL',
    view_can_configure = 1,
    view_can_reset_password = 1,
    view_can_edit_business = 1
WHERE code = 'PLATFORM_ADMIN' AND built_in = 1;

-- 平台管理员：全部平台端启用菜单（与种子 V100 同款动态授予口径，幂等）
INSERT IGNORE INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.side = 'PLATFORM' AND m.status = 'ENABLED';

-- 预置角色的菜单都带上级目录（110 平台管理）：菜单树按 parentId 组树，没有目录行子页面不下发。
-- 实施人员：工作台 + 租户管理 + 模块目录 + 菜单资源 + 授权范围模板（开通与配置类；无维护按钮）
INSERT IGNORE INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r JOIN plt_menu m ON m.id IN (100, 110, 111, 112, 113, 124)
WHERE r.code = 'IMPLEMENTATION' AND m.side = 'PLATFORM' AND m.status = 'ENABLED';

-- 运维人员：工作台 + 租户管理 + 模块目录 + 菜单资源（只读查看；日志与在线会话页随任务落地再授予）
INSERT IGNORE INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r JOIN plt_menu m ON m.id IN (100, 110, 111, 112, 113)
WHERE r.code = 'OPERATIONS' AND m.side = 'PLATFORM' AND m.status = 'ENABLED';

-- 客服支持：工作台 + 租户管理（进入负责租户的租户视角入口；视角会话属 E20-T01）
INSERT IGNORE INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r JOIN plt_menu m ON m.id IN (100, 110, 111)
WHERE r.code = 'SUPPORT' AND m.side = 'PLATFORM' AND m.status = 'ENABLED';
