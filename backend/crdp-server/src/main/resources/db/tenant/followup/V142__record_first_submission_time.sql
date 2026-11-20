-- OQR-05A：首次提交事实与实际随访日期 / 最后更新时间分离。
-- 历史 SUBMITTED 行无法从 updated_at 可靠还原首次提交时间，保持 NULL（未知），不伪造回填。
ALTER TABLE fu_record
  ADD COLUMN first_submitted_at DATETIME(3) NULL COMMENT '首次提交时间（北京时间）；历史无法还原时为NULL；修改/映射/重算不改写',
  ADD INDEX idx_fu_record_first_submitted (status, deleted, first_submitted_at);
