-- OQR-06：医生指定日期绑定具体排期；旧指令不猜测目标，保留日期供医生重新确认。
ALTER TABLE fu_enrollment
  ADD COLUMN doctor_next_node_id BIGINT NULL COMMENT '指定日期的目标节点',
  ADD COLUMN doctor_next_occurrence INT NULL COMMENT '指定日期的目标次数',
  ADD COLUMN doctor_next_implant_id BIGINT NULL COMMENT '指定时的在用器械',
  ADD COLUMN doctor_next_record_id BIGINT NULL COMMENT '发出指定的随访记录',
  ADD COLUMN doctor_next_mode VARCHAR(16) NULL COMMENT '指定时的入组模式';
