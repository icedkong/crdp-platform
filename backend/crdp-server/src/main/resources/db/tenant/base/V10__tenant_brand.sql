-- E06-T11 / PLT-CFG-008：租户个性化（显示名称、Logo、登录页背景、预设主题色、版权信息）。
-- 单行配置表（应用层按 id=1 读写），存租户库；未配置字段落 NULL，读取时回落平台默认：
--   display_name   显示名称（必填项，未配置时回落平台库租户名称）
--   logo_url       Logo 素材地址（PNG / SVG，≤1MB 由上传侧约束；对象存储在 E10 接入，
--                  此前先存素材 URL，见 open-questions OQ-49）
--   login_bg_url   登录页背景素材地址（未配置用平台默认）
--   theme_color    预设主题色 key（BLUE / TEAL / ORANGE / RED，取值来自 docs/design/tokens.ts；
--                  主色固定 #3A75FF，租户只能选预设辅助色，见 OQ-23）
--   copyright      版权与备案信息（登录页底部）
-- 素材按规格存该租户对象存储桶；本表只存引用地址。

CREATE TABLE cfg_brand (
  id BIGINT NOT NULL AUTO_INCREMENT,
  display_name VARCHAR(128) NULL COMMENT '显示名称，NULL=未配置（回落平台租户名称）',
  logo_url VARCHAR(512) NULL COMMENT 'Logo 素材地址（PNG/SVG）',
  login_bg_url VARCHAR(512) NULL COMMENT '登录页背景素材地址',
  theme_color VARCHAR(32) NULL COMMENT '预设主题色 key（BLUE/TEAL/ORANGE/RED）',
  copyright VARCHAR(255) NULL COMMENT '版权与备案信息（登录页底部）',
  updated_by BIGINT NULL COMMENT '最近维护人 sys_user.id',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 「机构设置」菜单（plt_menu 34，权限码 tenant:brand:edit，平台库 V21 新增）授予内置租户管理员角色。
-- 三处互斥不重复（与 base V9 同款）：老库走本脚本；全新租户库本脚本先于种子 V100（角色未建）执行、
-- 插 0 行，由种子 V105 补授；生产（无种子）由开通编排 ensureAdmin 授予。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 34 AS menu_id
) m ON m.code = r.code
WHERE r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
