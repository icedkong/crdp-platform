-- E17-T05 / A-FU-027（Q41 默认规则全量；hospital-a-spec 第 5 章「录入时对比提示规则（医院已确认 Q41）」）：
-- 预置医院 A 年度随访表单（A_ANNUAL_FU，V38 字段字典）的 Q41 全部默认对比标红规则。
--   V67（E17-T01）已随建表落了两条（ef_drop 射血分数下降、lvdd_rise 左室扩大），本脚本补齐其余 15 条：
--   Q41 表 9 行拆成 17 条单字段规则（一行多字段的：「其他新发事件」5 项、瓣膜返流加重 3 个瓣膜、
--   超出提示范围 3 项各成一条，rule_code 稳定、创建后不可改）。医院 B 沿用 A 全套（第 14 章「对比标红
--   沿用 A-FU-027」）；医院 C 的默认规则随 E21-T20 另配（C_PROGRAM_FU）。
-- 每条可单独开关（status，默认 ENABLED）、调阈值（params 阈值键，键集按 CompareRuleType 校验）；
--   只在录入表单内标红提示，不发通知、不生成待办、不影响提交（A-FU-027 / Q41）。
-- 字段与回落引用全部来自 V38 字段字典：f215 是否死亡、f219/f221/f223/f225/f227/f230/f233 终点事件、
--   f239/f241/f243 返流程度（a_mr_reflux 序数 0–4）、f245 LA、f248 EF、f249 LVDd、f258 心室血栓；
--   基线回落 f102 出院前最后一次EF、f103 出院前最后一次左室舒张末径、f113 出院前有无心室血栓（第 1 年对比基线）。
-- 新发事件口径（E17-T03 求值已锁定）：本次命中（缺省 = 非空且编码 ≠ 0「无」）且上一次该事件为空 / 为无，
--   或事件日期（params.dateField）晚于上一次随访日期；params 只写 dateField，triggerCodes 省略。
-- 由否变是无阈值键（FLIP_TO_YES params = '{}'，MySQL JSON_OBJECT() 零参报错故写字面量）；
--   new_death 无基线回落列（Q41 对比对象为「—」：本次 = 是即标红），new_thrombus 回落基线 f113。
-- INSERT 不写 status / updated_by 列（默认 ENABLED / NULL，与 V67 同款）；(form_code, rule_code) 唯一键
--   保证与 V67 两条不冲突（全新库 V67 先执行、老库本脚本只补 15 条）。

INSERT INTO fu_compare_rule
  (form_code, rule_code, name, category_key, target_field, rule_type, params,
   baseline_form_code, baseline_field, message, sort_no) VALUES
  -- 新发死亡（Q41：本次「是否死亡」= 是；对比对象「—」→ 无基线回落列）
  ('A_ANNUAL_FU', 'new_death', '新发死亡', 'fu_contact', 'f215', 'FLIP_TO_YES', '{}',
   NULL, NULL, '本次「是否死亡」= 是', 15),
  -- 新发再住院（#223 类型 + #224 日期；a_readmit_type 无「无」编码 → 填了即命中）
  ('A_ANNUAL_FU', 'new_readmission', '新发再住院', 'fu_event', 'f223', 'NEW_EVENT',
   JSON_OBJECT('dateField', 'f224'),
   NULL, NULL, '新发再住院：本次填写了再住院类型，且日期晚于上一次随访（或上一次未填）', 25),
  -- 新发再血运重建（#225 再次血运重建 + #226 时间；≠ 无 = 编码 ≠ 0）
  ('A_ANNUAL_FU', 'new_revascularization', '新发再血运重建', 'fu_event', 'f225', 'NEW_EVENT',
   JSON_OBJECT('dateField', 'f226'),
   NULL, NULL, '新发再血运重建：本次再次血运重建 ≠ 无，且日期晚于上一次随访（或上一次为无）', 30),
  -- 其他新发事件（Q41 一行 5 个字段各成一条：#219 / #221 / #227 / #230 / #233）
  ('A_ANNUAL_FU', 'new_postop_mi', '新发术后心肌梗死', 'fu_event', 'f219', 'NEW_EVENT',
   JSON_OBJECT('dateField', 'f220'),
   NULL, NULL, '新发术后心肌梗死：本次为是，且日期晚于上一次随访（或上一次为否）', 35),
  ('A_ANNUAL_FU', 'new_postop_cvd', '新发术后脑血管疾病', 'fu_event', 'f221', 'NEW_EVENT',
   JSON_OBJECT('dateField', 'f222'),
   NULL, NULL, '新发术后脑血管疾病：本次非无，且日期晚于上一次随访（或上一次为无）', 36),
  ('A_ANNUAL_FU', 'new_revalve', '新发再次瓣膜手术', 'fu_event', 'f227', 'NEW_EVENT',
   JSON_OBJECT('dateField', 'f229'),
   NULL, NULL, '新发再次瓣膜手术：本次非无，且日期晚于上一次随访（或上一次为无）', 37),
  ('A_ANNUAL_FU', 'new_arr_reop', '新发因心律失常再次手术', 'fu_event', 'f230', 'NEW_EVENT',
   JSON_OBJECT('dateField', 'f231'),
   NULL, NULL, '新发因心律失常再次手术：本次为是，且日期晚于上一次随访（或上一次为否）', 38),
  ('A_ANNUAL_FU', 'new_postop_bleed', '新发术后出血', 'fu_event', 'f233', 'NEW_EVENT',
   JSON_OBJECT('dateField', 'f234'),
   NULL, NULL, '新发术后出血：本次非无，且日期晚于上一次随访（或上一次为无）', 39),
  -- 瓣膜返流加重（#239 / #241 / #243 三个瓣膜；a_mr_reflux 序数：升高 ≥ 2 级，或达到中量(3) / 大量(4)）
  ('A_ANNUAL_FU', 'mr_reflux_worsen', '二尖瓣返流加重', 'fu_echo', 'f239', 'ORDINAL_RISE',
   JSON_OBJECT('grades', 2, 'reachCodes', JSON_ARRAY('3', '4')),
   NULL, NULL, '返流程度比上次升高 ≥ 2 级，或达到中量 / 大量', 50),
  ('A_ANNUAL_FU', 'ar_reflux_worsen', '主动脉瓣返流加重', 'fu_echo', 'f241', 'ORDINAL_RISE',
   JSON_OBJECT('grades', 2, 'reachCodes', JSON_ARRAY('3', '4')),
   NULL, NULL, '返流程度比上次升高 ≥ 2 级，或达到中量 / 大量', 51),
  ('A_ANNUAL_FU', 'tr_reflux_worsen', '三尖瓣返流加重', 'fu_echo', 'f243', 'ORDINAL_RISE',
   JSON_OBJECT('grades', 2, 'reachCodes', JSON_ARRAY('3', '4')),
   NULL, NULL, '返流程度比上次升高 ≥ 2 级，或达到中量 / 大量', 52),
  -- 新发心室血栓（#258 由否变是；无上一次随访时回落基线 #113 出院前有无心室血栓）
  ('A_ANNUAL_FU', 'new_thrombus', '新发心室血栓', 'fu_echo', 'f258', 'FLIP_TO_YES', '{}',
   'A_BASELINE', 'f113', '心室血栓由否变为是（对比上一次随访或出院前）', 60),
  -- 超出提示范围（Q41 默认三项：EF ＜ 40%、LVDd ＞ 60 mm、LA ＞ 45 mm；与字段字典取值校验范围分开配置）
  ('A_ANNUAL_FU', 'ef_below_40', 'EF 低于 40', 'fu_echo', 'f248', 'OUT_OF_RANGE',
   JSON_OBJECT('op', 'LT', 'value', 40),
   NULL, NULL, 'EF ＜ 40%（超出提示范围）', 70),
  ('A_ANNUAL_FU', 'lvdd_above_60', 'LVDd 大于 60', 'fu_echo', 'f249', 'OUT_OF_RANGE',
   JSON_OBJECT('op', 'GT', 'value', 60),
   NULL, NULL, 'LVDd ＞ 60 mm（超出提示范围）', 71),
  ('A_ANNUAL_FU', 'la_above_45', 'LA 大于 45', 'fu_echo', 'f245', 'OUT_OF_RANGE',
   JSON_OBJECT('op', 'GT', 'value', 45),
   NULL, NULL, 'LA ＞ 45 mm（超出提示范围）', 72);
