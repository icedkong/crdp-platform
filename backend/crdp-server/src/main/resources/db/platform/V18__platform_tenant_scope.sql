-- 租户授权范围、可分配范围、模板、敏感权限、影响分析与自动收回
-- （E05-T07 / PLT-SCOPE-001..006、008，验收：收回范围后租户角色同步收回）。
--   授权范围不落表：= 该租户已开通模块（plt_tenant_module.status='OPEN'）下的医院端启用菜单与按钮，
--   随模块开通 / 关闭自动扩张 / 收缩（PLT-SCOPE-001）。
--   plt_scope_template（PLT-SCOPE-003）——按模块预置默认可分配项（白名单）：清单同步新增的菜单项
--   不在模板内 → 默认不进入可分配范围，须平台确认（改模板）后对租户生效。
--   plt_sensitive_perm（PLT-SCOPE-004）——敏感权限清单：命中的按钮默认不可分配，平台按租户单独放开
--   （放开时原因必填）；清单可增减（rct 揭盲 / 锁库 2 项按规格「首期不登记」，rct 模块开发时再登记）。
--   plt_tenant_scope（PLT-SCOPE-002）——租户级覆盖：只存与「模板默认 ∩ 非敏感」不同的项；
--   生效可分配集 = base 固定能力 ∪（模板默认按覆盖调整）∩ 授权范围（已开通模块）。
--   plt_scope_log（PLT-SCOPE-008）——每次变更的操作人、前后差异与原因。

CREATE TABLE plt_sensitive_perm (
  id BIGINT NOT NULL AUTO_INCREMENT,
  perm_code VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_sensitive_perm (perm_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_scope_template (
  id BIGINT NOT NULL AUTO_INCREMENT,
  module_code VARCHAR(32) NOT NULL,
  menu_id BIGINT NOT NULL,
  updated_by BIGINT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_scope_template (module_code, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_tenant_scope (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  menu_id BIGINT NOT NULL,
  assignable TINYINT NOT NULL,
  reason VARCHAR(200) NULL,
  set_by BIGINT NULL,
  set_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_tenant_scope (tenant_id, menu_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_scope_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  reason VARCHAR(200) NOT NULL,
  detail VARCHAR(512) NOT NULL,
  operator_id BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_plt_scope_log (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 敏感权限清单（平台规格 §5「默认不可分配的敏感权限」已确认 9 项中的 7 项；
-- rct:blind:unblind、rct:db:lock 随 rct 模块开发时再登记）。
INSERT INTO plt_sensitive_perm (perm_code, name) VALUES
  ('followup:patient:plain', '敏感字段明文查看（证件号、手机号、住址等原值）'),
  ('followup:patient:export', '患者与业务数据导出（含批量导出）'),
  ('followup:record:delete', '删除业务数据（患者、随访记录等）'),
  ('followup:scan:download', '原件与附件下载（报告单、电子化资料、通话录音）'),
  ('tenant:audit:export', '审计日志导出'),
  ('tenant:user:export', '用户导出（含手机号等个人信息）'),
  ('followup:notice:batch', '批量群发通知（短信成本与骚扰风险）');

-- 授权范围模板默认项：首批业务模块（followup）医院端启用菜单与按钮中非敏感的全集
-- （敏感项按规格不能预置进模板，只能按租户单独放开）。base 模块是固定能力，不进模板。
INSERT INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.side = 'HOSPITAL' AND m.status = 'ENABLED' AND m.module_code = 'followup'
  AND (m.perm_code IS NULL OR m.perm_code NOT IN (SELECT perm_code FROM plt_sensitive_perm));

-- 权限点（平台规格 §5 权限点表）：查看 / 配置租户授权范围两个按钮 + 授权范围模板页。
-- 权限码创建后不改名（OQ-11）；与 V16 的 platform:tenant:module:open 同款四段式，经迁移登记。
INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (124, 110, 'PLATFORM', 'MENU', 'base', '授权范围模板', 'PlatformScopeTemplate', '/platform/scope-template', 'platform/scope-template/index', 'platform:scope:template:edit', 'SafetyCertificateOutlined', 50, 0, 'ENABLED'),
  (133, 111, 'PLATFORM', 'BUTTON', 'base', '查看授权范围', NULL, NULL, NULL, 'platform:tenant:scope:view', NULL, 30, 1, 'ENABLED'),
  (134, 111, 'PLATFORM', 'BUTTON', 'base', '配置授权范围', NULL, NULL, NULL, 'platform:tenant:scope:edit', NULL, 40, 1, 'ENABLED');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (124, 133, 134);
