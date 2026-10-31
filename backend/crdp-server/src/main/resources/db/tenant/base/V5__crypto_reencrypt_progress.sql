-- 重加密作业的断点游标（E03-T05，ADR 0003 的作业执行记录在 job_tenant_run，本表记列级进度）。
-- 每个（表，加密列）一行：目标版本、是否待全量刷新盲索引、已扫到的最大主键、趟次状态。
-- 行更新幂等：已在目标版本的行重扫会跳过，中断后从 last_id 续跑。

CREATE TABLE crypto_reencrypt_progress (
  id BIGINT NOT NULL AUTO_INCREMENT,
  table_name VARCHAR(64) NOT NULL,
  column_name VARCHAR(64) NOT NULL,
  target_key_version INT NOT NULL,
  target_index_version INT NULL,
  index_refresh_pending TINYINT NOT NULL DEFAULT 0,
  last_id BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_crypto_reencrypt_progress (table_name, column_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
