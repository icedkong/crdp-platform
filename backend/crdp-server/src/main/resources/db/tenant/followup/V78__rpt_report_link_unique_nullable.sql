-- G1（M3 审查）/ E16-T13、A-ATT-020：同一报告单同一位置唯一——数据库层真正挡住。
-- V63 的 uk_rpt_report_link 含可空列（link_record_id / anchor_type / anchor_key），MySQL 里
-- NULL 互不相等：同一报告单的两条「整条随访记录」（锚点全 NULL）或两条基线分类
-- （link_record_id NULL）可以同时插入，服务层只对单次请求去重（docs/reviews/m3-code-review.md G1）。
-- 本迁移不改 V63（已执行），改为：
--   1) 先清存量重复行（保留最早一条，即 id 最小行——与服务层「先挂的在前」一致）；
--   2) 加三个生成列把 NULL 换成固定占位（数值 0 / 空串），再对生成列建唯一索引——
--      占位值参与比较后 NULL 语义即「相等」，数据库层不再放行重复位置。
-- 生成列用 VIRTUAL：不落盘、加列不动表数据；唯一索引在生成列上与普通列同语义。

-- 1) 存量去重（保留 id 最小 = 最早挂上的位置行；正库无重复时零行删除）
DELETE l1 FROM rpt_report_link l1
JOIN rpt_report_link l2
  ON l1.report_id = l2.report_id
 AND l1.link_type = l2.link_type
 AND IFNULL(l1.link_record_id, 0) = IFNULL(l2.link_record_id, 0)
 AND IFNULL(l1.anchor_type, '') = IFNULL(l2.anchor_type, '')
 AND IFNULL(l1.anchor_key, '') = IFNULL(l2.anchor_key, '')
 AND l1.id > l2.id;

-- 2) 空值占位生成列 + 唯一索引（服务层请求内去重逻辑保留，双保险）
ALTER TABLE rpt_report_link
  ADD COLUMN link_record_id_nn BIGINT GENERATED ALWAYS AS (IFNULL(link_record_id, 0)) VIRTUAL,
  ADD COLUMN anchor_type_nn VARCHAR(16) GENERATED ALWAYS AS (IFNULL(anchor_type, '')) VIRTUAL,
  ADD COLUMN anchor_key_nn VARCHAR(64) GENERATED ALWAYS AS (IFNULL(anchor_key, '')) VIRTUAL,
  ADD UNIQUE KEY uk_rpt_report_link_nn
    (report_id, link_type, link_record_id_nn, anchor_type_nn, anchor_key_nn);
