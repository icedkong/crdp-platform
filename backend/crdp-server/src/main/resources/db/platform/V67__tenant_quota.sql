-- 配额管理（E05-T09 / PLT-TEN-014、PLT-ORG-010）：用户数上限与存储上限，默认不限（NULL）。
--   语义（platform-spec §2 业务规则）：设置了用户数上限时，达到上限禁止新建或启用用户
--   （租户侧按「启用中用户数」计数——新建即启用、启用停用账号都会 +1，PLT-ORG-010）；
--   设置了存储上限时，用量达 80%（建议值）预警，达到上限禁止上传（sys_file 未删除行的 size_bytes 合计）。
--   上限不强制高于当前用量：调低只挡新增，不回收既有账号或文件。
--   维护接口 PUT /api/platform/tenants/{id}/quota（platform:tenant:edit，写 TENANT_QUOTA 审计），
--   新建租户向导第 2 步可带初始配额（userLimit / storageLimitBytes，随 POST /tenants）。

ALTER TABLE plt_tenant
  ADD COLUMN user_limit INT NULL COMMENT '用户数上限（启用中用户数；NULL=不限，PLT-TEN-014）',
  ADD COLUMN storage_limit_bytes BIGINT NULL COMMENT '存储上限（字节，sys_file 未删除行合计；NULL=不限，PLT-TEN-014）';
