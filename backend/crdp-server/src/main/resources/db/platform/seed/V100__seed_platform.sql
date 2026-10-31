-- 虚构演示数据。没有真实医院、真实人员和真实口令。
-- 口令哈希由应用在 local/dev 启动时替换 PENDING_DEMO_HASH，明文只写在配置与 README。

INSERT INTO plt_module (code, name, description, base_module, status) VALUES
  ('base', '平台基础', '组织、用户、角色与菜单等底座能力', 1, 'ON'),
  ('followup', '随访', '医院 A、B、C 共用的随访模块，差异由模板和功能开关承担', 0, 'ON');

INSERT INTO plt_tenant (id, code, name, status, remark) VALUES
  (1, 'demoa', '虚构示例医院甲', 'ENABLED', '本地演示租户，库名 t_demoa，不含真实患者'),
  (2, 'demob', '虚构示例医院乙', 'ENABLED', '本地演示租户，库名 t_demob，不含真实患者');

INSERT INTO plt_tenant_resource
  (tenant_id, db_name, jdbc_url, db_username, password_cipher, secret_mode) VALUES
  (1, 't_demoa',
   'jdbc:mysql://127.0.0.1:13306/t_demoa?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false',
   'crdp_app', 'local:DevOnly#app2026', 'local'),
  (2, 't_demob',
   'jdbc:mysql://127.0.0.1:13306/t_demob?useUnicode=true&characterEncoding=utf8&connectionTimeZone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false',
   'crdp_app', 'local:DevOnly#app2026', 'local');

INSERT INTO plt_tenant_module (tenant_id, module_code, status) VALUES
  (1, 'base', 'OPEN'),
  (1, 'followup', 'OPEN'),
  (2, 'base', 'OPEN'),
  (2, 'followup', 'OPEN');

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (10, 0, 'HOSPITAL', 'MENU', 'base', '工作台', 'HospitalDashboard', '/dashboard', 'dashboard/index', NULL, 'DashboardOutlined', 10, 0, 'ENABLED'),
  (20, 0, 'HOSPITAL', 'DIR', 'followup', '随访', 'Followup', '/followup', 'LAYOUT', NULL, 'MedicineBoxOutlined', 20, 0, 'ENABLED'),
  (21, 20, 'HOSPITAL', 'MENU', 'followup', '患者档案', 'FollowupPatient', '/followup/patient', 'followup/patient/index', 'followup:patient:view', NULL, 10, 0, 'ENABLED'),
  (22, 20, 'HOSPITAL', 'MENU', 'followup', '随访任务', 'FollowupTask', '/followup/task', 'followup/task/index', 'followup:task:view', NULL, 20, 0, 'ENABLED'),
  (23, 20, 'HOSPITAL', 'MENU', 'followup', '报告单', 'FollowupReport', '/followup/report', 'followup/report/index', 'followup:report:view', NULL, 30, 0, 'ENABLED'),
  (211, 21, 'HOSPITAL', 'BUTTON', 'followup', '查看明文', NULL, NULL, NULL, 'followup:patient:plain', NULL, 10, 1, 'ENABLED'),
  (212, 21, 'HOSPITAL', 'BUTTON', 'followup', '导出患者', NULL, NULL, NULL, 'followup:patient:export', NULL, 20, 1, 'ENABLED'),
  (30, 0, 'HOSPITAL', 'DIR', 'base', '系统管理', 'HospitalSystem', '/system', 'LAYOUT', NULL, 'SettingOutlined', 30, 0, 'ENABLED'),
  (31, 30, 'HOSPITAL', 'MENU', 'base', '用户管理', 'HospitalUser', '/system/user', 'system/user/index', 'tenant:user:view', NULL, 10, 0, 'ENABLED'),
  (32, 30, 'HOSPITAL', 'MENU', 'base', '角色管理', 'HospitalRole', '/system/role', 'system/role/index', 'tenant:role:view', NULL, 20, 0, 'ENABLED'),
  (33, 30, 'HOSPITAL', 'MENU', 'base', '科室管理', 'HospitalDept', '/system/dept', 'system/dept/index', 'tenant:dept:view', NULL, 30, 0, 'ENABLED'),
  (100, 0, 'PLATFORM', 'MENU', 'base', '工作台', 'PlatformDashboard', '/dashboard', 'dashboard/index', NULL, 'DashboardOutlined', 10, 0, 'ENABLED'),
  (110, 0, 'PLATFORM', 'DIR', 'base', '平台管理', 'PlatformAdmin', '/platform', 'LAYOUT', NULL, 'BankOutlined', 20, 0, 'ENABLED'),
  (111, 110, 'PLATFORM', 'MENU', 'base', '租户管理', 'PlatformTenant', '/platform/tenant', 'platform/tenant/index', 'platform:tenant:view', NULL, 10, 0, 'ENABLED'),
  (112, 110, 'PLATFORM', 'MENU', 'base', '模块目录', 'PlatformModule', '/platform/module', 'platform/module/index', 'platform:module:view', NULL, 20, 0, 'ENABLED'),
  (113, 110, 'PLATFORM', 'MENU', 'base', '菜单资源', 'PlatformMenu', '/platform/menu', 'platform/menu/index', 'platform:menu:view', NULL, 30, 0, 'ENABLED'),
  (114, 110, 'PLATFORM', 'MENU', 'base', '平台账号', 'PlatformAccount', '/platform/account', 'platform/account/index', 'platform:account:view', NULL, 40, 0, 'ENABLED');

ALTER TABLE plt_menu AUTO_INCREMENT = 1000;

INSERT INTO plt_api_resource
  (http_method, path_pattern, perm_code, module_code, public_access, audit_level, sensitive_level, action_type) VALUES
  ('POST', '/api/hospital/auth/login', NULL, 'base', 1, 'LOGIN', 'NORMAL', 'QUERY'),
  ('POST', '/api/platform/auth/login', NULL, 'base', 1, 'LOGIN', 'NORMAL', 'QUERY'),
  ('GET', '/api/hospital/hello', NULL, 'base', 0, 'NONE', 'NORMAL', 'QUERY'),
  ('GET', '/api/hospital/menus', NULL, 'base', 0, 'NONE', 'NORMAL', 'QUERY'),
  ('GET', '/api/hospital/followup/ping', 'followup:task:view', 'followup', 0, 'NONE', 'NORMAL', 'QUERY');

INSERT INTO plt_account (username, display_name, password_hash, status) VALUES
  ('platform.admin', '平台演示管理员', 'PENDING_DEMO_HASH', 'ENABLED');

INSERT INTO plt_role (code, name, built_in, status) VALUES
  ('PLATFORM_ADMIN', '平台管理员', 1, 'ENABLED');

INSERT INTO plt_account_role (account_id, role_id)
SELECT a.id, r.id FROM plt_account a, plt_role r
WHERE a.username = 'platform.admin' AND r.code = 'PLATFORM_ADMIN';

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.side = 'PLATFORM';
