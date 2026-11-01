-- E05-T07 / PLT-SCOPE-003：授权范围模板的默认项种子（followup 模块医院端启用菜单中非敏感的全集）。
-- 为什么放种子目录：全新 local/dev 库按版本号顺序执行，V18 先于种子 V100（plt_menu 的菜单行
-- 由 V100 种子插入），V18 里的同款 INSERT…SELECT 命中 0 行；本脚本在 V100 建好菜单之后补种，
-- 与租户种子 V102（补授重置按钮）同理。已按乱序补过 V18 的老库不重复插入（INSERT IGNORE）。
-- 生产不含种子：模块模板由平台在「授权范围模板」页维护（PLT-SCOPE-003），清单同步新增的
-- 菜单项不在模板内即默认不进入可分配范围，须平台确认后对租户生效。

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.side = 'HOSPITAL' AND m.status = 'ENABLED' AND m.module_code = 'followup'
  AND (m.perm_code IS NULL OR m.perm_code NOT IN (SELECT perm_code FROM plt_sensitive_perm));
