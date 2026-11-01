-- E06-T10 / PLT-CFG-002、004：租户字典与租户参数覆盖（租户库）。
-- 字典采用「平台定义、租户覆盖」的增量模型（与 plt_tenant_scope 同思路）：
--   cfg_dict        租户自有字典类型（source=TENANT）；平台字典不落行，读取时与平台定义合并。
--   cfg_dict_item   租户侧覆盖标记与自有项：
--                     source=PLATFORM：平台项在本院的停用标记（status=DISABLED 表示停用，
--                       不存 label——平台项显示文字租户不可改，只能停用 / 恢复）；
--                     source=TENANT：租户新增的自有项（label / sort_no 本院维护）。
--   生效项 =（平台字典项 − 本院停用的平台项）∪ 本院自有项（PLT-CFG-002「继承平台字典，
--   可新增租户自有项或停用平台项」）；平台新增项自动出现，无需同步。
-- 参数：cfg_param（V1 已有）存租户覆盖值（param_key + param_value），本迁移补维护人与
-- 变更时间；cfg_param_log 记录每次覆盖 / 恢复（PLT-CFG-011「记录变更」，验收：租户覆盖
-- 平台默认值并留记录）。生效值 = 覆盖值（平台参数允许覆盖且值在范围内）否则平台值。

CREATE TABLE cfg_dict (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL COMMENT '字典编码，本院唯一；租户自有字典，创建后不可改',
  name VARCHAR(128) NOT NULL COMMENT '名称',
  description VARCHAR(255) NULL,
  status VARCHAR(16) NOT NULL COMMENT 'ENABLED/DISABLED',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cfg_dict_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE cfg_dict_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  dict_code VARCHAR(64) NOT NULL COMMENT '所属字典编码（平台字典或租户自有字典）',
  item_code VARCHAR(64) NOT NULL COMMENT '项编码，字典内唯一',
  label VARCHAR(128) NULL COMMENT '显示文字（仅 TENANT 项；平台项停用标记不存）',
  sort_no INT NOT NULL DEFAULT 0,
  source VARCHAR(16) NOT NULL COMMENT 'PLATFORM=平台项停用标记 / TENANT=租户自有项',
  status VARCHAR(16) NOT NULL COMMENT 'ENABLED/DISABLED（PLATFORM 行的 DISABLED 即本院停用该平台项）',
  updated_by BIGINT NULL COMMENT '最近维护人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cfg_dict_item (dict_code, item_code),
  KEY idx_cfg_dict_item_dict (dict_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE cfg_param
  ADD COLUMN updated_by BIGINT NULL COMMENT '最近覆盖人 sys_user.id' AFTER remark,
  ADD COLUMN updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
    ON UPDATE CURRENT_TIMESTAMP(3) AFTER updated_by;

CREATE TABLE cfg_param_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  param_key VARCHAR(64) NOT NULL,
  action VARCHAR(16) NOT NULL COMMENT 'OVERRIDE=覆盖 / RESET=恢复平台默认',
  old_value VARCHAR(512) NULL COMMENT '变更前的租户覆盖值（无覆盖为 NULL）',
  new_value VARCHAR(512) NULL COMMENT '变更后的租户覆盖值（RESET 为 NULL）',
  reason VARCHAR(200) NULL,
  operator_id BIGINT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_cfg_param_log_key (param_key, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 「维护租户参数 / 维护租户字典」按钮（plt_menu 341 / 342，平台库 V24）授予内置租户管理员角色。
-- 三处互斥不重复（与 base V9 / V10 同款）：老库走本脚本；全新 local/dev 租户库本脚本先于
-- 种子 V100（角色未建）执行、插 0 行，由种子 V108 补授；生产（无种子）由开通编排 ensureAdmin 授予。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 341 AS menu_id
  UNION ALL SELECT 'TENANT_ADMIN', 342
) m ON m.code = r.code
WHERE r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
