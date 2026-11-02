-- E09-T03 / A-NTF-003、PLT-CFG-006、A-NFR-007：腾讯云短信通道（模板、签名、发送与回执）。
--   plt_sms_template —— 平台短信模板登记（技术方案第 3 章表清单）：{1}..{n} 变量占位、腾讯云模板 ID 由
--     E01-T13（产品：账号、签名与模板报备）审核通过后回填；只有 APPROVED 且已回填模板 ID 的能发。
--     短信模板全部平台内置（签名与模板主体是平台，租户只能在其中选择，notify.md §2），
--     PLT-CFG-006 的「租户自定义模板」落在站内信 / Webhook（E09-T04），短信侧不开放。
--   plt_sms_send_log —— 平台通道发送与回执台账（验收「回执入库」）：每条发送 SUBMITTED/SUBMIT_FAILED，
--     腾讯云状态回执按 serial_no（= 回执 sid）回填 DELIVERED/RECEIVE_FAILED；对不上发送行的回执落
--     UNMATCHED_RECEIPT 不丢。手机号只存脱敏值，不存模板参数（短信不含患者敏感信息）。
-- 种子为首批内置模板（内容只含验证码/数量与提示语）；provider_template_id 留空，部署侧报备后回填。

CREATE TABLE plt_sms_template (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL COMMENT '模板编码（平台侧稳定标识）',
  name VARCHAR(128) NOT NULL COMMENT '模板名称',
  scene VARCHAR(32) NOT NULL COMMENT '场景：AUTH_LOGIN/AUTH_RESET/AUTH_BIND/NOTIFY（参数白名单依据）',
  content VARCHAR(500) NOT NULL COMMENT '模板内容，{1}..{n} 变量占位（与腾讯云模板一致），不含患者信息',
  provider VARCHAR(16) NOT NULL DEFAULT 'TENCENT' COMMENT '短信服务商（首期只有腾讯云）',
  provider_template_id VARCHAR(32) NULL COMMENT '腾讯云模板 ID（E01-T13 报备审核通过后回填）',
  sign_name VARCHAR(64) NULL COMMENT '模板级签名，空则用通道默认签名（crdp.sms.sign-name）',
  status VARCHAR(16) NOT NULL COMMENT '审核状态：APPROVED 才可发送',
  built_in TINYINT NOT NULL DEFAULT 1 COMMENT '平台内置模板（内置不可删）',
  remark VARCHAR(255) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_sms_template_code (code),
  KEY idx_plt_sms_template_scene (scene, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台短信模板（PLT-CFG-006）';

CREATE TABLE plt_sms_send_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  purpose VARCHAR(32) NOT NULL COMMENT '用途：AUTH_LOGIN/AUTH_RESET/AUTH_BIND/NOTIFY/UNMATCHED（回执无对应发送行）',
  tenant_code VARCHAR(32) NULL COMMENT '发送时的租户上下文（登录验证码在登录前发出，可为空）',
  template_code VARCHAR(64) NULL,
  provider_template_id VARCHAR(32) NULL,
  serial_no VARCHAR(128) NULL COMMENT '腾讯云流水号（SerialNo，= 回执 sid），回执关联键',
  phone_masked VARCHAR(32) NULL COMMENT '脱敏手机号（138****0000），不存明文',
  status VARCHAR(24) NOT NULL COMMENT 'SUBMITTED/SUBMIT_FAILED/DELIVERED/RECEIVE_FAILED/UNMATCHED_RECEIPT',
  error_code VARCHAR(64) NULL COMMENT '失败类别（SENSITIVE_CONTENT/CHANNEL_DISABLED/PROVIDER_REJECTED...）',
  error_message VARCHAR(255) NULL,
  receipt_status VARCHAR(32) NULL COMMENT '回执 report_status（SUCCESS/FAIL）',
  receipt_description VARCHAR(255) NULL,
  user_receive_time DATETIME(3) NULL COMMENT '用户实际接收时间（回执 user_receive_time）',
  receipt_time DATETIME(3) NULL COMMENT '回执到达本平台时间',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_sms_send_serial (serial_no),
  KEY idx_plt_sms_send_created (created_at),
  KEY idx_plt_sms_send_purpose (purpose, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='平台短信发送与回执台账（A-NTF-006 渠道侧事实）';

INSERT INTO plt_sms_template
  (code, name, scene, content, provider, sign_name, status, built_in, remark) VALUES
  ('AUTH_LOGIN_CODE', '登录验证码', 'AUTH_LOGIN',
   '登录验证码{1}，请勿泄露给他人。如非本人操作请忽略。', 'TENCENT', NULL, 'APPROVED', 1,
   'E04-T04 / PLT-AUTH-002；参数：4-8 位数字验证码'),
  ('AUTH_RESET_CODE', '重置密码验证码', 'AUTH_RESET',
   '您正在重置密码，验证码{1}，请勿泄露给他人。如非本人操作请忽略。', 'TENCENT', NULL, 'APPROVED', 1,
   'E04-T07 / PLT-AUTH-011；与登录验证码分模板，防混用'),
  ('AUTH_BIND_CODE', '换绑手机号验证码', 'AUTH_BIND',
   '您正在换绑手机号，验证码{1}，请勿泄露给他人。如非本人操作请勿转发。', 'TENCENT', NULL, 'APPROVED', 1,
   'E06-T11 / PLT-ORG-011；发往新手机号'),
  ('NOTIFY_DAILY_SUMMARY', '随访每日汇总', 'NOTIFY',
   '您有{1}项随访任务待处理，请登录平台查看。', 'TENCENT', NULL, 'APPROVED', 1,
   'A-NTF-003 / C-NTF-003；按人合并的汇总短信，只含数量（E09-T05 汇总作业使用）'),
  ('NOTIFY_OVERDUE', '随访逾期提醒', 'NOTIFY',
   '您有{1}项随访任务已逾期，请及时登录处理。', 'TENCENT', NULL, 'APPROVED', 1,
   '已逾期事件短信（A-NTF-001 默认渠道含短信）；只含数量');
