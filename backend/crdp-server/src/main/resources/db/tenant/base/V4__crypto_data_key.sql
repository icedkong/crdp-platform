-- 每租户数据密钥（信封加密，技术方案第 4 章）。
-- 只存被主密钥包装后的密钥；明文仅内存缓存。key_version 为 E03-T05 轮换预留（旧版本保留解密）。

CREATE TABLE crypto_data_key (
  id BIGINT NOT NULL AUTO_INCREMENT,
  purpose VARCHAR(32) NOT NULL,
  key_version INT NOT NULL,
  wrapped_key VARCHAR(1024) NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_crypto_data_key (purpose, key_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
