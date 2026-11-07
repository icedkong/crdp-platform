-- E16-T08 / A-ATT-017：通话记录与沟通记录录音为同一数据。
--
-- rpt_report.contact_log_id（可空）= 关联沟通记录 fu_contact_log.id，仅「通话记录」类型
-- （type_code = CALL，V62 预置）使用：
--   * 沟通记录创建时带录音（E15-T04 audioFileIds 非空）→ 同事务自动生成一份 CALL 报告单，
--     contact_log_id 回指该沟通记录；录音 sys_file 同时挂 fu_contact_audio 与 rpt_report_file
--     （同一份文件、不重复存储，两处看到同一份数据）；
--   * 报告单页签手动添加「通话记录」时可带 contactLogId 关联某条沟通记录（A-ATT-017），
--     该沟通记录的录音即报告单附件（无新上传时不重复占用 sys_file 的挂载状态）；
--   * 删除联动（「任一处删除即两处同时删除」，pages.md §4.8）：删沟通记录 → 连带软删其
--     CALL 报告单与录音台账（sys_file DELETED，两处预览 / 下载随即 40400）；删 CALL 报告单
--     （或其单个录音文件，E16-T09）→ sys_file 软删，沟通记录页签的录音同时消失。
-- 关联单独存放在本列、不算位置（tech-plan §10：位置只指 rpt_report_link 的基线 / 随访锚点）；
-- 录音生成的通话记录位置预选为该沟通关联任务对应的随访记录整条（fu_task.record_id，
-- 无对应记录时不落位置行、进入「未指定位置」组，A-ATT-020 / E16-T05 已有口径）。

ALTER TABLE rpt_report
  ADD COLUMN contact_log_id BIGINT NULL COMMENT '关联沟通记录 fu_contact_log.id（仅 CALL 通话记录，A-ATT-017；单独存放不算位置；同一沟通记录的录音与该报告单附件为同一批 sys_file） AFTER name_snapshot',
  ADD KEY idx_rpt_report_contact (contact_log_id);
