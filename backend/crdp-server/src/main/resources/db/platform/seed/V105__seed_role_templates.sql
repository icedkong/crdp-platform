-- E06-T05 / PLT-ROLE-005：随访模块业务角色模板种子（platform-spec §1「如随访员、责任医生、研究者、CRC、资料录入员」）。
-- 为什么放种子目录：V22 建表在版本序上先于 V100（菜单行由 V100 种子插入），同款 INSERT…SELECT 会命中 0 行，
-- 与授权范围模板 V102 同理。已按乱序补过 V22 的老库不重复插入（plt_role_template_code 唯一键）。
-- 模板菜单矩阵是暂行口径（OQ-50）：随访业务菜单随 E12+ 里程碑逐步登记，届时由平台按规格在医院侧调整模板；
-- 敏感按钮（明文 / 导出等，plt_sensitive_perm）不进模板。

INSERT IGNORE INTO plt_role_template (module_code, code, name, description, sort_no) VALUES
  ('followup', 'FOLLOWUP_NURSE', '随访员', '执行随访任务、维护患者联系方式（模板，复制后可调整）', 10),
  ('followup', 'FOLLOWUP_DUTY_DOCTOR', '责任医生', '患者医疗责任医生，查看与处理本人负责患者的随访（模板，复制后可调整）', 20),
  ('followup', 'FOLLOWUP_RESEARCHER', '研究者', '临床研究视角查看随访与报告数据（模板，复制后可调整）', 30),
  ('followup', 'FOLLOWUP_CRC', 'CRC', '临床研究协调员，代研究者维护研究资料（模板，复制后可调整）', 40),
  ('followup', 'FOLLOWUP_DATA_CLERK', '资料录入员', '纸质资料补录与报告单归档（模板，复制后可调整）', 50);

-- 菜单授权：随访目录（20）+ 患者档案（21）/ 随访任务（22）/ 报告单（23）。
-- 资料录入员不做随访任务（只补录资料与报告单），其余四个模板全覆盖现有随访菜单。
INSERT IGNORE INTO plt_role_template_menu (template_id, menu_id)
SELECT t.id, m.id
FROM plt_role_template t
JOIN plt_menu m ON m.id IN (20, 21, 22, 23)
WHERE t.module_code = 'followup'
  AND NOT (t.code = 'FOLLOWUP_DATA_CLERK' AND m.id = 22);
