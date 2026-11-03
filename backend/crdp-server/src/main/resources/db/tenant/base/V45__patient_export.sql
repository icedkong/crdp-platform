-- E13-T09 / A-PAT-009、C-PAT-014：患者导出复用 E08-T04 的导出作业表（base V19 job_export，
-- biz_type = PATIENT）。scope_json 冻结提交时申请人的数据范围快照与租户名——异步生成线程以申请人
-- 身份重建会话执行 @DataScope 查询（「导出内容按导出人的数据范围」，import-export.md §3；
-- 审批人通常是全院范围，不放大导出内容）。缺失该列数据的旧作业（仅审计导出，biz_type = AUDIT）
-- 不受影响；PATIENT 作业 scope_json 为空时生成失败（默认拒绝，可重新申请）。

ALTER TABLE job_export
  ADD COLUMN scope_json VARCHAR(1024) NULL COMMENT '生成上下文（E13-T09 患者导出：租户名 + 申请人数据范围快照，提交时冻结；审计导出恒为 NULL）' AFTER condition_json;
