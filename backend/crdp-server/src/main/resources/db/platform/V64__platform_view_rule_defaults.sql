-- S4（M3 审查）/ E20-T02 / PLT-VIEW-008、PLT-VIEW-010、PLT-VIEW-011：系统初始默认视角规则
-- 进生产库。此前 16 条默认规则只在本机种子 V109（local / dev / demo 剖面执行）里，
-- 生产库没有任何一处登记或开通代码插入它们——没有规则时解析回落「可见、只读、禁止导出」，
-- 报告单原件隐藏、通话录音隐藏、RCT 设盲全部缺失。
-- 本脚本与 V109 同内容、同幂等口径（code 唯一，INSERT IGNORE），放在主迁移目录：
-- 生产（不含种子）与本地（种子 V109 已插入，此处全部 IGNORE）补种结果一致；不改 V109。
-- 角色绑定 / 菜单授予经 INSERT ... SELECT 幂等补授：生产 plt_role 由平台初始化登记，
-- 若 V64 执行时角色尚不存在则命中 0 行（规则行不受影响；绑定关系由平台管理员在
-- 「平台角色 / 视角规则」页补配，或按 V109 同款语句重放）；启动校验见 ViewRuleDefaultsVerifier。

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

-- 菜单授予：与 V109 同款幂等补授（主迁移 V56 执行时 PLATFORM_ADMIN 可能尚未登记）。
INSERT IGNORE INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (147, 148);
