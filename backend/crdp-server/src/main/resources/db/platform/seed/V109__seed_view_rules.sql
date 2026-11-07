-- E20-T02 / PLT-VIEW-008：系统初始默认视角规则（platform-spec §8「关键字段 · 默认视角规则」，
-- 已确认：敏感字段脱敏、原件附件隐藏、RCT 设盲字段设盲、禁止导出；平台管理员可调整——
-- 默认规则就是普通规则行，可在「视角规则」页修改、停用）。
-- 布局口径（OQ-99）：同层命中按「更严」合并是字面规则；为让「平台管理员业务数据可编辑」的
-- 预置（已确认）不被同层只读规则吞掉，followup 的只读默认只绑「实施人员 + 客服支持」，
-- 平台管理员经模块层可编辑规则（模块 > 全局默认）生效，其余角色回落全局默认只读。
-- 幂等：code 唯一（INSERT IGNORE），老库与全新库（主迁移 V56 先于本种子执行）补种结果一致；
-- 生产不含种子，初始默认由平台初始化时按本清单登记（与 V104 同口径）。

INSERT IGNORE INTO plt_view_rule
  (code, name, layer, tenant_id, module_code, resource, field, visibility, mask_rule, operation, allow_export, status) VALUES
  ('VR-GLOBAL-DEFAULT', '全局默认：可见、只读、禁止导出', 'GLOBAL', NULL, NULL, NULL, NULL, 'PLAIN', NULL, 'READONLY', 0, 'ACTIVE'),
  ('VR-BASE-CONFIG-RO', '配置类默认：可见、只读', 'MODULE', NULL, 'base', NULL, NULL, 'PLAIN', NULL, 'READONLY', 0, 'ACTIVE'),
  ('VR-FOLLOWUP-RO', '业务数据默认：可见、只读（实施、客服）', 'MODULE', NULL, 'followup', NULL, NULL, 'PLAIN', NULL, 'READONLY', 0, 'ACTIVE'),
  ('VR-FOLLOWUP-ADMIN-EDIT', '平台管理员：业务数据可编辑', 'MODULE', NULL, 'followup', NULL, NULL, 'PLAIN', NULL, 'EDITABLE', 0, 'ACTIVE'),
  ('VR-FIELD-PATIENT-NAME', '敏感字段脱敏：患者姓名', 'FIELD', NULL, 'followup', 'patient', 'name', 'MASKED', 'name', 'READONLY', 0, 'ACTIVE'),
  ('VR-FIELD-PATIENT-IDCARD', '敏感字段脱敏：患者证件号', 'FIELD', NULL, 'followup', 'patient', 'idCard', 'MASKED', 'idCard', 'READONLY', 0, 'ACTIVE'),
  ('VR-FIELD-PATIENT-PHONE', '敏感字段脱敏：患者手机号', 'FIELD', NULL, 'followup', 'patient', 'phone', 'MASKED', 'phone', 'READONLY', 0, 'ACTIVE'),
  ('VR-FIELD-PATIENT-MRN', '敏感字段脱敏：患者病案号（病历号）', 'FIELD', NULL, 'followup', 'patient', 'medicalRecordNo', 'MASKED', 'medicalRecordNo', 'READONLY', 0, 'ACTIVE'),
  ('VR-FIELD-PATIENT-ADDRESS', '敏感字段脱敏：患者住址', 'FIELD', NULL, 'followup', 'patient', 'address', 'MASKED', 'address', 'READONLY', 0, 'ACTIVE'),
  ('VR-FIELD-USER-PHONE', '配置类脱敏：用户手机号', 'FIELD', NULL, 'base', 'user', 'phone', 'MASKED', 'phone', 'READONLY', 0, 'ACTIVE'),
  ('VR-FIELD-USER-IDCARD', '配置类脱敏：用户证件号', 'FIELD', NULL, 'base', 'user', 'idCard', 'MASKED', 'idCard', 'READONLY', 0, 'ACTIVE'),
  ('VR-FIELD-REPORT-ORIGINAL', '原件与附件隐藏：报告单与电子化资料', 'FIELD', NULL, 'followup', 'report', NULL, 'HIDDEN', NULL, 'READONLY', 0, 'ACTIVE'),
  ('VR-FIELD-CONTACT-RECORDING', '通话录音隐藏', 'FIELD', NULL, 'followup', 'contact', 'recording', 'HIDDEN', NULL, 'READONLY', 0, 'ACTIVE'),
  ('VR-FIELD-RCT-GROUPING', 'RCT 设盲：分组', 'FIELD', NULL, NULL, 'rct', 'grouping', 'BLINDED', NULL, 'READONLY', 0, 'ACTIVE'),
  ('VR-FIELD-RCT-RANDOM-TABLE', 'RCT 设盲：随机表', 'FIELD', NULL, NULL, 'rct', 'randomTable', 'BLINDED', NULL, 'READONLY', 0, 'ACTIVE'),
  ('VR-FIELD-RCT-UNBLINDING', 'RCT 设盲：揭盲信息', 'FIELD', NULL, NULL, 'rct', 'unblinding', 'BLINDED', NULL, 'READONLY', 0, 'ACTIVE');

-- 业务数据只读默认只绑实施 / 客服（平台管理员走可编辑规则，避免同层更严合并吞掉预置，OQ-99）。
INSERT IGNORE INTO plt_view_rule_role (rule_id, role_id)
SELECT rur.id, r.id FROM plt_view_rule rur, plt_role r
WHERE rur.code = 'VR-FOLLOWUP-RO' AND r.code IN ('IMPLEMENTATION', 'SUPPORT');

INSERT IGNORE INTO plt_view_rule_role (rule_id, role_id)
SELECT rur.id, r.id FROM plt_view_rule rur, plt_role r
WHERE rur.code = 'VR-FOLLOWUP-ADMIN-EDIT' AND r.code = 'PLATFORM_ADMIN';

-- 菜单授予：主迁移 V56 在全新库命中 0 行（PLATFORM_ADMIN 由 V100 创建），这里补授（幂等）。
-- 默认仅平台管理员维护视角规则（V104 职责描述；实施 / 运维 / 客服如需查看由平台管理员调整）。
INSERT IGNORE INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (147, 148);
