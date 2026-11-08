-- E19-T06 / A-DASH-013、A-DASH-014（首页卡片目录、按角色布局与用户隐藏 / 显示）：
-- 首页卡片配置的数据底座（docs/api/system.md §5、stats.md §3）。
--
--   * dash_card 卡片目录：固定卡片清单随卡片发版以迁移落库（stats.md §3「种子配置」，E21 的
--     C 卡片 device_dist / eri_list / abnormal_records / scan_progress 随各自任务追加）；
--     不提供增删改接口——卡片是否存在是代码能力（GET /dashboard/cards/{cardCode} 有无该分支），
--     不是租户数据。目录只决定「显示与顺序」，不放宽权限：每张卡片照常受 permission_code
--     （多为 followup:dashboard:view，OQ-19）与 feature_code 功能开关（如 node_rate 受
--     node_follow_rate，AGENTS §5.7）约束，数据范围仍由各卡片 Mapper 的 @DataScope 出行。
--   * dash_role_layout 按角色布局（A-DASH-013）：租户管理员经 GET/PUT/DELETE
--     /dashboard/role-layouts 配置；未配置的角色使用目录默认布局；医院 A / B 的默认差异
--     （节点随访率 A 默认不显示，B、C 初始化后经本表开启，A 规格 §14）走 default_visible=0
--     + 角色配置，不写死租户编码。
--   * dash_user_pref 用户隐藏偏好（A-DASH-014，P1）：用户在本人角色允许的卡片范围内隐藏 /
--     重新显示（PUT /dashboard/user-pref 全量覆盖；恢复默认 = 空清单），不能加入角色未允许
--     的卡片，也没有顺序字段（用户不能调顺序）。
--
-- 配置即时生效（tasks.csv E19-T06 验收「按角色配置后即生效」）：读取路径每次实时查库，不做缓存。

CREATE TABLE dash_card (
  card_code VARCHAR(64) NOT NULL COMMENT '卡片编码（与 GET /dashboard/cards/{cardCode} 同一命名，stats.md §3）',
  title VARCHAR(64) NOT NULL COMMENT '卡片标题（配置页与目录展示）',
  width VARCHAR(8) NOT NULL DEFAULT 'FULL' COMMENT '宽度：FULL 整行 / HALF 半行（相邻两张半行卡并排，A 规格 §9）',
  default_visible TINYINT NOT NULL DEFAULT 1 COMMENT '默认布局是否显示（未配置角色的回落值）',
  default_order INT NOT NULL COMMENT '默认布局顺序（从上到下，从 1 起）',
  permission_code VARCHAR(128) NOT NULL DEFAULT 'followup:dashboard:view' COMMENT '卡片权限码（会话未持有时该卡片不显示，A 规格 §9「没有对应功能权限时卡片不显示」）',
  feature_code VARCHAR(64) NULL COMMENT '依赖的功能开关（cfg_feature；NULL 不依赖，开关关闭时卡片不可用）',
  allow_user_hide TINYINT NOT NULL DEFAULT 1 COMMENT '默认是否允许用户隐藏（角色配置可逐卡覆盖，A 规格 §9 关键字段「允许用户隐藏」）',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT '目录状态（随卡片下线的迁移维护，暂无 DISABLED 项）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (card_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='首页卡片目录（A-DASH-013；固定清单随卡片发版以迁移落库，E19-T06）';

CREATE TABLE dash_role_layout (
  id BIGINT NOT NULL AUTO_INCREMENT,
  role_id BIGINT NOT NULL COMMENT '角色 sys_role.id（租户内；模板 / 停用角色同样可配置，生效看用户持有）',
  card_code VARCHAR(64) NOT NULL COMMENT '卡片 dash_card.card_code',
  visible TINYINT NOT NULL COMMENT '该角色是否显示此卡片',
  sort_order INT NOT NULL COMMENT '从上到下的顺序（角色内从 1 起；相邻半行卡并排）',
  allow_user_hide TINYINT NOT NULL DEFAULT 1 COMMENT '本角色是否允许用户隐藏该卡片（A-DASH-014 前置）',
  updated_by BIGINT NOT NULL COMMENT '最近修改人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_dash_role_layout (role_id, card_code),
  KEY idx_dash_role_card (card_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='首页卡片按角色布局（A-DASH-013；PUT 全量覆盖、DELETE 恢复默认；E19-T06）';

CREATE TABLE dash_user_pref (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL COMMENT '用户 sys_user.id',
  card_code VARCHAR(64) NOT NULL COMMENT '卡片 dash_card.card_code',
  hidden TINYINT NOT NULL DEFAULT 1 COMMENT '是否隐藏（表内只存隐藏行；PUT 全量覆盖）',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_dash_user_pref (user_id, card_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户卡片隐藏偏好（A-DASH-014，P1；只能在角色允许且允许隐藏的卡片内；E19-T06）';

-- 卡片目录种子（stats.md §3；A 规格 §9「首页卡片目录」）。E19-T02~T05 已落地的八张卡：
--   * 任务区在目录中是两张卡（task_board 看板 / task_table 任务表）：A 默认都显示（首页同一卡片区
--     「看板 | 任务表」切换），医院 C 只用看板（C-DASH-008 无 C 需求编号），配置粒度与 stats.md §3 一致；
--   * node_rate 节点随访率 default_visible=0：医院 A 默认不显示（A 规格 §9「医院 A 默认不显示」、
--     §14「B 初始化后在首页卡片配置中开启」），可用性仍受 node_follow_rate 开关约束（关 = 卡片不可用）；
--   * 半行卡两张：基线 / 随访资料完整度相邻并排（default_order 70 / 71）。
INSERT INTO dash_card
  (card_code, title, width, default_visible, default_order, permission_code, feature_code, allow_user_hide, status) VALUES
  ('kpi', '指标卡', 'FULL', 1, 10, 'followup:dashboard:view', NULL, 1, 'ENABLED'),
  ('trend', '趋势图', 'FULL', 1, 20, 'followup:dashboard:view', NULL, 1, 'ENABLED'),
  ('task_board', '任务看板', 'FULL', 1, 30, 'followup:dashboard:view', NULL, 1, 'ENABLED'),
  ('task_table', '任务表', 'FULL', 1, 40, 'followup:dashboard:view', NULL, 1, 'ENABLED'),
  ('yearly_rate', '每年随访率', 'FULL', 1, 50, 'followup:dashboard:view', NULL, 1, 'ENABLED'),
  ('node_rate', '节点随访率', 'FULL', 0, 60, 'followup:dashboard:view', 'node_follow_rate', 1, 'ENABLED'),
  ('baseline_completeness', '基线资料完整度', 'HALF', 1, 70, 'followup:dashboard:view', NULL, 1, 'ENABLED'),
  ('followup_completeness', '随访资料完整度', 'HALF', 1, 71, 'followup:dashboard:view', NULL, 1, 'ENABLED');
