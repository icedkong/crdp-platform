-- E20-T05 / PLT-VIEW-014、015、016 种子：V62 登记的按钮对 PLATFORM_ADMIN 补授（幂等）。
--   149 复核高敏变更（platform:view:rule:review）——高敏规则变更须由发起人以外的另一名平台管理员
--   复核通过后生效（已确认），默认仅平台管理员持有复核入口（与 148 维护视角规则同口径，V104 职责）。
--   1293 查看视角会话记录（platform:view:session:view）——审计中心「视角会话」列表（PLT-VIEW-016
--   仅平台侧可见；医院端无入口）。
-- 全新库：主迁移 V62 先执行（此时 PLATFORM_ADMIN 由 V100 创建于其后，命中 0 行），这里补授；
-- 老库：直接命中补授。生产不含种子，初始化时按同清单授予（与 V107/V109 同口径）。

INSERT IGNORE INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (149, 1293);
