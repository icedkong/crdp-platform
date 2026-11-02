-- E09-T02 / A-NTF-002、C-NTF-002：站内信收件箱（docs/api/notify.md §1）。
-- 投递入口是 NoticeInboxService.deliver（通知规则引擎 E09-T04 经 outbox 分发器调用，
-- 至少一次语义 → dedup_key 唯一键幂等）。患者姓名只落信封密文，读取时解密并按
-- 接收人当前字段策略脱敏（@MaskedByPolicy，不在落库时写死脱敏值）。租户库表不带 tenant_id。

CREATE TABLE ntf_message (
  id BIGINT NOT NULL AUTO_INCREMENT,
  recipient_id BIGINT NOT NULL COMMENT '接收人（sys_user.id），站内信只发医护',
  event_type VARCHAR(64) NOT NULL COMMENT '事件类型（TASK_OPENED、TASK_OVERDUE 等，notify.md §2）',
  title VARCHAR(200) NOT NULL COMMENT '标题（不含患者姓名明文）',
  summary VARCHAR(500) NULL COMMENT '摘要（不含患者姓名明文）',
  patient_id BIGINT NULL COMMENT '关联患者（可空，列表可按患者跳转）',
  patient_name_cipher VARCHAR(512) NULL COMMENT '患者姓名密文（CryptoService 信封加密，purpose=ntf-patient-name）',
  target_type VARCHAR(16) NULL COMMENT '点击跳转落点：TASK | RECORD | PATIENT',
  target_id BIGINT NULL COMMENT '落点对象 ID',
  dedup_key VARCHAR(160) NOT NULL COMMENT '幂等键（outbox 事件 ID + 接收人），重复投递不重发',
  read_flag TINYINT NOT NULL DEFAULT 0 COMMENT '0 未读 / 1 已读',
  read_at DATETIME(3) NULL COMMENT '标记已读时间',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ntf_message_dedup (dedup_key),
  KEY idx_ntf_message_recipient (recipient_id, read_flag, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='站内信收件箱（A-NTF-002 / C-NTF-002）';
