-- 到期提醒与停用补偿底座（E05-T03 / PLT-TEN-012、016、017）。两张表都只存平台侧事实，不含医院业务数据：
--
-- plt_tenant_suspension 停用时段（PLT-TEN-016、017 的底座）：租户每次停用开一段（cause 记人工 / 宽限期满自动），
-- 恢复（续期或人工启用）补 restored_at。停用补偿（PLT-TEN-017）由业务模块按「停用时段内本应执行」推导，
-- 把任务标记为“停用期间未执行”（fu_task 创建方式，E14-T06），底座只提供时段查询（GET /tenants/{id}/suspensions）。
-- 同一租户同时最多一段未关闭（open 用 NOT EXISTS 防重）；E05-T03 之前已停用的存量租户没有时段行，恢复时无段可关，按无操作处理。
--
-- plt_tenant_expire_notice 到期提醒派发记录（PLT-TEN-012）：每日作业在租户配置的提醒节点（到期前 N 天）解析接收人
-- （租户管理员 sys_user·TENANT_ADMIN 与平台侧 plt_account·PLATFORM_ADMIN）并落一条 PENDING 派发行，
-- 唯一键保证同一节点不重发。站内信 / 短信实际通道随 E09 通知域接入后消费本表（send_status PENDING → SENT/FAILED），
-- 平台负责人按租户指定的能力随 PLT-VIEW-006（负责租户多选）落地，当前回落到全部启用的 PLATFORM_ADMIN（见 OQ-44）。

CREATE TABLE plt_tenant_suspension (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  cause VARCHAR(16) NOT NULL COMMENT '停用原因类别：MANUAL 人工停用 / AUTO_GRACE_END 宽限期满自动',
  reason VARCHAR(255) NOT NULL COMMENT '停用原因（人工填写或自动说明）',
  disabled_at DATETIME(3) NOT NULL,
  restored_at DATETIME(3) NULL COMMENT '恢复时间；NULL 表示仍在停用中',
  restore_cause VARCHAR(16) NULL COMMENT '恢复途径：RENEWAL 续期 / MANUAL_ENABLE 人工启用',
  PRIMARY KEY (id),
  KEY idx_plt_suspension_tenant (tenant_id, disabled_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE plt_tenant_expire_notice (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  remind_date DATE NOT NULL COMMENT '提醒触发日（= 到期日 − N 天，按补跑逻辑日计算）',
  days_left INT NOT NULL COMMENT '距到期天数（提醒节点 N）',
  recipient_type VARCHAR(16) NOT NULL COMMENT 'TENANT_ADMIN 租户管理员 / PLATFORM_OWNER 平台负责人',
  recipient_id BIGINT NOT NULL COMMENT 'sys_user.id 或 plt_account.id',
  recipient_name VARCHAR(64) NOT NULL COMMENT '接收人显示名（冗余，排查用；不含手机号等敏感明文）',
  channels VARCHAR(32) NOT NULL DEFAULT 'INBOX,SMS' COMMENT '派发渠道（规格：站内信与短信）',
  send_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 待通道（E09）/ SENT / FAILED',
  sent_at DATETIME(3) NULL,
  error_text VARCHAR(500) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_expire_notice (tenant_id, remind_date, recipient_type, recipient_id),
  KEY idx_plt_expire_notice_pending (send_status, remind_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
