-- E06-T10 / PLT-CFG-001、003：平台字典与系统参数（平台定义、租户在范围内覆盖）。
--   plt_dict / plt_dict_item  全局字典类型与字典项（性别、证件类型、科室类型等）；
--     编码创建后不可改（应用层校验）；字典项停用不删除（system.md §4 口径）。
--   plt_param                 系统参数：值类型 文本/数值/布尔/枚举/JSON，层级 平台锁定/租户可覆盖，
--     取值范围（JSON，可覆盖时必填：NUMBER {min,max} / TEXT {maxLength} / ENUM {options[]}；
--     BOOLEAN 与 JSON 的范围由类型本身决定）租户覆盖值不得超出。
-- 租户侧的覆盖与停用标记存租户库 cfg_dict / cfg_dict_item / cfg_param（租户库 base V13），
-- 生效值读取时合并（平台定义缓存 60 秒，平台保存后立即失效，PLT-CFG-011）。

CREATE TABLE plt_dict (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL COMMENT '字典编码，全局唯一，创建后不可改',
  name VARCHAR(128) NOT NULL COMMENT '名称',
  description VARCHAR(255) NULL COMMENT '说明',
  status VARCHAR(16) NOT NULL COMMENT 'ENABLED/DISABLED',
  updated_by BIGINT NULL COMMENT '最近维护人 plt_account.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_dict_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_dict_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  dict_id BIGINT NOT NULL,
  item_code VARCHAR(64) NOT NULL COMMENT '项编码，字典内唯一，创建后不可改',
  label VARCHAR(128) NOT NULL COMMENT '显示文字',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序号，小者在前',
  status VARCHAR(16) NOT NULL COMMENT 'ENABLED/DISABLED，停用不删除',
  updated_by BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_dict_item (dict_id, item_code),
  KEY idx_plt_dict_item_dict (dict_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_param (
  id BIGINT NOT NULL AUTO_INCREMENT,
  param_key VARCHAR(64) NOT NULL COMMENT '参数键，全局唯一，创建后不可改',
  name VARCHAR(128) NOT NULL COMMENT '名称',
  description VARCHAR(255) NULL COMMENT '说明',
  value_type VARCHAR(16) NOT NULL COMMENT 'TEXT/NUMBER/BOOLEAN/ENUM/JSON',
  platform_value VARCHAR(512) NOT NULL COMMENT '平台默认值',
  tenant_overridable TINYINT NOT NULL DEFAULT 0 COMMENT '层级：0 平台锁定 / 1 租户可覆盖',
  value_range VARCHAR(512) NULL COMMENT '取值范围 JSON，可覆盖时必填（BOOLEAN/JSON 除外）',
  updated_by BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_param_key (param_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 种子字典（配置数据走迁移，AGENTS.md 规则 6）。全部通用配置，不含真实数据。
INSERT INTO plt_dict (code, name, description, status) VALUES
  ('gender', '性别', '患者与用户通用的性别字典', 'ENABLED'),
  ('id_type', '证件类型', '患者建档与账号绑定的证件类型', 'ENABLED'),
  ('dept_type', '科室类型', '临床 / 医技 / 职能 / 随访中心等（PLT-ORG-002 科室字段引用）', 'ENABLED');

INSERT INTO plt_dict_item (dict_id, item_code, label, sort_no, status)
SELECT d.id, i.item_code, i.label, i.sort_no, 'ENABLED'
FROM plt_dict d
JOIN (
  SELECT 'gender' AS dict_code, '1' AS item_code, '男' AS label, 10 AS sort_no
  UNION ALL SELECT 'gender', '2', '女', 20
  UNION ALL SELECT 'gender', '0', '未知（示例）', 90
  UNION ALL SELECT 'id_type', 'ID_CARD', '居民身份证', 10
  UNION ALL SELECT 'id_type', 'PASSPORT', '护照', 20
  UNION ALL SELECT 'id_type', 'OTHER', '其他证件', 90
  UNION ALL SELECT 'dept_type', 'CLINICAL', '临床科室', 10
  UNION ALL SELECT 'dept_type', 'MEDICAL_TECH', '医技科室', 20
  UNION ALL SELECT 'dept_type', 'FUNCTIONAL', '职能科室', 30
  UNION ALL SELECT 'dept_type', 'FOLLOWUP_CENTER', '随访中心', 40
) i ON i.dict_code = d.code;

-- 种子参数：覆盖三种典型层级与值类型（NUMBER 可覆盖带范围 / TEXT 可覆盖带长度 /
-- BOOLEAN 平台锁定）。均为通用配置项，不含真实机构信息。
INSERT INTO plt_param (param_key, name, description, value_type, platform_value, tenant_overridable, value_range) VALUES
  ('tenant.db.pool.max', '每租户连接池上限', '该院数据库连接池的最大连接数（NFR-CAP-002 租户间资源互不影响）',
   'NUMBER', '20', 1, '{"min":5,"max":50}'),
  ('report.footer.note', '报告单页脚备注', '本院报告单导出与打印时追加在页脚的备注文字（可留空）',
   'TEXT', '', 1, '{"maxLength":255}'),
  ('platform.brand.locked', '平台品牌锁定示例', '演示平台锁定参数：层级为「平台锁定」，租户不可覆盖',
   'BOOLEAN', 'false', 0, NULL);

-- 平台端菜单（pages.md §11 系统配置）：字典管理 /system 参数两个页面，挂在「平台管理」下。
-- 授权：与 V13/V17/V20 同款做法，显式授予 PLATFORM_ADMIN（种子 V100 的全量授予是执行时快照，
-- 不随新迁移自动跟随）。
INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (127, 110, 'PLATFORM', 'MENU', 'base', '字典管理', 'PlatformDict', '/platform/dict', 'platform/dict/index', 'platform:dict:edit', 'BookOutlined', 70, 0, 'ENABLED'),
  (128, 110, 'PLATFORM', 'MENU', 'base', '系统参数', 'PlatformParam', '/platform/param', 'platform/param/index', 'platform:param:edit', 'ControlOutlined', 80, 0, 'ENABLED');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (127, 128)
  AND NOT EXISTS (
    SELECT 1 FROM plt_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.id
  );

-- 医院端「机构设置」下的参数 / 字典页签按钮（pages.md §10；菜单 34 机构设置随 E06-T11 已有，
-- 权限码取 platform-spec §10 权限点表：tenant:dict:edit / tenant:param:edit，OQ-11 创建后不改名）。
-- 角色授予在租户库：老库由租户库 base V13 授予、全新 local/dev 库由种子 V108 兜底、
-- 生产由开通编排 ensureAdmin 授予（与 V19 / V21 同款三处互斥）。
INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (341, 34, 'HOSPITAL', 'BUTTON', 'base', '维护租户参数', NULL, NULL, NULL, 'tenant:param:edit', NULL, 10, 1, 'ENABLED'),
  (342, 34, 'HOSPITAL', 'BUTTON', 'base', '维护租户字典', NULL, NULL, NULL, 'tenant:dict:edit', NULL, 20, 1, 'ENABLED');
