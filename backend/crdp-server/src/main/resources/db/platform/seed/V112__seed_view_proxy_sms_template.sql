-- E20-T06 / PLT-VIEW-017：代为重置医院用户密码的临时密码短信模板（新密码经短信发给用户本人，已确认；
-- 平台账号在视角中看不到新密码）。场景 AUTH_TEMP_PASSWORD（V31 场景枚举扩展，参数白名单 = 系统生成的
-- 临时密码字符集），模板内容只含临时密码与提示语；provider_template_id 留空，部署侧报备后回填（与 V31 同口径）。
-- 幂等：code 唯一（INSERT IGNORE），老库与全新库（主迁移先于种子执行）补种结果一致；生产不含种子，
-- 初始模板由平台初始化时按本清单登记（与 V31 同口径）。

INSERT IGNORE INTO plt_sms_template
  (code, name, scene, content, provider, sign_name, status, built_in, remark) VALUES
  ('AUTH_TEMP_PASSWORD', '代重置密码临时密码下发', 'AUTH_TEMP_PASSWORD',
   '您的密码已由平台支持人员重置，临时密码{1}，首次登录后请及时修改。请勿泄露给他人。', 'TENCENT', NULL, 'APPROVED', 1,
   'E20-T06 / PLT-VIEW-017；参数：系统生成的临时密码（16 位以上，四类字符）');
