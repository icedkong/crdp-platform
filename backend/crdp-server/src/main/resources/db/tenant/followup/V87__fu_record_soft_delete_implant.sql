-- E21-T11 / C-FU-024、C-FU-026、C-FU-008（OQ-130③、OQ-134②③）：fu_record 软删除列与器械维度列。
--
-- 软删除（C-FU-026 / PLT-AUD-001，与 pt_patient V37、rpt_report V65 同口径）：
-- DELETE /api/hospital/followup/records/{id}（followup:record:delete，平台库 V72 按钮 230）
-- 置 deleted = 1 并记 delete_reason（必填）/ deleted_by / deleted_at；数据长期保留（A-NFR-008 只软删），
-- 读取侧一律加 deleted = 0（越权不可见同 40400——不暴露存在）。关联任务只断开 record_id 链接
-- （任务终态不动：删除记录不重开任务，重录走补录 / 计划外入口）。
--
-- 器械维度（C-FU-008 附录 A 随访 #1「关联植入记录」系统字段；OQ-130③、OQ-134②）：
--   fu_record.implant_id = 按随访日期匹配当时在用的器械（区间 [implant_date, end_date)，E21-T03
--   DeviceContextService 同一判定；visit_date 为空的任务录入回落提交时的当前器械）。
--   创建草稿与提交时解析落列（FollowupRecordService）；对比标红「上一次」按同一器械过滤
--   （CompareSourceService，C-FU-010「电池类参数与同一器械比」）；implant 开关关闭的租户
--   （A / B）恒为 NULL，对比与既有行为不变（不参与过滤）。存量行回填：visit_date 非空的
--   补录 / 计划外 / 历史导入行按区间匹配回填；任务录入行 visit_date 为空、随访日期在表单
--   字段里，留 NULL（对比侧对 NULL 不过滤，动态判定保留为口径基准，OQ-134②）。
--
-- 另：修改已提交记录（C-FU-026 / OQ-14）双权限码的租户差异用功能开关 record_edit_submitted 区分
-- （AGENTS §5.7 医院差异走开关，不写 if tenant）：开关关闭（无行，A / B 默认）→ 修改已提交沿用
-- followup:record:edit；开关开启（医院 C 随租户配置写入 cfg_feature）→ 必须 followup:record:edit-submitted。
-- 本脚本不插 cfg_feature 行（无行 = 关闭 = A / B 行为），C 上线配置时开启（见 PR 与 open-questions）。

ALTER TABLE fu_record
  ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记（C-FU-026：0=在册；删除须填原因并记审计，数据长期保留）' AFTER status,
  ADD COLUMN delete_reason VARCHAR(255) NULL COMMENT '删除原因（C-FU-026 必填；审计 aud_log 同步留痕）' AFTER deleted,
  ADD COLUMN deleted_by BIGINT NULL COMMENT '删除人 sys_user.id' AFTER delete_reason,
  ADD COLUMN deleted_at DATETIME(3) NULL COMMENT '删除时间' AFTER deleted_by,
  ADD COLUMN implant_id BIGINT NULL COMMENT '关联植入记录 pt_implant.id（附录 A 随访 #1 系统字段：按随访日期匹配当时在用的器械，E21-T03 同一判定；implant 开关关闭的租户恒为 NULL）' AFTER visit_date;

-- 存量行器械维度回填：visit_date 非空的行按 [implant_date, end_date) 区间匹配（取出行 end_date = 取出日期，
-- 区间右开与 E21-T03 findImplantAt 同口径）；多器械时段各归各行。
UPDATE fu_record r
JOIN pt_implant i ON i.patient_id = r.patient_id
  AND r.visit_date >= i.implant_date
  AND (i.end_date IS NULL OR r.visit_date < i.end_date)
SET r.implant_id = i.id
WHERE r.visit_date IS NOT NULL;

-- 器械维度查询（对比候选按患者 + 器械过滤；软删行已被读取侧 deleted = 0 排除）。
CREATE INDEX idx_fu_record_implant ON fu_record (patient_id, implant_id, visit_date);
