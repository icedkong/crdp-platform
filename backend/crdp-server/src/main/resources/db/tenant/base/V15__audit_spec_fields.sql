-- E08-T01 / PLT-AUD-001~003：审计日志按规格字段写入（platform-spec §9 通用字段 + 扩展字段）。
-- 只新增列与新表，不改旧列、不搬旧数据；event_type / action / target / detail / username /
-- success / message 等 M0 列保留，E08-T04 查询与既有用例继续可用。
-- 哈希链列（prev_hash / hash）本任务只建列不填值，E08-T02 计算封存。

-- 操作日志（PLT-AUD-001）：规格通用字段补全
ALTER TABLE aud_log
  ADD COLUMN user_name VARCHAR(64) NULL COMMENT '账号' AFTER actor_id,
  ADD COLUMN display_name VARCHAR(128) NULL COMMENT '姓名（示例）' AFTER user_name,
  ADD COLUMN user_type VARCHAR(16) NULL COMMENT 'HOSPITAL / PLATFORM' AFTER display_name,
  ADD COLUMN role_codes VARCHAR(512) NULL COMMENT '角色编码，逗号分隔' AFTER user_type,
  ADD COLUMN module VARCHAR(64) NULL COMMENT '模块（system/followup/...）' AFTER role_codes,
  ADD COLUMN object_type VARCHAR(64) NULL COMMENT '对象类型（不记患者明文）' AFTER module,
  ADD COLUMN object_id VARCHAR(128) NULL COMMENT '对象 ID' AFTER object_type,
  ADD COLUMN result VARCHAR(16) NULL COMMENT 'SUCCESS / DENIED / FAIL' AFTER object_id,
  ADD COLUMN error_code INT NULL COMMENT '错误码' AFTER result,
  ADD COLUMN reason VARCHAR(512) NULL COMMENT '失败 / 拒绝原因' AFTER error_code,
  ADD COLUMN before_json TEXT NULL COMMENT '变更前值（敏感值只存摘要）' AFTER reason,
  ADD COLUMN after_json TEXT NULL COMMENT '变更后值（敏感值只存摘要）' AFTER before_json,
  ADD COLUMN ip VARCHAR(64) NULL COMMENT '来源 IP（X-Forwarded-For 首段）' AFTER after_json,
  ADD COLUMN user_agent VARCHAR(255) NULL COMMENT '终端信息' AFTER ip,
  ADD COLUMN view_session_id VARCHAR(64) NULL COMMENT '视角会话号（平台账号在租户视角访问时）' AFTER user_agent,
  ADD COLUMN prev_hash VARCHAR(64) NULL COMMENT '前序哈希（E08-T02 哈希链）' AFTER view_session_id,
  ADD COLUMN hash VARCHAR(64) NULL COMMENT '本条哈希（E08-T02 哈希链）' AFTER prev_hash,
  ADD KEY idx_aud_log_user (user_name),
  ADD KEY idx_aud_log_trace (trace_id);

-- 登录日志（PLT-AUD-002）：扩展字段（登录方式、租户识别方式、第二因素结果、失败原因、会话 ID）
ALTER TABLE aud_login_log
  ADD COLUMN user_id BIGINT NULL COMMENT '用户 ID' AFTER username,
  ADD COLUMN display_name VARCHAR(128) NULL COMMENT '姓名（示例）' AFTER user_id,
  ADD COLUMN user_type VARCHAR(16) NULL COMMENT 'HOSPITAL / PLATFORM' AFTER display_name,
  ADD COLUMN role_codes VARCHAR(512) NULL COMMENT '角色编码，逗号分隔' AFTER user_type,
  ADD COLUMN module VARCHAR(64) NULL COMMENT '模块（system）' AFTER role_codes,
  ADD COLUMN action VARCHAR(64) NULL COMMENT 'LOGIN / LOGOUT / LOCK / UNLOCK / KICK' AFTER module,
  ADD COLUMN result VARCHAR(16) NULL COMMENT 'SUCCESS / DENIED / FAIL' AFTER action,
  ADD COLUMN error_code INT NULL COMMENT '错误码' AFTER result,
  ADD COLUMN login_method VARCHAR(16) NULL COMMENT 'PASSWORD / SMS' AFTER error_code,
  ADD COLUMN tenant_identify_method VARCHAR(16) NULL COMMENT 'SUBDOMAIN / INPUT' AFTER login_method,
  ADD COLUMN second_factor_result VARCHAR(16) NULL COMMENT 'PASS / FAIL / SKIP' AFTER tenant_identify_method,
  ADD COLUMN fail_reason VARCHAR(128) NULL COMMENT '失败原因' AFTER second_factor_result,
  ADD COLUMN session_id VARCHAR(64) NULL COMMENT '会话号（令牌哈希前 16 位）' AFTER fail_reason,
  ADD COLUMN ip VARCHAR(64) NULL COMMENT '来源 IP' AFTER session_id,
  ADD COLUMN user_agent VARCHAR(255) NULL COMMENT '终端信息' AFTER ip,
  ADD COLUMN prev_hash VARCHAR(64) NULL COMMENT '前序哈希（E08-T02 哈希链）' AFTER user_agent,
  ADD COLUMN hash VARCHAR(64) NULL COMMENT '本条哈希（E08-T02 哈希链）' AFTER prev_hash,
  ADD KEY idx_aud_login_created (created_at);

-- 数据访问日志（PLT-AUD-003）：查看明文、导出、打印、下载、批量查询
CREATE TABLE aud_data_access_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NULL COMMENT '操作者用户 ID',
  user_name VARCHAR(64) NULL COMMENT '账号',
  display_name VARCHAR(128) NULL COMMENT '姓名（示例）',
  user_type VARCHAR(16) NULL COMMENT 'HOSPITAL / PLATFORM',
  role_codes VARCHAR(512) NULL COMMENT '角色编码，逗号分隔',
  module VARCHAR(64) NOT NULL COMMENT '模块',
  action VARCHAR(64) NOT NULL COMMENT '操作类型',
  access_method VARCHAR(32) NOT NULL COMMENT 'VIEW_PLAIN / EXPORT / PRINT / DOWNLOAD / BATCH_QUERY',
  object_type VARCHAR(64) NULL COMMENT '对象类型（不记患者明文）',
  object_id VARCHAR(128) NULL COMMENT '对象 ID',
  record_count BIGINT NULL COMMENT '涉及记录数',
  query_digest VARCHAR(512) NULL COMMENT '查询条件摘要（不含敏感明文）',
  file_hash VARCHAR(128) NULL COMMENT '导出文件哈希',
  result VARCHAR(16) NOT NULL COMMENT 'SUCCESS / DENIED / FAIL',
  error_code INT NULL COMMENT '错误码',
  reason VARCHAR(512) NULL COMMENT '失败 / 拒绝原因',
  description VARCHAR(255) NULL COMMENT '操作描述',
  ip VARCHAR(64) NULL COMMENT '来源 IP',
  user_agent VARCHAR(255) NULL COMMENT '终端信息',
  trace_id VARCHAR(64) NULL COMMENT '链路追踪',
  view_session_id VARCHAR(64) NULL COMMENT '视角会话号',
  prev_hash VARCHAR(64) NULL COMMENT '前序哈希（E08-T02 哈希链）',
  hash VARCHAR(64) NULL COMMENT '本条哈希（E08-T02 哈希链）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_aud_dal_created (created_at),
  KEY idx_aud_dal_user (user_name),
  KEY idx_aud_dal_trace (trace_id),
  KEY idx_aud_dal_object (object_type, object_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
