-- 系统公告（E05-T11 / PLT-CFG-009）：平台向全部或指定租户发布公告。
--   plt_announcement —— 公告主体：scope=ALL 面向全部租户（不落关联行）、TENANTS 只面向
--   plt_announcement_tenant 登记的租户（未知租户发布侧 40400）；status 只有 PUBLISHED
--   （发布即生效，无草稿与排期，P2 范围）与 REVOKED（撤下，医院端即刻不可见；发布后不可
--   编辑，更正走撤下 + 重新发布）。published_by_name 存「姓名（账号）」快照（账号后续改名
--   不回写）。发布 / 撤下写平台审计（ANNOUNCEMENT_PUBLISH / ANNOUNCEMENT_REVOKE）。
--   医院端读取（GET /api/hospital/announcements，登录即可）按本院过滤，只读平台库。
-- 菜单 150「系统公告」（/platform/notice，pages.md §11 系统配置行；通知渠道与模板
--   PLT-CFG-005/006 随后续任务并入同一菜单）：perm_code=platform:notice:edit
--   （docs/api/platform.md §7 资源表口径，plt_menu.perm_code 全库唯一，本任务先注册）。
--   授予 PLATFORM_ADMIN 与 V17 同款做法：主迁移 INSERT..SELECT（全新库此时还没有
--   PLATFORM_ADMIN 行，命中 0 行无害；种子 V100 对全部 PLATFORM 侧菜单补授）。

CREATE TABLE plt_announcement (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  title VARCHAR(128) NOT NULL COMMENT '标题',
  content TEXT NOT NULL COMMENT '正文',
  scope VARCHAR(16) NOT NULL COMMENT '发布范围：ALL 全部租户 / TENANTS 指定租户',
  status VARCHAR(16) NOT NULL COMMENT 'PUBLISHED 发布中 / REVOKED 已撤下',
  published_at DATETIME(3) NOT NULL COMMENT '发布时间（发布即生效）',
  published_by BIGINT NULL COMMENT '发布人（plt_account.id）',
  published_by_name VARCHAR(160) NULL COMMENT '发布人快照「姓名（账号）」',
  revoked_at DATETIME(3) NULL COMMENT '撤下时间',
  revoked_by BIGINT NULL COMMENT '撤下人（plt_account.id）',
  KEY idx_plt_announcement_published (status, published_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统公告（PLT-CFG-009）';

CREATE TABLE plt_announcement_tenant (
  announcement_id BIGINT NOT NULL COMMENT '公告 ID（plt_announcement.id）',
  tenant_id BIGINT NOT NULL COMMENT '租户 ID（plt_tenant.id）',
  PRIMARY KEY (announcement_id, tenant_id),
  KEY idx_plt_announcement_tenant (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '系统公告定向租户（scope=TENANTS）';

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status, source) VALUES
  (150, 110, 'PLATFORM', 'MENU', 'base', '系统公告', 'PlatformNotice', '/platform/notice', 'platform/notice/index', 'platform:notice:edit', 'SoundOutlined', 85, 0, 'ENABLED', 'MANIFEST');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id = 150;
