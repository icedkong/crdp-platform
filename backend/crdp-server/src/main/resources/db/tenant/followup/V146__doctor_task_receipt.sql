-- OQR-06：已生效的医生指定排期保留来源，后续实际日期重算不能把它恢复成默认日期。
ALTER TABLE fu_task
  ADD COLUMN doctor_override_record_id BIGINT NULL COMMENT '本次数医生指定排期的来源记录；同事务消费留痕';
