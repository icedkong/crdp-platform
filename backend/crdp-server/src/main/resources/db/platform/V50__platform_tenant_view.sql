-- E20-T01 / PLT-VIEW-001、004、005、006：租户视角的授权数据与会话台账。
--   plt_role_view_tenant（PLT-VIEW-005）——平台角色租户范围 view_tenant_scope='SPECIFIED' 的指定租户清单
--   （E06-T04 起 SPECIFIED 暂拒绝提交，OQ-48；本任务放开并落清单，非 SPECIFIED 时清单不参与计算）。
--   plt_account_tenant（PLT-VIEW-006）——平台账号的负责租户多选：租户范围为「负责的租户」的角色只能进入这些
--   租户；到期提醒的平台负责人（OQ-44 ②）也按本表按租户指定。
--   plt_view_session（PLT-VIEW-001、004）——视角会话台账：会话号 = 视角令牌 SHA-256 前 16 位十六进制
--   （SessionPrincipal.sessionIdOf 单向推导，令牌本体只存 Redis）；单次最长 2 小时、空闲 15 分钟由
--   Redis 会话的 TTL 执行（absoluteSeconds=7200 / idle=平台端 15 分钟），本表只做台账与审计索引，
--   超时（到时 / 空闲）由 TenantViewScheduler 收尾（end_method 区分）。生效规则版本随 E20-T02 写实值，
--   初始默认规则记 'DEFAULT'。页面 / 查询 / 被拒 / 代为操作计数列随 E20-T03 起递增，本任务先落列。
-- 权限点（platform-spec §8 权限点表，码创建后不改名，OQ-11）：
--   platform:view:enter（按钮 143，挂 111 租户管理）、platform:view:grant:edit（按钮 144，挂 114 平台账号）。
--   （135–134 与 141/142 已占用，143/144 为空闲号段。）
-- 授予 PLATFORM_ADMIN 与 V20 同款做法：主迁移 INSERT..SELECT（全新库此时还没有 PLATFORM_ADMIN 行，
-- 命中 0 行无害；种子 V107 对全部预置角色补授）。

CREATE TABLE plt_role_view_tenant (
  role_id BIGINT NOT NULL COMMENT '平台角色 plt_role.id',
  tenant_id BIGINT NOT NULL COMMENT '指定租户 plt_tenant.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (role_id, tenant_id),
  KEY idx_plt_role_view_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='平台角色「指定租户」清单（PLT-VIEW-005，view_tenant_scope=SPECIFIED 时生效）';

CREATE TABLE plt_account_tenant (
  account_id BIGINT NOT NULL COMMENT '平台账号 plt_account.id',
  tenant_id BIGINT NOT NULL COMMENT '负责租户 plt_tenant.id',
  set_by BIGINT NULL COMMENT '分配人（平台账号）',
  set_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (account_id, tenant_id),
  KEY idx_plt_account_tenant_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='平台账号负责租户（PLT-VIEW-006：RESPONSIBLE 租户范围只能进入这些租户）';

CREATE TABLE plt_view_session (
  id BIGINT NOT NULL AUTO_INCREMENT,
  session_no VARCHAR(32) NOT NULL COMMENT '视角会话号（视角令牌 SHA-256 前 16 位十六进制，单向推导）',
  account_id BIGINT NOT NULL COMMENT '平台账号 plt_account.id（不借用医院用户身份）',
  account_name VARCHAR(64) NOT NULL COMMENT '平台账号（进入时快照）',
  display_name VARCHAR(128) NOT NULL COMMENT '姓名（示例）',
  role_codes VARCHAR(512) NOT NULL COMMENT '进入时的平台角色，逗号分隔（快照）',
  tenant_id BIGINT NOT NULL COMMENT '被查看租户 plt_tenant.id',
  purpose VARCHAR(32) NOT NULL COMMENT '查看目的：TROUBLESHOOT / CONFIG_CHECK / TRAINING / OTHER',
  note VARCHAR(500) NOT NULL COMMENT '说明（进入时必填）',
  rule_version VARCHAR(64) NOT NULL DEFAULT 'DEFAULT' COMMENT '生效规则版本（E20-T02 起写实值，初始默认规则为 DEFAULT）',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE 进行中 / ENDED 已结束',
  started_at DATETIME(3) NOT NULL COMMENT '开始时间',
  ends_at DATETIME(3) NOT NULL COMMENT '最晚结束时间（started_at + 2 小时，PLT-VIEW-004）',
  end_at DATETIME(3) NULL COMMENT '实际结束时间',
  end_method VARCHAR(32) NULL COMMENT '结束方式：LOGOUT 主动退出 / TIMEOUT 到时 / IDLE 空闲超时 / PERM_CHANGE 权限变更',
  page_count BIGINT NOT NULL DEFAULT 0 COMMENT '页面数（会话摘要，随 E20-T03 起递增）',
  query_count BIGINT NOT NULL DEFAULT 0 COMMENT '查询次数（会话摘要，随 E20-T03 起递增）',
  denied_count BIGINT NOT NULL DEFAULT 0 COMMENT '被拒次数（会话摘要，随 E20-T03 起递增）',
  proxy_count BIGINT NOT NULL DEFAULT 0 COMMENT '代为操作次数（会话摘要，随 E20-T06 起递增）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_view_session_no (session_no),
  KEY idx_plt_view_session_account (account_id, status),
  KEY idx_plt_view_session_tenant (tenant_id, id),
  KEY idx_plt_view_session_active (status, ends_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='租户视角会话台账（PLT-VIEW-001、004；令牌本体只存 Redis，到时 / 空闲超时由调度收尾）';

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (143, 111, 'PLATFORM', 'BUTTON', 'base', '进入租户视角', NULL, NULL, NULL, 'platform:view:enter', NULL, 50, 1, 'ENABLED'),
  (144, 114, 'PLATFORM', 'BUTTON', 'base', '维护账号负责租户', NULL, NULL, NULL, 'platform:view:grant:edit', NULL, 30, 1, 'ENABLED');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (143, 144);
