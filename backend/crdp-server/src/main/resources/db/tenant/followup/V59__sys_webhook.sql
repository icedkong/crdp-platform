-- E09-T06 / A-NTF-008、C-NTF-008、PLT-CFG-007：租户 Webhook 配置与投递台账。
--
--   sys_webhook        —— 本院 Webhook 配置（/system/settings「Webhook」页签，权限 tenant:webhook:edit，
--                         平台库 V47 按钮 343、租户库种子 V121 授予 TENANT_ADMIN）：
--     * url 仅 HTTPS 且禁止内网 / 回环 / 链路本地地址（tech-plan §8 防 SSRF，WebhookSsrfGuard 保存与投递双重校验）；
--     * secret_cipher 是 CryptoService 信封加密密文（purpose=webhook-secret，每租户数据密钥），
--       明文只在新建 / 更换时回显一次（notify.md §6「secret 只写不读」），日志与审计永不落明文；
--     * events CSV 即订阅事件（TASK_OPENED / TASK_OVERDUE / RECORD_SUBMITTED / PATIENT_DEAD；
--       A / C 差异由本院勾选体现——C 不勾患者死亡即可，不写租户分支）。
--   sys_webhook_delivery —— 投递队列 + 台账（PLT-CFG-007「投递记录」：按事件 / 状态筛选，失败可手动重投）：
--     * 负载只含事件类型与对象 ID（字符串），不含患者信息（A-NTF-008 / A-NFR-007）；
--     * (webhook_id, outbox_event_id) 唯一键：outbox 至少一次语义下订阅方重放不重排；
--       测试投递（WEBHOOK_TEST）无来源事件、outbox_event_id 为 NULL 不占唯一键（MySQL NULL 不参与唯一冲突）；
--     * 重试进度持久化（attempt / next_attempt_at，指数退避 3 次后 FAILED），重启不丢退避进度
--       （比 sys_outbox 分发器的内存重试台账口径更强，OQ-52 的 Webhook 侧答案）。

CREATE TABLE sys_webhook (
  id BIGINT NOT NULL AUTO_INCREMENT,
  name VARCHAR(100) NOT NULL COMMENT '名称（本院内唯一语义由页面维护，不做硬约束）',
  url VARCHAR(500) NOT NULL COMMENT '回调地址：仅 HTTPS，内网 / 回环 / 链路本地拒绝（保存与投递双校验）',
  secret_cipher VARCHAR(2000) NOT NULL COMMENT '签名密钥密文（CryptoService purpose=webhook-secret；明文只在生成/更换时回显一次）',
  events VARCHAR(200) NOT NULL COMMENT '订阅事件 CSV：TASK_OPENED/TASK_OVERDUE/RECORD_SUBMITTED/PATIENT_DEAD',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '0 停用（不排队不投递）/ 1 启用',
  last_delivery_at DATETIME(3) NULL COMMENT '最近一次成功投递时间（配置页展示）',
  created_by BIGINT NULL COMMENT '创建人 sys_user.id',
  updated_by BIGINT NULL COMMENT '最近修改人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_sys_webhook_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户 Webhook 配置（A-NTF-008 / C-NTF-008 / PLT-CFG-007）';

CREATE TABLE sys_webhook_delivery (
  id BIGINT NOT NULL AUTO_INCREMENT,
  webhook_id BIGINT NOT NULL COMMENT 'sys_webhook.id（配置删除时随删）',
  outbox_event_id BIGINT NULL COMMENT '来源 sys_outbox.id（幂等锚点；WEBHOOK_TEST 测试投递为 NULL）',
  event_type VARCHAR(64) NOT NULL COMMENT '事件类型（含 WEBHOOK_TEST）',
  object_type VARCHAR(16) NULL COMMENT '对象类型：TASK / RECORD / PATIENT / WEBHOOK',
  object_id VARCHAR(64) NULL COMMENT '对象 ID（字符串序列化，超大 ID 不失真）',
  payload VARCHAR(1000) NOT NULL COMMENT '请求体 JSON（eventType/objectType/objectId/tenantCode/occurredAt，无患者信息）',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING 待投/退避中 / SENT 已投（2xx）/ FAILED 重试耗尽或地址复核不过',
  attempt INT NOT NULL DEFAULT 0 COMMENT '已失败次数（退避按它翻倍）',
  next_attempt_at DATETIME(3) NULL COMMENT '下一次投递时间（SENT / FAILED 为 NULL）',
  response_status INT NULL COMMENT '接收方 HTTP 状态码（网络异常为 NULL）',
  error VARCHAR(200) NULL COMMENT '失败类别（内网拒绝 / 非 2xx / 网络异常；不含 URL 与响应体）',
  sent_at DATETIME(3) NULL COMMENT '成功投递时间',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_webhook_delivery (webhook_id, outbox_event_id),
  KEY idx_webhook_delivery_due (status, next_attempt_at),
  KEY idx_webhook_delivery_webhook (webhook_id, status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='Webhook 投递台账与重试队列（PLT-CFG-007 投递记录）';
