-- E20-T02 / PLT-VIEW-007、008、009：视角规则（脱敏、设盲、只读、导出）的存储与配置入口。
--   plt_view_rule——规则行（platform-spec §8「关键字段 · 视角规则」）：层级 GLOBAL/TENANT/MODULE/FIELD，
--   租户 / 模块 / 资源对象与字段为可选引用（空 = 全部）；可见性 PLAIN/MASKED/BLINDED/HIDDEN，
--   MASKED 必带脱敏规则（沿用第 4 章 SensitiveValueMasker 的字段码）；操作 READONLY/EDITABLE；
--   允许导出默认否。状态 ACTIVE/DISABLED（PENDING_REVIEW 与复核人列随 E20-T05 高敏复核补列，
--   本任务普通变更即时生效）；version 每次变更 +1，完整版本链与回滚随 E20-T05（PLT-VIEW-015）。
--   规则编号服务端生成（VR-00001），种子规则带语义编号（VR-GLOBAL-DEFAULT 等）便于幂等补种。
--   plt_view_rule_role——适用平台角色清单（空 = 全部角色）；按 role_id 绑定，解析时经 plt_role 换码。
-- 匹配口径（PLT-VIEW-009，已确认）：字段 > 模块 > 租户 > 全局默认，越具体越优先；同一目标命中
--   同层多条规则（如账号有多个平台角色）按更严的执行：隐藏 > 设盲 > 脱敏 > 明文、只读 > 可编辑、
--   禁止导出 > 允许导出。默认规则的预置布局见种子 V109（避免同层互相抵消，见 OQ-99）。
-- 权限点（platform-spec §8 权限点表，码创建后不改名，OQ-11）：
--   platform:view:rule:view（菜单 147 挂载）、platform:view:rule:edit（按钮 148，新建 / 修改 / 停用）；
--   platform:view:rule:review（复核高敏变更）随 E20-T05 登记。135–140、147 起为空闲号段。
-- 授予 PLATFORM_ADMIN 与 V50 同款：主迁移 INSERT..SELECT（全新库此时还没有 PLATFORM_ADMIN 行，
--   命中 0 行无害；种子 V109 对 PLATFORM_ADMIN 补授）。

CREATE TABLE plt_view_rule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL COMMENT '规则编号（服务端生成 VR-00001；种子规则带语义编号）',
  name VARCHAR(128) NOT NULL COMMENT '规则名称',
  layer VARCHAR(16) NOT NULL COMMENT '层级：GLOBAL 全局默认 / TENANT 租户 / MODULE 模块 / FIELD 字段',
  tenant_id BIGINT NULL COMMENT '适用租户 plt_tenant.id（空 = 全部租户；TENANT 层必填）',
  module_code VARCHAR(64) NULL COMMENT '适用模块 plt_module.code（空 = 全部模块；MODULE 层必填）',
  resource VARCHAR(64) NULL COMMENT '资源对象（FIELD 层必填，取自视角字段目录 ViewRuleCatalog）',
  field VARCHAR(64) NULL COMMENT '字段码（FIELD 层资源对象内；空 = 整个资源对象）',
  visibility VARCHAR(16) NOT NULL COMMENT '可见性：PLAIN 明文 / MASKED 脱敏 / BLINDED 设盲 / HIDDEN 隐藏',
  mask_rule VARCHAR(32) NULL COMMENT '脱敏规则（MASKED 必填）：name/phone/idCard/medicalRecordNo/address/generic',
  operation VARCHAR(16) NOT NULL COMMENT '操作：READONLY 只读 / EDITABLE 可编辑',
  allow_export TINYINT NOT NULL DEFAULT 0 COMMENT '允许导出（默认否；导出 / 打印 / 下载同口径）',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 生效 / DISABLED 停用（PENDING_REVIEW 随 E20-T05）',
  version INT NOT NULL DEFAULT 1 COMMENT '版本号，每次变更 +1（完整版本链与回滚随 E20-T05）',
  created_by BIGINT NULL COMMENT '创建人（平台账号）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT NULL COMMENT '最后变更人（平台账号）',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_view_rule_code (code),
  KEY idx_plt_view_rule_layer (layer, status),
  KEY idx_plt_view_rule_tenant (tenant_id),
  KEY idx_plt_view_rule_module (module_code),
  KEY idx_plt_view_rule_resource (resource, field)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='平台租户视角规则（PLT-VIEW-007..009；脱敏 / 设盲 / 只读 / 导出按 字段>模块>租户>全局默认 匹配）';

CREATE TABLE plt_view_rule_role (
  rule_id BIGINT NOT NULL COMMENT '规则 plt_view_rule.id',
  role_id BIGINT NOT NULL COMMENT '适用平台角色 plt_role.id（清单空 = 全部角色）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (rule_id, role_id),
  KEY idx_plt_view_rule_role_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='视角规则的适用平台角色清单（PLT-VIEW-007；空清单 = 全部角色）';

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (147, 110, 'PLATFORM', 'MENU', 'base', '视角规则', 'PlatformViewRule', '/platform/view-rule', 'platform/view-rule/index', 'platform:view:rule:view', 'SafetyCertificateOutlined', 70, 0, 'ENABLED'),
  (148, 147, 'PLATFORM', 'BUTTON', 'base', '维护视角规则', NULL, NULL, NULL, 'platform:view:rule:edit', NULL, 10, 1, 'ENABLED');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (147, 148);
