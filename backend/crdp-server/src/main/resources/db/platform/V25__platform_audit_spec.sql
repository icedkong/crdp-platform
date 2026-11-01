-- E08-T01 / PLT-AUD-002、004、PLT-TEN-022：平台库审计按规格字段写入。
-- plt_audit_log（平台操作 / 租户控制面日志）补规格通用字段；新增平台侧登录日志与平台访问日志表。
-- 哈希链列本任务只建列不填值，E08-T02 计算封存。

-- 平台操作日志（含租户控制面，PLT-TEN-022 按 tenant_id 维度检索）
ALTER TABLE plt_audit_log
  ADD COLUMN user_name VARCHAR(64) NULL COMMENT '账号' AFTER actor_id,
  ADD COLUMN display_name VARCHAR(128) NULL COMMENT '姓名（示例）' AFTER user_name,
  ADD COLUMN user_type VARCHAR(16) NULL COMMENT 'HOSPITAL / PLATFORM' AFTER display_name,
  ADD COLUMN role_codes VARCHAR(512) NULL COMMENT '角色编码，逗号分隔' AFTER user_type,
  ADD COLUMN module VARCHAR(64) NULL COMMENT '模块（platform）' AFTER role_codes,
  ADD COLUMN object_type VARCHAR(64) NULL COMMENT '对象类型' AFTER module,
  ADD COLUMN object_id VARCHAR(128) NULL COMMENT '对象 ID' AFTER object_type,
  ADD COLUMN result VARCHAR(16) NULL COMMENT 'SUCCESS / DENIED / FAIL' AFTER object_id,
  ADD COLUMN error_code INT NULL COMMENT '错误码' AFTER result,
  ADD COLUMN reason VARCHAR(512) NULL COMMENT '失败 / 拒绝原因' AFTER error_code,
  ADD COLUMN before_json TEXT NULL COMMENT '变更前值（敏感值只存摘要）' AFTER reason,
  ADD COLUMN after_json TEXT NULL COMMENT '变更后值（敏感值只存摘要）' AFTER before_json,
  ADD COLUMN ip VARCHAR(64) NULL COMMENT '来源 IP' AFTER after_json,
  ADD COLUMN user_agent VARCHAR(255) NULL COMMENT '终端信息' AFTER ip,
  ADD COLUMN view_session_id VARCHAR(64) NULL COMMENT '视角会话号' AFTER user_agent,
  ADD COLUMN prev_hash VARCHAR(64) NULL COMMENT '前序哈希（E08-T02 哈希链）' AFTER view_session_id,
  ADD COLUMN hash VARCHAR(64) NULL COMMENT '本条哈希（E08-T02 哈希链）' AFTER prev_hash,
  ADD KEY idx_plt_audit_user (user_name),
  ADD KEY idx_plt_audit_trace (trace_id),
  ADD KEY idx_plt_audit_tenant (tenant_id);

-- 平台登录日志（PLT-AUD-002 平台端）：平台账号登录成功 / 失败 / 登出 / 锁定 / 解锁 / 强制下线，
-- 以及租户未识别（写不进租户库）的医院端登录失败。
CREATE TABLE aud_login_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NULL COMMENT '关联租户（控制面解锁等场景），登录本身为 NULL',
  username VARCHAR(64) NOT NULL COMMENT '账号（手机号登录为脱敏手机号）',
  success TINYINT NOT NULL COMMENT '1 成功 / 0 失败',
  message VARCHAR(128) NOT NULL COMMENT '描述',
  trace_id VARCHAR(64) NULL COMMENT '链路追踪',
  user_id BIGINT NULL COMMENT '用户 ID',
  display_name VARCHAR(128) NULL COMMENT '姓名（示例）',
  user_type VARCHAR(16) NULL COMMENT 'HOSPITAL / PLATFORM',
  role_codes VARCHAR(512) NULL COMMENT '角色编码，逗号分隔',
  module VARCHAR(64) NULL COMMENT '模块（platform）',
  action VARCHAR(64) NULL COMMENT 'LOGIN / LOGOUT / LOCK / UNLOCK / KICK',
  result VARCHAR(16) NULL COMMENT 'SUCCESS / DENIED / FAIL',
  error_code INT NULL COMMENT '错误码',
  login_method VARCHAR(16) NULL COMMENT 'PASSWORD / SMS',
  tenant_identify_method VARCHAR(16) NULL COMMENT 'SUBDOMAIN / INPUT',
  second_factor_result VARCHAR(16) NULL COMMENT 'PASS / FAIL / SKIP',
  fail_reason VARCHAR(128) NULL COMMENT '失败原因',
  session_id VARCHAR(64) NULL COMMENT '会话号（令牌哈希前 16 位）',
  ip VARCHAR(64) NULL COMMENT '来源 IP',
  user_agent VARCHAR(255) NULL COMMENT '终端信息',
  prev_hash VARCHAR(64) NULL COMMENT '前序哈希（E08-T02 哈希链）',
  hash VARCHAR(64) NULL COMMENT '本条哈希（E08-T02 哈希链）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_plt_login_created (created_at),
  KEY idx_plt_login_user (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 平台访问日志（PLT-AUD-004）：租户视角会话、页面、查询、被拒操作、代为操作与视角规则变更。
-- 只存平台库，医院端不提供查看入口（platform-spec §9 业务规则）。
CREATE TABLE aud_platform_access_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NULL COMMENT '被查看 / 操作的租户',
  user_id BIGINT NULL COMMENT '平台账号 ID',
  user_name VARCHAR(64) NULL COMMENT '平台账号',
  display_name VARCHAR(128) NULL COMMENT '姓名（示例）',
  user_type VARCHAR(16) NULL COMMENT '固定 PLATFORM',
  role_codes VARCHAR(512) NULL COMMENT '平台角色，逗号分隔',
  module VARCHAR(64) NOT NULL COMMENT '被访问模块（followup/system/...）',
  action VARCHAR(64) NOT NULL COMMENT '操作类型（VIEW_SESSION / PAGE_VIEW / QUERY / DENIED / PROXY_EDIT / VIEW_RULE ...）',
  description VARCHAR(255) NULL COMMENT '操作描述',
  object_type VARCHAR(64) NULL COMMENT '对象类型（不记患者明文）',
  object_id VARCHAR(128) NULL COMMENT '对象 ID',
  view_session_id VARCHAR(64) NULL COMMENT '视角会话号（平台访问记录必填）',
  page VARCHAR(128) NULL COMMENT '租户视角内访问的页面路径',
  access_method VARCHAR(32) NULL COMMENT 'VIEW_PLAIN / EXPORT / PRINT / DOWNLOAD / BATCH_QUERY（数据访问类操作）',
  record_count BIGINT NULL COMMENT '涉及记录数',
  query_digest VARCHAR(512) NULL COMMENT '查询条件摘要（不含敏感明文）',
  file_hash VARCHAR(128) NULL COMMENT '导出文件哈希',
  before_json TEXT NULL COMMENT '代为操作前值（敏感值只存摘要）',
  after_json TEXT NULL COMMENT '代为操作后值（敏感值只存摘要）',
  result VARCHAR(16) NOT NULL COMMENT 'SUCCESS / DENIED / FAIL',
  error_code INT NULL COMMENT '错误码',
  reason VARCHAR(512) NULL COMMENT '失败 / 拒绝原因',
  ip VARCHAR(64) NULL COMMENT '来源 IP',
  user_agent VARCHAR(255) NULL COMMENT '终端信息',
  trace_id VARCHAR(64) NULL COMMENT '链路追踪',
  prev_hash VARCHAR(64) NULL COMMENT '前序哈希（E08-T02 哈希链）',
  hash VARCHAR(64) NULL COMMENT '本条哈希（E08-T02 哈希链）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_apal_created (created_at),
  KEY idx_apal_tenant (tenant_id),
  KEY idx_apal_view_session (view_session_id),
  KEY idx_apal_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
