-- E11-T04 / A-FLD-006、C-FLD-007：跨字段校验规则表 fe_rule（租户库，tech-plan §6「元数据模型」）。
--   通用比较规则 COMPARE：params = {leftField, op(lt|lte), rightField}（字段稳定键），语义「leftField op rightField
--   须成立」，覆盖 A-FLD-006 的全部形态与 C-FLD-007 的频率约束：
--     入院 ≤ 手术 ≤ 出院（两条 lte）｜手术开始 < 结束、进 ICU < 出 ICU（lt，日期时间）
--     死亡日期 ≥ 出院日期（表达为 出院 ≤ 死亡）｜随访事件 ≥ 出院（出院 ≤ 事件，另一条随随访表单）
--     上限频率 > 下限频率（数值 lt）。
--   值按 tech-plan §6 值编码口径比较：数值比大小、日期 YYYY-MM-DD / 日期时间 YYYY-MM-DDTHH:mm 比先后
--   （日期按当日 00:00 归一）；一侧为空或不可比不触发。硬 / 软范围仍在 fe_field（V28），随版本快照固化（E11-T02）。
--   severity：HARD 违反即拦截（提交 / 保存修改 42200，form.md §2）；SOFT 黄色提示可保存、要求确认
--   （A-FLD-003 软范围、C-FLD-007 程控参数建议范围同一语义）。
--   规则不进模板版本快照（form.md §1：FormSchema 的 rules 为 fe_rule 实时读取）；停用（DISABLED）即不生效、不删除。
-- 演示种子（虚构）：A_BASELINE 演示规则「手术日期不能早于出生日期」（入院 / 出院等 A-FLD-006 完整规则链随
--   E23-T01 字段录入配套迁移；C 程控上下限频率规则随 C 字段字典落地）。

CREATE TABLE fe_rule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  form_code VARCHAR(64) NOT NULL COMMENT '所属表单编码（fe_form.code）',
  rule_id VARCHAR(64) NOT NULL COMMENT '规则稳定编码（前后端一致的命中标识，fieldErrors[].rule），表单内唯一，创建后不可改',
  category_key VARCHAR(32) NOT NULL COMMENT '命中提示挂靠的分类锚点键（FormSchema rules[].categoryKey 与 fieldErrors[].categoryKey 同源）',
  target_field VARCHAR(32) NOT NULL COMMENT '提示挂靠字段稳定键（rules[].key / fieldErrors[].key，一般取规则中靠后的字段）',
  severity VARCHAR(8) NOT NULL DEFAULT 'HARD' COMMENT 'HARD 硬校验：违反拦截提交（42200）；SOFT 软校验：黄色提示可保存（C-FLD-007 建议范围语义）',
  rule_type VARCHAR(16) NOT NULL DEFAULT 'COMPARE' COMMENT '规则类型：COMPARE 比较（params.leftField op params.rightField 须成立）',
  params JSON NOT NULL COMMENT 'COMPARE：{leftField, op: lt|lte, rightField}（字段稳定键）；值按值编码口径比较，一侧为空不触发',
  message VARCHAR(200) NOT NULL COMMENT '违反时的提示文案（前后端同源展示）',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序号，小者在前',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED：停用即不生效（不删除）',
  updated_by BIGINT NULL COMMENT '最近维护人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_fe_rule (form_code, rule_id),
  KEY idx_fe_rule_form (form_code, status, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='跨字段校验规则（tech-plan §6 fe_rule，A-FLD-006 / C-FLD-007，E11-T04）';

-- 演示规则（虚构数据）：出生日期 ≤ 手术日期。f003（出生日期）与 f020（手术日期）为 V28 既有演示字段。
INSERT INTO fe_rule
  (form_code, rule_id, category_key, target_field, severity, rule_type, params, message, sort_no) VALUES
  ('A_BASELINE', 'birth_le_surgery', 'surgery', 'f020', 'HARD', 'COMPARE',
   JSON_OBJECT('leftField', 'f003', 'op', 'lte', 'rightField', 'f020'),
   '手术日期不能早于出生日期（示例规则，A-FLD-006 完整规则链随 E23-T01 录入）', 10);
