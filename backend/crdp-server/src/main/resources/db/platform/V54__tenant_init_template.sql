-- E12-T05 / A-FLD-014「新建租户时选择模板初始化」：plt_tenant 登记开通向导选择的配置模板。
-- 开通编排新增 config-template 步骤（模块开通之后执行，见 TenantOnboardingService）：
--   ① 新建时带 templateId → 插入行即落本列；
--   ② 失败重试（向导原参数重提交或进度页重试）未带 templateId 时沿用本列，保证步骤可重入；
--   ③ 未选择模板为 NULL，该步骤记 SKIPPED。
-- 只存模板 id（追溯经 plt_config_template 头信息与 CONFIG_TEMPLATE_APPLY 审计），不落外键——
-- 模板可被平台侧清理（plt_config_template 无删除接口，但库级运维可能清表），留 NULL 化余地。

ALTER TABLE plt_tenant
  ADD COLUMN init_template_id BIGINT NULL COMMENT '初始化所选配置模板 plt_config_template.id（E12-T05；重试沿用，未选择为 NULL）'
  AFTER grace_read_only;
