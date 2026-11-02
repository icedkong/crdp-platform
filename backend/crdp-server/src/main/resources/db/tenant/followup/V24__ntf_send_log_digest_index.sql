-- E09-T05 / A-NTF-005、C-NTF-003、C-NTF-005：每日汇总作业的台账查询索引（只加索引，不改表结构）。
--   汇总作业消费 QUEUED 短信行（listQueuedSmsRecipients / listQueuedSms / countSentSmsBetween），
--   现有 idx_ntf_send_log_query 以 event_type 打头，服务不了「channel + send_status」为主的作业路径；
--   idx_ntf_send_log_digest 以 (channel, send_status, recipient_id, sent_at) 覆盖：
--     · 待汇总接收人 / 某人待汇总行：channel + send_status 前缀；
--     · 每人每日已发条数（频控上限）：channel + send_status + recipient_id + sent_at 全命中。

CREATE INDEX idx_ntf_send_log_digest
  ON ntf_send_log (channel, send_status, recipient_id, sent_at);
