-- E16-T15 / A-ATT-021、C-SCAN-021：模板调整后的位置退回作业（docs/api/report.md §2）。
--   rpt_report.location_adjusted 报告单级「原位置已调整」标记：退回作业（rpt-location-fallback，
--   crdp-server 每日 00:25 + 启动各一次）对某报告单发生退回（随访分组 → 整条记录）或移除
--   （基线分类已不在当前发布版 / 随访记录不存在）时置 1，患者内清单行带出
--   （rpt_report_link.adjusted 为位置级标记，V63 已有、由本作业置位）；用户经修改位置
--   add 重新指定后清 0（E16-T13 接口顺带维护）。因此失去全部位置的报告单自然进入
--   「未指定位置」组（A-ATT-020），退回与移除逐报告单写 RPT_LOCATION_FALLBACK 操作日志。

ALTER TABLE rpt_report
  ADD COLUMN location_adjusted TINYINT NOT NULL DEFAULT 0
  COMMENT '模板调整退回标记（E16-T15 作业置 1；修改位置 add 重新指定后清 0）'
  AFTER digitize_status;
