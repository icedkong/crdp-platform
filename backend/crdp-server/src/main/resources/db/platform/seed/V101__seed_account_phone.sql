-- E04-T04 演示数据：平台演示账号的虚构手机号（1390000xxxx 段，非真实号码）。
-- 标记由应用在 local/dev 启动时加密并回填盲索引（DemoPhoneInitializer）。

UPDATE plt_account SET phone = 'PENDING_DEMO_PHONE:13900000003' WHERE username = 'platform.admin';
