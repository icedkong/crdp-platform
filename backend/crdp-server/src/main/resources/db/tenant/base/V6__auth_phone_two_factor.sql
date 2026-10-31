-- E04-T04 / PLT-AUTH-002、PLT-AUTH-003：短信验证码登录与双因素认证。
-- 账号绑定手机号：明文不落库，存信封加密密文（CryptoService，purpose=sys-user-phone，密钥在租户库
-- crypto_data_key），登录按 phone_idx 盲索引（HMAC）查询；本院唯一（多条 NULL 不冲突，未绑定不限）。

ALTER TABLE sys_user
  ADD COLUMN phone VARCHAR(512) NULL COMMENT '绑定手机号，信封加密密文（purpose=sys-user-phone）',
  ADD COLUMN phone_idx VARCHAR(64) NULL COMMENT '手机号盲索引（HMAC），短信登录与双因素取号',
  ADD UNIQUE KEY uk_sys_user_phone_idx (phone_idx);

-- 双因素按角色配置（PLT-AUTH-003「租户按角色配置」）：逗号分隔的角色编码，
-- 账号命中其中任一角色时密码第一步通过后还需短信第二步。空值 = 本院无角色强制。
INSERT INTO cfg_param (param_key, param_value, remark) VALUES
  ('security.two-factor.roles', '',
   '双因素认证：密码后需短信第二步的角色编码，逗号分隔；空表示无角色强制（E04-T04）');
