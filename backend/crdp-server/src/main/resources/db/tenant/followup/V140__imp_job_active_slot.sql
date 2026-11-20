-- OQR-03 / E18-T02 / E18-T05：同院活动导入作业由数据库唯一约束互斥。
-- 上线前停止新的导入请求并核实在途执行，按 docs/ops/import-active-jobs.md 输出冲突清单。
-- 存量多活动作业时先阻断迁移，不挑选赢家、不修改作业、不删除历史。
-- 临时校验表只存在于本连接；重试前先清除，失败不留下持久业务表。
DROP TEMPORARY TABLE IF EXISTS crdp_imp_active_migration_guard;
CREATE TEMPORARY TABLE crdp_imp_active_migration_guard (
  active_jobs BIGINT NOT NULL,
  CONSTRAINT ck_imp_job_resolve_active_conflicts CHECK (active_jobs <= 1)
);
INSERT INTO crdp_imp_active_migration_guard (active_jobs)
SELECT COUNT(*) FROM imp_job WHERE status IN ('UPLOADED', 'PRECHECKING', 'IMPORTING');
DROP TEMPORARY TABLE crdp_imp_active_migration_guard;

-- 唯一索引是最终并发闸门：状态切换与占用 / 释放在同一行原子完成，跨应用实例仍有效。
-- PRECHECKED / PRECHECK_FAILED / PARTIAL_FAILED 与终态均为 NULL，不占活动槽；确认和重试重新争用。
ALTER TABLE imp_job
  ADD COLUMN active_slot TINYINT GENERATED ALWAYS AS (
    CASE WHEN status IN ('UPLOADED', 'PRECHECKING', 'IMPORTING') THEN 1 ELSE NULL END
  ) STORED COMMENT '同院活动导入槽，活动状态为1，其余NULL；随状态原子占用释放',
  ADD UNIQUE KEY uk_imp_job_active_slot (active_slot);
