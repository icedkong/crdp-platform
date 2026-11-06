-- E20-T01 / PLT-VIEW-001、005：预置角色的视角权限码补授（幂等）。
--   platform:view:enter（143）——四个预置角色的默认视角权限「可进入」均为是（platform-spec §8 预置表，
--   种子 V104；平台账号后续可在角色页调整）；platform:view:grant:edit（144）——配置账号负责租户，
--   仅平台管理员。全新库主迁移 V50 先于本种子执行（PLATFORM_ADMIN 由 V100 创建、IMPLEMENTATION /
--   OPERATIONS / SUPPORT 由 V104 创建），INSERT IGNORE 保证老库与全新库补授结果一致；生产不含种子，
--   控制面初始授予由平台初始化时登记（与 V104 同口径）。

INSERT IGNORE INTO plt_role_menu (role_id, menu_id)
SELECT r.id, 143 FROM plt_role r
WHERE r.code IN ('PLATFORM_ADMIN', 'IMPLEMENTATION', 'OPERATIONS', 'SUPPORT') AND r.built_in = 1;

INSERT IGNORE INTO plt_role_menu (role_id, menu_id)
SELECT r.id, 144 FROM plt_role r
WHERE r.code = 'PLATFORM_ADMIN' AND r.built_in = 1;
