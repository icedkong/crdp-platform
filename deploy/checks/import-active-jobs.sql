-- OQR-03 / V140 上线前只读排查；先连接待升级租户数据库，逐租户执行。
-- 不返回文件名、对象键或导入内容，不修改状态。
SELECT DATABASE() AS tenant_database, COUNT(*) AS active_jobs,
       CASE WHEN COUNT(*) > 1 THEN 'BLOCKED' ELSE 'READY' END AS migration_check
FROM imp_job WHERE status IN ('UPLOADED', 'PRECHECKING', 'IMPORTING');

-- 处置清单：必须逐条核实执行线程、批次与实际业务写入；不能以 updated_at 早就认定失活。
SELECT id AS job_id, status, operator_id, created_at, updated_at
FROM imp_job WHERE status IN ('UPLOADED', 'PRECHECKING', 'IMPORTING') ORDER BY id;
