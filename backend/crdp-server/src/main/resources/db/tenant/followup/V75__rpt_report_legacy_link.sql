-- E18-T08 / A-ATT-002（2026-10-31 变更：报告单位置由「可选关联基线或第 N 年随访」改为必填、可多选）：
-- 历史报告单位置迁移的输入落库形态与重入判据（tech-plan §10「历史数据迁移」、A 规格 §14 变更记录）。
--
-- 旧系统 / 前期试运行按「可选单一关联」保存的报告单（关联基线，或关联第 N 年随访）迁入本平台时，
-- 原关联落在本列（迁移输入，bulk 装载时写入；正常上传接口不写本列——新上传一律走 rpt_report_link）：
--   * legacy_link_type：YEAR 按年关联（第 N 年随访）/ BASELINE 关联基线；NULL = 非历史迁移报告单。
--   * legacy_link_year：按年关联的随访年度 N（YEAR 时非空）。
-- 迁移口径（tasks.csv E18-T08 验收）：按年关联 → 该年随访记录整条（fu_record.followup_year = N，
--   E18-T05 历史导入生成的年度记录，锚点整条、模板版本取该记录固化版本）；原关联基线 → 不落位置行、
--   进入「未指定位置」组（A-ATT-020，不集中人工补指定）。迁移由 ReportLegacyLocationMigrationService
--   执行（POST /api/hospital/followup/reports/legacy-location-migration，可重入）：
--   * legacy_migrated_at 为重入判据——NULL 待迁移，非 NULL 已迁移不再处理（重复执行不重复落位置行）；
--   * 按年关联但该年无随访记录的保持待迁移（下次执行可补），迁移报告单列数量；
--   * legacy_* 两列迁移后保留作原关联溯源，不回填清除。

ALTER TABLE rpt_report
  ADD COLUMN legacy_link_type VARCHAR(16) NULL COMMENT '旧系统关联形态（E18-T08 迁移输入）：YEAR 按年关联 / BASELINE 关联基线；正常上传不写本列' AFTER contact_log_id,
  ADD COLUMN legacy_link_year INT NULL COMMENT '按年关联的随访年度 N（legacy_link_type=YEAR 时非空；迁移时定位该年随访记录）' AFTER legacy_link_type,
  ADD COLUMN legacy_migrated_at DATETIME(3) NULL COMMENT '位置迁移完成时间（E18-T08 重入判据：NULL 待迁移；legacy_* 保留作原关联溯源）' AFTER legacy_link_year,
  ADD KEY idx_rpt_report_legacy (legacy_link_type, legacy_migrated_at);
