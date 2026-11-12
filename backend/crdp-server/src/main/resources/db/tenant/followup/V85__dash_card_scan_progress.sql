-- E21-T15 / C-DASH-007（补录进度卡片目录行；stats.md §3「E21 的 C 卡片随各自任务追加目录行」）：
--   * scan_progress 补录进度：待录入 / 已电子化扫描件的数量与比例 + 补录随访记录条数，
--     可按科室病区（页面级 wardId）、上传人、文档年份筛选（docs/api/stats.md §2）；
--   * default_visible=1：卡片是否可用仍受 feature_code=scan_progress 约束（cfg_feature 种子
--     V100 默认 0——医院 A / B 关闭即整卡不出现，医院 C 上线随 E23 打开，AGENTS §5.7），
--     权限沿用 followup:dashboard:view（OQ-19），数据范围由卡片 Mapper 的 @DataScope 出行；
--   * default_order=80：排在完整度两卡（70 / 71）之后，与 pages.md §2 医院 C 默认顺序
--     「…→ 补录进度」一致（device_dist / eri_list / abnormal_records 随 E21-T19 追加）。
INSERT INTO dash_card
  (card_code, title, width, default_visible, default_order, permission_code, feature_code, allow_user_hide, status)
VALUES
  ('scan_progress', '补录进度', 'FULL', 1, 80, 'followup:dashboard:view', 'scan_progress', 1, 'ENABLED');
