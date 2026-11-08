-- E19-T02 / A-DASH-001、A-DASH-002、C-DASH-001（pages.md §1、§2）：医院端首页菜单（种子 V100 菜单 10）
-- 标题「工作台」按线框定稿改为「首页」——首页承载指标卡、趋势图与后续任务 / 统计卡片
-- （权限码 followup:dashboard:view 见平台库 V59 按钮 234；平台端菜单 100 不动）。
-- 放种子系列（V100+）：全新库主迁移 V1x–V5x 先于种子执行，V59 段的 UPDATE 在种子插行前跑会空匹配
-- （AGENTS.md「平台库主迁移 V1x、种子 V100+」的排序口径，V108 同款），V110 保证在 V100 之后执行、新旧库都生效。

UPDATE plt_menu
SET title = '首页'
WHERE id = 10 AND side = 'HOSPITAL' AND title = '工作台';
