-- 安全告警站内信派发行（E08-T05 / PLT-AUD-012，验收：触发规则后站内信告警）。
--
-- 规则命中（异常登录 LOCK、批量导出 EXPORT、权限变更 ROLE_*、平台访问 VIEW_*，定义见 OQ-58）即按「接收人 × 触发」
-- 展开一行：接收人 = 全部启用的 PLATFORM_ADMIN（plt_account × plt_role，按租户指定平台负责人随 PLT-VIEW-006，
-- 回落口径同 OQ-44 到期提醒）；channel 固定 INBOX、send_status 从 PENDING 起步——站内信收件箱与 PENDING → SENT/FAILED
-- 的实际投递随 E09 通知域接入后消费本表（NotifySender 目前为占位实现，与 plt_tenant_expire_notice 同一消费契约）。
--
-- 唯一键 (rule_code, alert_key, recipient_id)：alert_key 取触发请求的 traceId，同一触发（同一 traceId）不重复派发；
-- traceId 为空时 MySQL 唯一键对 NULL 不去重（程序化直写探针场景），可接受——告警是派生事实，多一条不影响正确性。
-- 本表不是审计链表（无哈希链、无只读触发器），事实源是审计日志，可对账、可按运维需要清理。

CREATE TABLE plt_security_alert (
  id BIGINT NOT NULL AUTO_INCREMENT,
  rule_code VARCHAR(32) NOT NULL COMMENT '命中规则：ABNORMAL_LOGIN 异常登录 / BATCH_EXPORT 批量导出 / PERMISSION_CHANGE 权限变更 / PLATFORM_ACCESS 平台访问',
  alert_key VARCHAR(64) NULL COMMENT '触发去重键（traceId；同一触发不重复派发）',
  tenant_id BIGINT NULL COMMENT '事件所属租户；平台侧事件为 NULL',
  user_id BIGINT NULL COMMENT '触发用户 ID（登录失败未知账号为 NULL）',
  user_name VARCHAR(64) NULL COMMENT '触发账号（不存手机号等敏感明文）',
  display_name VARCHAR(128) NULL COMMENT '触发人显示名',
  user_type VARCHAR(16) NULL COMMENT 'HOSPITAL / PLATFORM',
  module VARCHAR(64) NULL COMMENT '审计模块',
  action VARCHAR(64) NULL COMMENT '触发动作（如 LOCK / EXPORT / ROLE_MEMBERS）',
  object_type VARCHAR(64) NULL COMMENT '对象类型（如 AUDIT_EXPORT）',
  object_id VARCHAR(128) NULL COMMENT '对象 ID',
  detail VARCHAR(500) NOT NULL COMMENT '告警摘要（简体中文，不含敏感明文）',
  trace_id VARCHAR(64) NULL COMMENT '关联审计记录 traceId',
  recipient_id BIGINT NOT NULL COMMENT '接收人 plt_account.id（启用的 PLATFORM_ADMIN）',
  recipient_name VARCHAR(64) NOT NULL COMMENT '接收人显示名（冗余，排查用）',
  channel VARCHAR(16) NOT NULL DEFAULT 'INBOX' COMMENT '派发渠道：站内信（收件箱随 E09-T02）',
  send_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待通道（E09 消费）/ SENT / FAILED',
  sent_at DATETIME(3) NULL,
  error_text VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_security_alert (rule_code, alert_key, recipient_id),
  KEY idx_plt_security_alert_pending (send_status, created_at),
  KEY idx_plt_security_alert_rule (rule_code, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='安全告警站内信派发行（E08-T05 / PLT-AUD-012；通道消费随 E09）';
