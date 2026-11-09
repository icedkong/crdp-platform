-- E20-T05 / PLT-VIEW-014、015：高敏规则变更的第二人复核 + 规则版本链。
--   plt_view_rule_version——版本链（PLT-VIEW-015）：规则每次变更（新建 / 维护 / 回滚）一条版本行，
--   记录变更人（proposed_by）、前后值（before_json / after_json，含适用角色清单）与复核人
--   （reviewed_by，高敏变更必填且须为发起人以外的平台账号）。版本号按「该规则版本链 MAX(version)+1」
--   分配，驳回后不复用（uk 保证链上唯一）；普通变更提交即 APPLIED（applied_at=提交时刻），
--   高敏变更 PENDING_REVIEW——复核通过才写入规则行（PLT-VIEW-014「复核前规则保持原状」），
--   驳回则规则行不动（新建的待复核规则行置 DISABLED，proposal 终态 REJECTED）。
--   高敏判定（OQ-99 ③）：以「行的生效内容」按严格序比较——可见性 隐藏>设盲>脱敏>明文 下调、
--   只读→可编辑、禁止导出→允许导出，以及把生效中的限制性规则（可见性非明文）的选择器
--   （层级 / 租户 / 模块 / 资源 / 字段）或适用角色收窄（取消其原覆盖目标的限制）；
--   生效内容 = 规则行 ACTIVE 时为行内容，否则为系统初始默认（可见、只读、禁止导出）。
--   plt_view_rule.status 新增 PENDING_REVIEW（待复核，新建即高敏时规则行的初始状态，
--   解析只取 ACTIVE，V56 列注释预告）；回滚 = 以历史版本内容发起一次新变更
--   （POST /api/platform/view-rules/{id}/rollback，是否待复核按同一高敏判定）。
-- 权限点（platform-spec §8 权限点表，码创建后不改名，OQ-11；V56 预告本任务登记）：
--   platform:view:rule:review（按钮 149，挂 147 视角规则，复核高敏变更）；
--   platform:view:session:view（按钮 1293，挂 129 审计中心，查看全部租户视角会话记录，
--   PLT-VIEW-016 审计中心「视角会话」列表的口径）。
-- 授予 PLATFORM_ADMIN 与 V50/V56 同款：主迁移 INSERT..SELECT（全新库此时还没有 PLATFORM_ADMIN 行，
--   命中 0 行无害；种子 V111 对 PLATFORM_ADMIN 补授）。

CREATE TABLE plt_view_rule_version (
  id BIGINT NOT NULL AUTO_INCREMENT,
  rule_id BIGINT NOT NULL COMMENT '规则 plt_view_rule.id',
  rule_code VARCHAR(64) NOT NULL COMMENT '规则编号（快照，展示与审计对账）',
  version INT NOT NULL COMMENT '版本号（应用后规则行的 version；按版本链 MAX+1 分配，驳回不复用）',
  change_type VARCHAR(16) NOT NULL COMMENT 'CREATE 新建 / UPDATE 维护（含停用启用）/ ROLLBACK 回滚',
  is_sensitive TINYINT NOT NULL DEFAULT 0 COMMENT '高敏变更（放宽：可见性下调 / 取消只读 / 允许导出 / 收窄限制性规则范围，OQ-99 ③；sensitive 是 MySQL 保留字故加前缀）',
  status VARCHAR(16) NOT NULL COMMENT 'PENDING_REVIEW 待复核 / APPLIED 已生效 / REJECTED 已驳回',
  before_json TEXT NULL COMMENT '变更前内容快照（含适用角色 id 与编码；新建为 NULL=系统初始默认）',
  after_json TEXT NOT NULL COMMENT '变更后内容快照（含适用角色 id 与编码；复核通过即按此写入规则行）',
  proposed_by BIGINT NULL COMMENT '变更人（平台账号）',
  proposed_by_name VARCHAR(64) NULL COMMENT '变更人账号（快照）',
  proposed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  reviewed_by BIGINT NULL COMMENT '复核人（平台账号；高敏变更必填，须为发起人以外）',
  reviewed_by_name VARCHAR(64) NULL COMMENT '复核人账号（快照）',
  reviewed_at DATETIME(3) NULL,
  review_comment VARCHAR(500) NULL COMMENT '复核意见（同意 / 驳回，≤500）',
  applied_at DATETIME(3) NULL COMMENT '生效时间（普通变更=提交即生效）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_pvr_version (rule_id, version),
  KEY idx_pvr_status (status, proposed_at),
  KEY idx_pvr_proposed_by (proposed_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='视角规则版本链（PLT-VIEW-015；高敏变更待复核→第二人复核生效，PLT-VIEW-014）';

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (149, 147, 'PLATFORM', 'BUTTON', 'base', '复核高敏变更', NULL, NULL, NULL, 'platform:view:rule:review', NULL, 20, 1, 'ENABLED'),
  (1293, 129, 'PLATFORM', 'BUTTON', 'base', '查看视角会话记录', NULL, NULL, NULL, 'platform:view:session:view', NULL, 30, 1, 'ENABLED');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (149, 1293);
