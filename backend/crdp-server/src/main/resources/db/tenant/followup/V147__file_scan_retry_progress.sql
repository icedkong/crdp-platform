-- OQR-08 / OQ-154：记录补扫尝试与固定原因码，不重写历史扫描结论，不删除异常原件。
ALTER TABLE sys_file
  ADD COLUMN virus_scan_attempts INT NOT NULL DEFAULT 0 COMMENT '补扫尝试次数',
  ADD COLUMN virus_scan_attempted_at DATETIME(3) NULL COMMENT '最近补扫尝试时间',
  ADD COLUMN virus_scan_retry_reason VARCHAR(32) NULL COMMENT '待重试固定原因码，不记录原始异常或文件正文',
  ADD KEY idx_sys_file_scan_retry (virus_scan_status, virus_scan_attempted_at, id);
