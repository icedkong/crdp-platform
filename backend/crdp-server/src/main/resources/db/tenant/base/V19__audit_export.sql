-- E08-T04 / PLT-AUD-005、006、007：医院端「审计中心」菜单授予与审计导出作业表。
-- 表结构与平台库 V28 的 job_export / job_export_approval 同构（租户侧无 tenant_id 列：
-- 租户库表不带租户维度，AGENTS 第 5 节）；E13-T09 患者导出复用同一组表（biz_type=PATIENT）。

CREATE TABLE job_export (
  id BIGINT NOT NULL AUTO_INCREMENT,
  biz_type VARCHAR(32) NOT NULL COMMENT 'AUDIT / PATIENT（E13 起）',
  export_type VARCHAR(32) NOT NULL COMMENT '日志类型：OPERATION / LOGIN / DATA_ACCESS',
  condition_json VARCHAR(1024) NOT NULL COMMENT '导出查询条件摘要（不含敏感明文）',
  reason VARCHAR(512) NULL COMMENT '申请理由',
  applicant_id BIGINT NOT NULL COMMENT '申请人',
  applicant_name VARCHAR(64) NOT NULL COMMENT '申请人账号',
  status VARCHAR(32) NOT NULL COMMENT 'PENDING_APPROVAL / GENERATING / READY / REJECTED / FAILED',
  approver_id BIGINT NULL COMMENT '审批人',
  approver_name VARCHAR(64) NULL COMMENT '审批人账号',
  approve_comment VARCHAR(512) NULL COMMENT '审批意见',
  row_count BIGINT NULL COMMENT '导出行数',
  file_name VARCHAR(128) NULL COMMENT '导出文件名',
  file_hash VARCHAR(128) NULL COMMENT '导出文件 SHA-256（经 DigestService）',
  file_body LONGBLOB NULL COMMENT '导出文件内容（CSV，含水印；生成后 24 小时过期）',
  fail_reason VARCHAR(512) NULL COMMENT '生成失败原因',
  expires_at DATETIME(3) NULL COMMENT '下载截止时间（生成完成 + 24 小时，PLT-AUD-007 限时下载）',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_job_export_status (status, created_at),
  KEY idx_job_export_applicant (applicant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='导出作业（PLT-AUD-007 异步导出；审批与限时下载）';

CREATE TABLE job_export_approval (
  id BIGINT NOT NULL AUTO_INCREMENT,
  export_id BIGINT NOT NULL COMMENT '导出作业 ID',
  action VARCHAR(16) NOT NULL COMMENT 'APPROVE / REJECT',
  approver_id BIGINT NOT NULL COMMENT '审批人',
  approver_name VARCHAR(64) NULL COMMENT '审批人账号',
  comment VARCHAR(512) NULL COMMENT '审批意见',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_job_export_approval_export (export_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='导出审批记录（PLT-AUD-007，只追加）';

-- 审计中心菜单（plt_menu 35 / 351 / 352，权限码 tenant:audit:view / export / export:approve，
-- 平台库 V28 同批新增）授予内置 TENANT_ADMIN。全新 local/dev 库上本脚本先于种子 V100 运行、
-- 角色还不存在（命中 0 行），由种子 V109 补授；生产由开通编排 ensureAdmin 授予（本批同步加清单）。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, m.menu_id
FROM sys_role r
JOIN (
  SELECT 'TENANT_ADMIN' AS code, 35 AS menu_id
  UNION ALL SELECT 'TENANT_ADMIN', 351
  UNION ALL SELECT 'TENANT_ADMIN', 352
) m ON m.code = r.code
WHERE r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = m.menu_id
  );
