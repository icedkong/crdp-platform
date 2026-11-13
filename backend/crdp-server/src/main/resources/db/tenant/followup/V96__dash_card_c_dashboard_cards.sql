-- E21-T19 / C-DASH-002、C-DASH-005、C-DASH-006（三张 C 首页卡片进卡片目录；stats.md §3「E21 的 C 卡片随各自任务
-- 追加目录行」、pages.md §2 医院 C 默认顺序「指标卡 → 任务看板 → 器械类型分布与随访率并列 → 接近 ERI 列表与
-- 异常标红记录并列 → 补录进度」）：
--   * device_dist 器械类型分布（C-DASH-002）：在随访患者按在用植入记录的起搏器类型统计、可按品牌拆分；
--     default_order=45 排在任务区（task_table 40）之后、随访率（yearly_rate 50）之前；feature_code=implant
--     （无植入数据的租户卡片无意义，开关关闭即整卡不出现，AGENTS §5.7；目录「并列」的宽度归并见 OQ-146②）；
--   * eri_list 接近 ERI 器械列表（C-DASH-005）：最近一次 ERI 状态为接近ERI或已达ERI(EOI)、或预计电池寿命
--     ≤ 1 年的患者，含器械、最近随访日期、下次计划日期；feature_code=eri_cycle（stats.md §3；判定与计划切换
--     SW-7 / SW-8 同一谓词 plan.domain.EriEval）；default_order=62 排在节点随访率（60）之后；
--   * abnormal_records 异常标红记录（C-DASH-006）：近 N 天（默认 30）提交的含标红参数（fu_record.highlight，
--     E17-T04）的随访记录，显示标红项目与变化率，只做查看；feature_code=implant（A / B 不显示该 C 卡——
--     compare_highlight 是 A 也开的对比能力，不能作为本卡的租户闸门）；default_order=63 与 eri_list 相邻。
-- 三卡 default_visible=1：医院 C 初始化（implant / eri_cycle 随 E23 打开）后即按目录默认顺序显示；医院 A / B
-- 因开关关闭不出卡，不影响 A 默认布局。权限沿用 followup:dashboard:view（OQ-19），数据范围由各卡片 Mapper 的
-- @DataScope 出行；数字与指标卡（接近 ERI 器械数、近 30 天含标红参数的记录数）同一条件树（stats.md §1）。
INSERT INTO dash_card
  (card_code, title, width, default_visible, default_order, permission_code, feature_code, allow_user_hide, status)
VALUES
  ('device_dist', '器械类型分布', 'FULL', 1, 45, 'followup:dashboard:view', 'implant', 1, 'ENABLED'),
  ('eri_list', '接近 ERI 器械列表', 'FULL', 1, 62, 'followup:dashboard:view', 'eri_cycle', 1, 'ENABLED'),
  ('abnormal_records', '异常标红记录', 'FULL', 1, 63, 'followup:dashboard:view', 'implant', 1, 'ENABLED');
