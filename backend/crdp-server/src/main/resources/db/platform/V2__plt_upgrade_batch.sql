-- 租户库升级批次（PLT-MOD-007 / E02-T03：按批次升级租户库，失败停批，可重试）。
-- 批次头记录整体状态；明细行按租户记录进度与失败原因。平台端展示页在 E05。

CREATE TABLE plt_upgrade_batch (
  id BIGINT NOT NULL AUTO_INCREMENT,
  description VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_upgrade_batch_item (
  id BIGINT NOT NULL AUTO_INCREMENT,
  batch_id BIGINT NOT NULL,
  tenant_id BIGINT NOT NULL,
  status VARCHAR(16) NOT NULL,
  error_text VARCHAR(1000) NULL,
  started_at DATETIME(3) NULL,
  finished_at DATETIME(3) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_upgrade_batch_item (batch_id, tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
