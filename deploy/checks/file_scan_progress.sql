-- OQR-08：只能由获授权运维在单个租户库执行并记录工单；只读，无患者明文、文件名或对象键。
-- 本结果不是扫描报告，也不触发扫描 / 删除 / 恢复访问。
SELECT virus_scan_status, COUNT(*) AS file_count,
       SUM(virus_scan_retry_reason IS NOT NULL) AS retry_count,
       MAX(virus_scanned_at) AS last_completed_at,
       MAX(virus_scan_attempted_at) AS last_attempted_at
FROM sys_file WHERE status <> 'DELETED'
GROUP BY virus_scan_status ORDER BY virus_scan_status;

SELECT id AS file_id, virus_scan_status, virus_scan_attempts,
       virus_scan_attempted_at, virus_scanned_at, virus_scan_retry_reason
FROM sys_file
WHERE status <> 'DELETED'
  AND (virus_scan_status IN ('INFECTED','HASH_MISMATCH') OR virus_scan_retry_reason IS NOT NULL)
ORDER BY id LIMIT 200;
