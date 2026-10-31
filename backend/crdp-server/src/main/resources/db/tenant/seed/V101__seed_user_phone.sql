-- E04-T04 演示数据：给演示账号绑定虚构手机号（1390000xxxx 段，非真实号码）。
-- 种子只放 PENDING_DEMO_PHONE:手机号 标记，由应用在 local/dev 启动时加密并回填盲索引
-- （DemoPhoneInitializer，SQL 里算不了 HMAC）；生产不执行种子目录。

UPDATE sys_user SET phone = 'PENDING_DEMO_PHONE:13900000001' WHERE username = 'nurse.demo';
UPDATE sys_user SET phone = 'PENDING_DEMO_PHONE:13900000002' WHERE username = 'doctor.demo';
