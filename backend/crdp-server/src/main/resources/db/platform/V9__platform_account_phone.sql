-- E04-T04 / PLT-AUTH-003、PLT-AUTH-005：平台账号强制双因素，需要账号绑定手机号。
-- 与租户库 sys_user 同构：密文（purpose=plt-account-phone，密钥在平台库 crypto_data_key，见 OQ-34）
-- + 盲索引 phone_idx，平台库内唯一。

ALTER TABLE plt_account
  ADD COLUMN phone VARCHAR(512) NULL COMMENT '绑定手机号，信封加密密文（purpose=plt-account-phone）',
  ADD COLUMN phone_idx VARCHAR(64) NULL COMMENT '手机号盲索引（HMAC），双因素短信取号',
  ADD UNIQUE KEY uk_plt_account_phone_idx (phone_idx);
