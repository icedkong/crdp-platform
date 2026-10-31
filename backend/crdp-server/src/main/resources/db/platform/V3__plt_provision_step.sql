-- 开通编排的逐步结果（PLT-TEN-008 / E02-T04：中途失败重跑可续）。
-- 每步成功或失败都 upsert 到本表；重跑从失败步骤继续，已成功步骤幂等跳过。

CREATE TABLE plt_provision_step (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  step VARCHAR(32) NOT NULL,
  status VARCHAR(16) NOT NULL,
  error_text VARCHAR(1000) NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_provision_step (tenant_id, step)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
