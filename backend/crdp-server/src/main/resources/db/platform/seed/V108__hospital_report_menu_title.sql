-- E16-T05 / A-ATT-016、C-SCAN-014：全院报告单列表（/followup/report，pages.md §6.4、§0.1）。
-- 菜单 23（种子 V100）标题「报告单」按线框定稿改为「报告单与资料」——该菜单承载报告单 / 资料
-- 的全院列表与检索（患者档案内页签随 E16-T02）。权限码 followup:report:view 不变（OQ-12 定论，
-- 创建后不改名 OQ-11）；A_WARD_DOCTOR / A_FOLLOWUP_NURSE / A_DIRECTOR 的菜单授予见租户库 V123。
-- 放种子系列（V100+）：全新库主迁移 V1x–V5x 先于种子执行，V5x 段的 UPDATE 在种子插行前跑会空匹配
-- （AGENTS.md「平台库主迁移 V1x、种子 V100+」的排序口径），V108 保证在 V100 之后执行、新旧库都生效。

UPDATE plt_menu
SET title = '报告单与资料'
WHERE id = 23 AND side = 'HOSPITAL' AND title = '报告单';
