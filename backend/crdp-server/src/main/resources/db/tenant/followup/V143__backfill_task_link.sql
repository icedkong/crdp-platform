-- OQR-01 / OQ-148：医院 C 配置启用，其他租户默认关闭，不按租户名称分支。
INSERT INTO cfg_feature(feature_code,enabled) VALUES ('backfill_task_link',0);
-- 同一补录提交请求的摘要与结果用于安全重试；不保存原始请求载荷。
ALTER TABLE fu_record
  ADD COLUMN backfill_submit_digest CHAR(64) NULL COMMENT '补录首次提交请求摘要，含选择和版本',
  ADD COLUMN backfill_submit_result JSON NULL COMMENT '补录提交结果；仅用于同请求幂等返回';
