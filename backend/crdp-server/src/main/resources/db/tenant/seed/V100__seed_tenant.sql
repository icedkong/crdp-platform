-- 每个演示租户库执行同一份脚本。账号相同是故意的：账号只要求本院唯一。
-- 全部为虚构身份。口令哈希标记会在启动时被 Argon2id 替换。

INSERT INTO sys_org (code, name, status) VALUES ('ORG-DEMO', '虚构院区', 'ENABLED');

INSERT INTO sys_dept (org_id, parent_id, code, name, status)
SELECT id, 0, 'DEPT-CARDIO', '虚构心内科', 'ENABLED' FROM sys_org WHERE code = 'ORG-DEMO';

INSERT INTO sys_user (username, display_name, password_hash, status, must_change_password) VALUES
  ('nurse.demo', '演示护士', 'PENDING_DEMO_HASH', 'ENABLED', 0),
  ('doctor.demo', '演示医生', 'PENDING_DEMO_HASH', 'ENABLED', 0);

INSERT INTO sys_role (code, name, built_in, status) VALUES
  ('TENANT_ADMIN', '租户管理员', 1, 'ENABLED'),
  ('FOLLOWUP_DOCTOR', '随访医生', 0, 'ENABLED');

INSERT INTO sys_user_role (user_id, role_id)
SELECT u.id, r.id
FROM sys_user u
JOIN sys_role r
  ON (u.username = 'nurse.demo' AND r.code = 'TENANT_ADMIN')
  OR (u.username = 'doctor.demo' AND r.code = 'FOLLOWUP_DOCTOR');

INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 10 AS menu_id
  UNION ALL SELECT 'TENANT_ADMIN', 20
  UNION ALL SELECT 'TENANT_ADMIN', 21
  UNION ALL SELECT 'TENANT_ADMIN', 22
  UNION ALL SELECT 'TENANT_ADMIN', 23
  UNION ALL SELECT 'TENANT_ADMIN', 30
  UNION ALL SELECT 'TENANT_ADMIN', 31
  UNION ALL SELECT 'TENANT_ADMIN', 32
  UNION ALL SELECT 'TENANT_ADMIN', 33
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 10
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 20
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 21
  UNION ALL SELECT 'FOLLOWUP_DOCTOR', 22
) m ON m.code = r.code;

INSERT INTO sys_role_scope (role_id, scope_type)
SELECT id, 'ALL' FROM sys_role WHERE code = 'TENANT_ADMIN';

INSERT INTO sys_role_scope (role_id, scope_type)
SELECT id, 'WARD' FROM sys_role WHERE code = 'FOLLOWUP_DOCTOR';

INSERT INTO sys_role_scope (role_id, scope_type)
SELECT id, 'RESPONSIBLE_PATIENT' FROM sys_role WHERE code = 'FOLLOWUP_DOCTOR';

INSERT INTO ward (code, name, status) VALUES ('W-DEMO-1', '虚构病区一', 'ENABLED');

INSERT INTO sys_user_ward (user_id, ward_id)
SELECT u.id, w.id
FROM sys_user u, ward w
WHERE u.username = 'doctor.demo' AND w.code = 'W-DEMO-1';

INSERT INTO cfg_param (param_key, param_value, remark) VALUES
  ('hospital.display_name', '虚构示例医院', '演示参数，正式名称以平台库租户档案为准');

INSERT INTO cfg_feature (feature_code, enabled) VALUES
  ('report', 1),
  ('wide_import', 1),
  ('node_follow_rate', 1),
  ('scan_progress', 0),
  ('implant', 0),
  ('eri_cycle', 0),
  ('compare_highlight', 1);
