-- E21-T09 / C-FU-018：随访序号自动编号与重排——fu_record.sequence_no 列 + 功能开关 record_sequence_no。
--
-- 编号口径（hospital-c-spec §5 派生字段表「随访序号：同一患者按随访日期升序编号，从 1 起；补录后自动重排」）：
--   * 同一患者已提交记录按有效随访日期（visit_date ?? 表单随访日期字段 ?? 任务计划日期，与 E21-T11
--     派生预演同一口径）升序编 1..N，同日按 id 小者为先；草稿不占号、软删行不在册（deleted = 0）。
--   * 重排触发（FollowupSequenceService，调用方事务内）：①记录提交（含补录插入——验收「补录插入后序号
--     自动重排」，插入点之后的记录序号整体 +1）；②修改已提交记录（表单随访日期字段可能改早 / 改晚）；
--     ③删除记录（软删后排在后面的记录序号回退）。
--   * 内部记录号不变（C-FU-018 / followup.md §3）：重排只写派生列 sequence_no，fu_record.id、患者随访编号
--     与记录业务数据不动，也不动 version / updated_by（系统重算不是用户编辑，与 highlight V69 同款）。
--   * 重排记入变更历史（Q13 验收「变更历史中有重排记录」）：序号变化的行逐条写 fe_field_history
--     （record_type = FU_RECORD、field_key = 'sequence_no' 虚拟稳定键——随访序号是派生系统字段、不在
--     表单模板字段集内，键与 f0xx 稳定键不冲突；source = SYSTEM_RECALC）；审计由触发操作自身的
--     RECORD_SUBMIT / RECORD_UPDATE / RECORD_DELETE 条目覆盖，重排不单独写审计。
--
-- 存量行不回填（与 V69 highlight 同口径：老库行 sequence_no 为 NULL，下一次任一触发点覆盖到即补齐；
-- 首编旧值为 null、同样留痕）。读侧（记录详情 sequenceNo）开关开启的已提交记录优先落库值、缺省回落
-- E21-T11 派生预演，草稿与关闭租户维持派生预演不变。
--
-- 租户差异（AGENTS §5.7）：功能开关 record_sequence_no，本脚本只落关闭行（默认 0 = A / B 行为——
-- 不编号、不重排、读侧维持派生），医院 C 随租户开通配置（E23-T23）改为 1。
-- 全新库排序：followup V95 先于种子 V100 执行，二者插入的 feature_code 不重叠
-- （V100 不含 record_sequence_no，脚本只新增不改旧），uk_cfg_feature 不冲突（与 V92 同口径）。
ALTER TABLE fu_record
  ADD COLUMN sequence_no INT NULL
    COMMENT '随访序号（E21-T09 / C-FU-018）：同一患者已提交记录按有效随访日期升序 1..N，同日按 id 小者为先；提交 / 修改 / 删除后自动重排并写 fe_field_history（field_key = sequence_no）；开关 record_sequence_no 关闭的租户（A / B）恒为 NULL'
    AFTER implant_id;

INSERT INTO cfg_feature (feature_code, enabled) VALUES ('record_sequence_no', 0);
