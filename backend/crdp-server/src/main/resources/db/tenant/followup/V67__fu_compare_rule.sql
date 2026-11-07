-- E17-T01 / A-FU-027（Q41 默认规则见 hospital-a-spec 第 5 章；C-FU-011 随 E21）：
-- 录入时对比标红规则表 fu_compare_rule（租户库，tech-plan §11「录入时对比标红规则引擎」）。
--   规则是租户配置：每条可开关（status）、调阈值（params 里的阈值键），只在录入表单内标红提示，
--   不发通知、不生成待办、不影响提交（A-FU-027）；规则条目本身由预置迁移落库（A 的 Q41 全量随
--   E17-T05、C 的默认随 E21-T20），配置页只做开关与阈值，不提供增删（docs/api/followup.md §4）。
--   对比对象与求值（上一次已提交年度记录、无上一次时回落基线字段）随 E17-T02 / E17-T03 落地；
--   结果持久化（fu_record.highlight）与规则变更重算随 E17-T04。
--
-- rule_type 与 params 形状（tech-plan §11 规则类型表，7 类；对比字段恒为 target_field，
--   基线回落字段独立成列便于关联字段字典取显示名）：
--   DIFF          差值下降 / 上升  {direction: DROP|RISE, threshold: 差值阈值(>0)}
--                                   A：EF 下降 ≥ 10 个百分点（f248，基线 f102）；LVDd 增加 ≥ 5 mm（f249，基线 f103）
--   CHANGE_PCT    变化率           {direction: DROP|RISE|ANY, percent: 百分比阈值(>0)}
--                                   C：起搏阈值升高 > 50%；阻抗升高或降低 > 50%（ANY）；感知下降 > 50%
--   ORDINAL_RISE  序数升级         {grades: 升级级数(1-5整数), reachCodes?: 达到即标红的选项编码}
--                                   A：返流程度升高 ≥ 2 级，或达到中量 / 大量（f239/f241/f243，fe_option.ordinal 序数）
--   NEW_EVENT     新发事件         {dateField?: 事件日期字段, triggerCodes?: 命中编码}
--                                   本次 target_field 命中（缺省 = 值非空且非「无」）且日期（有 dateField 时）
--                                   晚于上一次随访日期（或上一次未填）；A：再住院 / 再血运重建 / 心梗等
--   FLIP_TO_YES   由否变是         {}（无阈值键，只可开关）
--                                   A：新发心室血栓（f258，基线 f113）、本次「是否死亡」= 是
--   OUT_OF_RANGE  超出提示范围     {op: LT|LTE|GT|GTE, value: 边界值}
--                                   A 默认：EF < 40、LVDd > 60、LA > 45（与字段字典取值校验范围分开配置）
--   VALUE_HIT     取值命中         {codes?: 命中选项编码[]} 或 {op: LT|LTE|GT|GTE, value}（二选一）
--                                   C：ERI 状态非正常；预计电池寿命 ≤ 1 年
--   params 键集按类型校验（未知键 / 缺必填键 / 越界值 → 40001），阈值范围：threshold/percent > 0 且
--   ≤ 9999（两位小数内）、grades 为 1–5 整数、value 在 ±10000 内。
--
-- 功能开关 compare_highlight（cfg_feature，种子 V100 已落 A 默认开）：关闭时配置接口 40301、
--   前端隐藏入口（AGENTS.md 规则 7）；无行视为默认开（生产库无种子，tech-plan §10 A 默认开）。
-- 「对比规则」菜单（plt_menu 40，权限码 followup:abnormal:config，平台库 V57 新增）授予内置
--   租户管理员角色（A 规格无单独配置码，按 OQ-16 给 A 也授 followup:abnormal:config）：老库走本脚本；
--   全新库本脚本先于种子 V100 执行、插 0 行，由种子 V127 兜底；生产由开通编排 ensureAdmin 授予。
-- 配置模板登记（A-FLD-014 复制清单含「录入时对比提示规则」）：ConfigTableRegistry / ConfigImportSpecs
--   按 (form_code, rule_code) 自然键合并复制。

CREATE TABLE fu_compare_rule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  form_code VARCHAR(64) NOT NULL COMMENT '所属表单编码（fe_form.code，如 A_ANNUAL_FU、C_PROGRAM_FU）',
  rule_code VARCHAR(64) NOT NULL COMMENT '规则稳定编码（前后端一致的命中标识），表单内唯一，创建后不可改',
  name VARCHAR(100) NOT NULL COMMENT '规则名（配置页显示，如「射血分数下降」）',
  category_key VARCHAR(32) NOT NULL COMMENT '标红挂靠的分类锚点键（fe_form.categories[].key，如 fu_echo）',
  target_field VARCHAR(32) NOT NULL COMMENT '对比与标红挂靠的字段稳定键（fe_field.field_key，如 f248）',
  rule_type VARCHAR(16) NOT NULL COMMENT '规则类型：DIFF/CHANGE_PCT/ORDINAL_RISE/NEW_EVENT/FLIP_TO_YES/OUT_OF_RANGE/VALUE_HIT（见迁移注释）',
  params JSON NOT NULL COMMENT '阈值参数（按 rule_type 校验键集与取值，见迁移注释；PUT 只改这里的阈值键与开关）',
  baseline_form_code VARCHAR(64) NULL COMMENT '无上一次随访时回落对比的基线表单编码（fe_form.code，如 A_BASELINE）；DIFF/FLIP_TO_YES 使用',
  baseline_field VARCHAR(32) NULL COMMENT '回落对比的基线字段稳定键（如 f102 出院前最后一次EF）；与 baseline_form_code 成对',
  message VARCHAR(200) NOT NULL COMMENT '标红提示文案（前后端同源展示，如「EF 比上次下降 ≥ 10 个百分点」）',
  sort_no INT NOT NULL DEFAULT 0 COMMENT '排序号，小者在前',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED：每条规则可开关，停用即不参与标红（不删除）',
  updated_by BIGINT NULL COMMENT '最近维护人 sys_user.id',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_fu_compare_rule (form_code, rule_code),
  KEY idx_fu_compare_rule_form (form_code, status, sort_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='录入时对比标红规则（tech-plan §11，A-FU-027 / C-FU-011，E17-T01）';

-- 演示种子（虚构数据）：两条 Q41 默认规则（射血分数下降、左室扩大），默认值与医院确认口径一致；
-- Q41 全量（新发事件、返流加重、心室血栓、超出提示范围等）随 E17-T05 预置迁移落库。
-- f248 EF 射血分数 / f249 LVDd 舒张末径（A_ANNUAL_FU）与 f102 / f103（A_BASELINE）为 V38 既有字段。
INSERT INTO fu_compare_rule
  (form_code, rule_code, name, category_key, target_field, rule_type, params,
   baseline_form_code, baseline_field, message, sort_no) VALUES
  ('A_ANNUAL_FU', 'ef_drop', '射血分数下降', 'fu_echo', 'f248', 'DIFF',
   JSON_OBJECT('direction', 'DROP', 'threshold', 10),
   'A_BASELINE', 'f102', 'EF 比上次（或出院前）下降 ≥ 10 个百分点', 10),
  ('A_ANNUAL_FU', 'lvdd_rise', '左室扩大', 'fu_echo', 'f249', 'DIFF',
   JSON_OBJECT('direction', 'RISE', 'threshold', 5),
   'A_BASELINE', 'f103', 'LVDd 比上次（或出院前）增加 ≥ 5 mm', 20);

-- 「对比规则」菜单（plt_menu 40，权限码 followup:abnormal:config，平台库 V57 新增）授予内置
-- 租户管理员角色（TENANT_ADMIN；与报告单设置 V62 同口径）。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 40
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 40
  );
