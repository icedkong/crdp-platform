-- E19-T07 / A-DASH-009、C-DASH-010（医护负责患者数，P1）：医院端「统计」页面菜单（pages.md §9）。
--   * 菜单 42（/followup/stats，component followup/stats/index）：挂随访目录（20）下、报告单（23）
--     之后（sort 40）。页签：每年随访率 / 节点随访率 / 数据完整度 / 医护负责患者数（C 的器械分布
--     等页签随 E21）；前三页与首页卡片同一组接口（stats.md §1「一个口径一个查询」），本任务落地
--     医护负责患者数（GET /api/hospital/followup/stats/doctor-workload）。
--   * perm_code 置 NULL：plt_menu.perm_code 全库唯一（uk_plt_menu_perm），followup:dashboard:view
--     已由按钮 234（V59，统计页与首页卡片同码 OQ-19）持有——接口按该码鉴权，菜单行只管路由可见性
--     （V39 患者详情 / V48 基线分节同做法：可见性随角色授予，授予清单与 234 的 V129 / V131 同集）。
--   * 仅桌面 / 平板（pages.md §9）；数据范围由接口侧 @DataScope(resource = patient) 出行。
-- followup 模块医院端菜单须进授权范围模板（V42 / V59 同口径，非敏感项默认可分配）；
--   租户库内置角色的菜单授予在租户库种子 V132（老库补授幂等）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (42, 20, 'HOSPITAL', 'MENU', 'followup', '统计', 'FollowupStats', '/followup/stats', 'followup/stats/index', NULL, NULL, 40, 0, 'ENABLED');

INSERT IGNORE INTO plt_scope_template (module_code, menu_id)
SELECT 'followup', m.id
FROM plt_menu m
WHERE m.id = 42 AND m.status = 'ENABLED';
