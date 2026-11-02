-- E09-T04 / A-NTF-001、A-NTF-004、C-NTF-001、C-NTF-004：通知事件规则、接收人规则与发送台账。
--   ntf_rule —— 逐事件配置（开关、提前天数、发送时间、渠道、接收人规则、短信模板）。
--     默认值取 A 规格「关键字段 · 通知事件默认设置」：窗口预告 7 天 / 即将逾期 2 天提前、
--     08:30 发送、已逾期 站内信+短信 且可抄送病区负责人、其余站内信；每日汇总 短信+站内信。
--     A 与 C 的事件差异（C 无窗口预告）由租户在本院「通知设置」页开关，不写租户分支（AGENTS §5.7）。
--     advance_days / send_time 由事件产生方（E14 每日调度）与汇总作业（E09-T05）读取决定触发时机，
--     规则引擎本身在事件到达时即时投递（notify.md §2/§3）。
--   ntf_send_log —— 发送台账（A-NTF-006 的存储；查询/重试接口随 E09-T07）：
--     站内信投递即 SENT；短信先落 QUEUED（同日按人并入每日汇总、免打扰与频控在 E09-T05 汇总作业统一执行，
--     A 规格「同一天同一接收人的多条短信合并为每日汇总」），dedup_key 唯一键保证 outbox 至少一次语义下幂等。
--   ward.default_owner_id —— A-WARD-001 病区字典的「默认随访负责人」字段提前物化：
--     「逾期抄送病区负责人」暂按该字段解析（规格未单独定义病区负责人概念，OQ-60），
--     维护界面随 E13-T08 病区字典任务落地。
-- 另：医院端「通知设置」菜单（平台库 V32，plt_menu 35）授予租户库 TENANT_ADMIN（与 V106 同口径）。

CREATE TABLE ntf_rule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_type VARCHAR(64) NOT NULL COMMENT '事件类型（NotifyEventType：WINDOW_PREVIEW/TASK_OPENED/...，notify.md §2）',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '0 停发 / 1 启用',
  advance_days INT NULL COMMENT '提前天数（窗口预告默认 7、即将逾期默认 2；由事件产生方读取）',
  send_time TIME NULL COMMENT '发送时间（默认 08:30；即时事件为 NULL，由事件产生方 / 汇总作业读取）',
  channels VARCHAR(64) NOT NULL DEFAULT 'INBOX' COMMENT '渠道集合 CSV：INBOX / SMS',
  recipients VARCHAR(128) NOT NULL DEFAULT 'OWNER,WARD_STAFF' COMMENT '接收人规则 CSV：OWNER / WARD_STAFF / CC_WARD_LEADER',
  sms_template_code VARCHAR(64) NULL COMMENT '平台短信模板编码（须为 APPROVED 的 NOTIFY 场景模板）',
  updated_by BIGINT NULL COMMENT '最近修改人（sys_user.id）',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ntf_rule_event (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知事件规则（A-NTF-001 / C-NTF-001）';

CREATE TABLE ntf_send_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_type VARCHAR(64) NOT NULL COMMENT '事件类型（与 ntf_rule / ntf_message.event_type 同口径）',
  recipient_id BIGINT NOT NULL COMMENT '接收人（sys_user.id），通知只发医护不发患者（A-NTF-004 / C-NTF-004）',
  channel VARCHAR(16) NOT NULL COMMENT '渠道：INBOX / SMS',
  send_status VARCHAR(16) NOT NULL COMMENT 'QUEUED（待 E09-T05 汇总发送）/ SENT / FAILED / SKIPPED',
  title VARCHAR(200) NULL COMMENT '发送标题（与站内信标题一致，不含患者姓名明文）',
  detail VARCHAR(500) NULL COMMENT '结果说明（失败原因、跳过原因；不含患者敏感信息）',
  dedup_key VARCHAR(160) NOT NULL COMMENT '幂等键（outbox 事件 ID + 接收人 + 渠道），重复投递不重发不重记',
  sent_at DATETIME(3) NULL COMMENT '实际投递时间（QUEUED 为空）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_ntf_send_log_dedup (dedup_key),
  KEY idx_ntf_send_log_query (event_type, channel, send_status, created_at),
  KEY idx_ntf_send_log_recipient (recipient_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通知发送台账（A-NTF-006 存储；查询随 E09-T07）';

ALTER TABLE ward
  ADD COLUMN default_owner_id BIGINT NULL COMMENT '默认随访负责人（A-WARD-001）；逾期抄送「病区负责人」暂按此解析（OQ-60），维护界面随 E13-T08';

-- 事件默认规则种子（A 规格「通知事件默认设置」；逐院可在通知设置页调整）。
INSERT INTO ntf_rule (event_type, enabled, advance_days, send_time, channels, recipients, sms_template_code) VALUES
  ('WINDOW_PREVIEW', 1, 7, '08:30:00', 'INBOX', 'OWNER,WARD_STAFF', NULL),
  ('TASK_OPENED', 1, NULL, '08:30:00', 'INBOX', 'OWNER,WARD_STAFF', NULL),
  ('WINDOW_OPEN', 1, NULL, '08:30:00', 'INBOX', 'OWNER,WARD_STAFF', NULL),
  ('DUE_SOON', 1, 2, '08:30:00', 'INBOX', 'OWNER,WARD_STAFF', NULL),
  ('TASK_OVERDUE', 1, NULL, '08:30:00', 'INBOX,SMS', 'OWNER,WARD_STAFF,CC_WARD_LEADER', 'NOTIFY_OVERDUE'),
  ('DAILY_DIGEST', 1, NULL, '08:30:00', 'INBOX,SMS', 'OWNER,WARD_STAFF', 'NOTIFY_DAILY_SUMMARY'),
  ('TASK_REASSIGNED', 1, NULL, NULL, 'INBOX', 'OWNER,WARD_STAFF', NULL),
  ('CALLBACK_DUE', 1, NULL, NULL, 'INBOX', 'OWNER,WARD_STAFF', NULL),
  ('UNREACHED_FOLLOWUP', 1, NULL, '08:30:00', 'INBOX', 'OWNER,WARD_STAFF', NULL),
  ('LOST_CANDIDATE', 1, NULL, '08:30:00', 'INBOX', 'OWNER,WARD_STAFF', NULL);

-- 「通知设置」菜单（plt_menu 36，followup:notice:config）授予 TENANT_ADMIN（老库补授；
-- 全新 local/dev 库本脚本先于租户种子 V100 执行，角色已由 base V1 建好，同样命中；幂等）。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 36
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 36
  );
