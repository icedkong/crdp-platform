-- OQ-11 定论 B（2026-11-01 用户确认）：组织架构菜单权限码对齐规格权限点表
-- （platform-spec.md：tenant:org:view / tenant:org:edit），tenant:dept:view → tenant:org:view。
-- README「权限码创建后不改」的经批准例外：该码当前零角色授权（plt_role_menu 按菜单 id 关联，
-- 不随码变化），组织架构接口 / 页面 / 角色授权均未实现（E06+），属最低成本改名窗口。
-- 放在 seed 目录 V103 的原因：种子 V100 与主迁移同历史按版本排序，V1x 主迁移在全新库上
-- 先于 V100 执行、UPDATE 会扑空；V103 保证全新库在 V100 插入后改名。无种子的环境（prod）
-- 没有菜单 33，本 UPDATE 为无害空操作。

UPDATE plt_menu SET perm_code = 'tenant:org:view'
WHERE id = 33 AND perm_code = 'tenant:dept:view';
