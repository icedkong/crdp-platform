-- 每租户调度执行记录与补跑（技术方案第 5、15 章）。
-- 补跑起点 = MAX(run_date) WHERE status = 'SUCCESS'；唯一键保证按日幂等，重复执行覆盖当天。

CREATE TABLE job_tenant_run (
  id BIGINT NOT NULL AUTO_INCREMENT,
  job_code VARCHAR(64) NOT NULL,
  run_date DATE NOT NULL,
  status VARCHAR(16) NOT NULL,
  started_at DATETIME(3) NOT NULL,
  finished_at DATETIME(3) NULL,
  error_text VARCHAR(1000) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_job_code_run_date (job_code, run_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
