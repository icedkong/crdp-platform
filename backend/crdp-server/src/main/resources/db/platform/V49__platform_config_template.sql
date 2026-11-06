-- E12-T01 / A-FLD-014：平台库配置模板表——从源租户导出的、带版本的配置包。
-- 包体 package_body 存 gzip JSON（与 job_export.file_body 同构先例；对象存储副本未做，见 open-questions OQ-90）。
-- 只存注册表（ConfigTableRegistry）标注的配置表；患者与业务数据表不入包（导出侧白名单 + 注册表自检双保险）。
-- 导入与应用到新租户（ID 重映射）随 E12-T02，本表先承载导出产物与追溯信息（哈希、操作人）。

CREATE TABLE plt_config_template (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL COMMENT '源租户 plt_tenant.id',
  tenant_code VARCHAR(32) NOT NULL COMMENT '源租户编码（导出时快照）',
  tenant_name VARCHAR(128) NOT NULL COMMENT '源租户名称（导出时快照）',
  name VARCHAR(128) NOT NULL COMMENT '模板名称（缺省「{租户名} 配置模板」）',
  version INT NOT NULL COMMENT '同租户递增版本（v1 起）',
  format_version INT NOT NULL COMMENT '包结构版本（ConfigPackage.formatVersion）',
  table_count INT NOT NULL COMMENT '包内配置表节数（含 absent 节）',
  row_count BIGINT NOT NULL COMMENT '入包配置行合计',
  package_hash VARCHAR(128) NOT NULL COMMENT '包 JSON 的 SHA-256（经 DigestService）',
  package_body LONGBLOB NOT NULL COMMENT 'gzip JSON 配置包（只含注册表标注的配置表）',
  created_by BIGINT NULL COMMENT '导出平台账号 plt_account.id',
  created_by_name VARCHAR(64) NULL COMMENT '导出平台账号名',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_config_template_version (tenant_id, version),
  KEY idx_plt_config_template_tenant (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='配置模板（A-FLD-014：导出产物；导入与应用随 E12-T02）';
