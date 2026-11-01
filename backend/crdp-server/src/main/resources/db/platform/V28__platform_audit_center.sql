-- E08-T04 / PLT-AUD-005、006、007：两端「审计中心」菜单与平台侧审计导出作业表。
-- 平台端菜单（pages.md §11）：/platform/audit，页签含平台操作 / 平台登录 / 租户视角 / 视角规则变更 /
-- 租户控制面日志；按钮：申请导出 platform:audit:export、审批导出 platform:audit:export:approve。
-- 医院端菜单（pages.md §0.1 / §10）：/system/audit，页签为操作 / 登录 / 数据访问日志（不含平台访问记录）；
-- 按钮：申请导出 tenant:audit:export（敏感权限，已在 plt_sensitive_perm 清单，V18）、
-- 审批导出 tenant:audit:export:approve（均出自 platform-spec §9 权限点表；码创建后不改名，OQ-11）。
-- 平台菜单授予 PLATFORM_ADMIN 与 V20/V24 同款做法：主迁移 INSERT..SELECT（全新库此时还没有
-- PLATFORM_ADMIN 行，命中 0 行无害；种子 V100 会把当时的全部平台端菜单动态授予它；老库由本脚本补授）。
-- 医院端菜单的角色授予在租户库：老库由 base V19 增量授予、全新 local/dev 库由种子 V109 补授、
-- 生产由开通编排 ensureAdmin 授予（base 模块医院端菜单是固定能力，不进授权范围模板）。

INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (129, 110, 'PLATFORM', 'MENU', 'base', '审计中心', 'PlatformAudit', '/platform/audit', 'platform/audit/index', 'platform:audit:view', 'AuditOutlined', 90, 0, 'ENABLED'),
  (1291, 129, 'PLATFORM', 'BUTTON', 'base', '申请导出审计日志', NULL, NULL, NULL, 'platform:audit:export', NULL, 10, 1, 'ENABLED'),
  (1292, 129, 'PLATFORM', 'BUTTON', 'base', '审批审计导出', NULL, NULL, NULL, 'platform:audit:export:approve', NULL, 20, 1, 'ENABLED'),
  (35, 30, 'HOSPITAL', 'MENU', 'base', '审计中心', 'HospitalAudit', '/system/audit', 'system/audit/index', 'tenant:audit:view', NULL, 35, 0, 'ENABLED'),
  (351, 35, 'HOSPITAL', 'BUTTON', 'base', '申请导出审计日志', NULL, NULL, NULL, 'tenant:audit:export', NULL, 10, 1, 'ENABLED'),
  (352, 35, 'HOSPITAL', 'BUTTON', 'base', '审批审计导出', NULL, NULL, NULL, 'tenant:audit:export:approve', NULL, 20, 1, 'ENABLED');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (129, 1291, 1292);

-- 审计导出作业（PLT-AUD-007，import-export.md §3）：biz_type 区分业务（AUDIT 现在、PATIENT 随 E13-T09）。
-- 状态机：PENDING_APPROVAL（待审批）→ GENERATING（异步生成）→ READY（限时下载）/ REJECTED / FAILED；
-- 平台账号导出一律待平台管理员审批，申请人不能审批自己的申请（platform-spec §9 已确认）。
-- 文件体存 LONGBLOB：M1 单实例（ADR 0003 第 4 条）先行，对象存储（E10）落地后迁到桶 + 签名链接。
CREATE TABLE job_export (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NULL COMMENT '按租户筛选导出时记录（平台侧），租户侧恒为 NULL',
  biz_type VARCHAR(32) NOT NULL COMMENT 'AUDIT / PATIENT（E13 起）',
  export_type VARCHAR(32) NOT NULL COMMENT '日志类型：OPERATION / LOGIN / DATA_ACCESS / PLATFORM_ACCESS',
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

-- 审批记录（只追加）：一次申请至多一条终局审批（APPROVE / REJECT），供审批追溯。
CREATE TABLE job_export_approval (
  id BIGINT NOT NULL AUTO_INCREMENT,
  export_id BIGINT NOT NULL COMMENT '导出作业 ID',
  action VARCHAR(16) NOT NULL COMMENT 'APPROVE / REJECT',
  approver_id BIGINT NOT NULL COMMENT '审批人',
  approver_name VARCHAR(64) NOT NULL COMMENT '审批人账号',
  comment VARCHAR(512) NULL COMMENT '审批意见',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_job_export_approval_export (export_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='导出审批记录（PLT-AUD-007，只追加）';
