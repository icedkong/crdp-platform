-- 租户列表与信息维护（E05-T01 / PLT-TEN-001、PLT-TEN-009）。
-- plt_tenant 补齐“关键字段 · 租户信息”的可编辑列；到期类型与到期日先落列支撑列表展示与筛选，
-- 到期设置、提醒、宽限与续期的维护是 E05-T02（PLT-TEN-011..013）。
-- 医院名称全平台唯一（规格第 2 章“关键字段 · 租户信息”），种子数据无重名。

ALTER TABLE plt_tenant
  ADD COLUMN short_name VARCHAR(64) NULL COMMENT '简称，用于菜单栏与通知署名',
  ADD COLUMN credit_code VARCHAR(32) NULL COMMENT '统一社会信用代码',
  ADD COLUMN org_type VARCHAR(64) NULL COMMENT '机构类型与等级（字典选项随 PLT-CFG-001 提供，先存文本）',
  ADD COLUMN region VARCHAR(128) NULL COMMENT '所在地区',
  ADD COLUMN contact_name VARCHAR(64) NULL COMMENT '联系人',
  ADD COLUMN contact_phone VARCHAR(512) NULL COMMENT '联系人电话，信封加密密文（CryptoService，purpose=plt-tenant-phone）',
  ADD COLUMN contact_email VARCHAR(128) NULL COMMENT '联系邮箱',
  ADD COLUMN contract_no VARCHAR(64) NULL COMMENT '合同编号',
  ADD COLUMN expire_type VARCHAR(16) NOT NULL DEFAULT 'LONG_TERM' COMMENT '到期类型 FIXED/LONG_TERM，默认长期（E05-T02 维护）',
  ADD COLUMN expire_at DATE NULL COMMENT '试用或服务到期日（expire_type=FIXED 时有值）',
  ADD COLUMN updated_at DATETIME(3) NULL COMMENT '信息最近修改时间（每次修改留审计记录）',
  ADD UNIQUE KEY uk_plt_tenant_name (name);

-- 平台库字段加密的数据密钥表（与租户库 db/tenant/base/V4 同构，见 OQ-34）。
-- 租户联系电话等平台侧敏感字段同样只走 CryptoService 信封加密；
-- 平台库没有这张表时 CryptoService 在平台上下文无法取钥。
CREATE TABLE crypto_data_key (
  id BIGINT NOT NULL AUTO_INCREMENT,
  purpose VARCHAR(32) NOT NULL,
  key_version INT NOT NULL,
  wrapped_key VARCHAR(1024) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_crypto_data_key (purpose, key_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
