-- 租户注销审批、数据迁出、销毁证明与法定保留封存（E05-T08 / PLT-TEN-018..021）。
--   注销流程主表 plt_tenant_cancel：一租户一条流程行（uk tenant_id），REJECTED / REVOKED 后可重新发起（重置同一行，历史在 plt_audit_log）。
--   stage 流转：PENDING_APPROVAL（已申请待审批，租户仍停用）→ COOLING（审批通过，租户=注销中，冷静期 30 天）
--     → EXPORTED（迁出包已生成，冷静期满后可生成）→ ACKED（医院签收）→ DESTROYED（销毁完成，租户=已注销）；
--     分支：PENDING_APPROVAL → REJECTED（审批驳回，回到停用可重发起）、COOLING → REVOKED（冷静期内撤销，回到停用）。
--   书面确认 / 签收单：plt_cancel_file（按 tenant_id 归档，kind=CONFIRMATION / ACK），对象存 platform/ 前缀。
--   迁出包 plt_tenant_export：标准迁出包（每表 CSV+JSON、数据字典、原始文件、manifest）加密打包，
--     限时下载（默认 7 天 / 5 次，已确认），解压密码信封加密存 password_cipher（短信通道随 E09）。
--   法定保留 plt_tenant_retention：REGISTERED → SEALED（独立密钥加密封存，登记保留期满日）→ DESTROYED（期满销毁，补充证明）。
--   销毁证明 plt_destroy_certificate：租户整体销毁（TENANT）与保留数据期满销毁（RETENTION）两类，PDF 平台存档。

CREATE TABLE plt_tenant_cancel (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  stage VARCHAR(24) NOT NULL COMMENT 'PENDING_APPROVAL/COOLING/EXPORTED/ACKED/DESTROYED/REJECTED/REVOKED',
  reason VARCHAR(500) NOT NULL COMMENT '注销原因',
  confirmation_file_id BIGINT NULL COMMENT '医院书面确认（plt_cancel_file.id，申请时必填）',
  applied_by BIGINT NOT NULL COMMENT '发起人（平台管理员或实施人员）',
  applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  approved_by BIGINT NULL COMMENT '审批人（须为另一名平台管理员）',
  approved_at DATETIME(3) NULL,
  approve_note VARCHAR(500) NULL COMMENT '审批意见（驳回原因也记这里）',
  cooling_until DATETIME(3) NULL COMMENT '冷静期截止（审批时刻 + 30 天，已确认默认）',
  revoked_by BIGINT NULL COMMENT '撤销人',
  revoked_at DATETIME(3) NULL,
  revoke_reason VARCHAR(500) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_tenant_cancel_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户注销流程（E05-T08 / PLT-TEN-018..021）';

CREATE TABLE plt_cancel_file (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  kind VARCHAR(16) NOT NULL COMMENT 'CONFIRMATION 医院书面确认 / ACK 迁出包签收单',
  file_name VARCHAR(255) NOT NULL COMMENT '原始文件名',
  object_key VARCHAR(512) NOT NULL COMMENT '对象键（platform/ 前缀，注销档案不随租户销毁）',
  sha256 CHAR(64) NOT NULL COMMENT '内容 SHA-256（小写十六进制）',
  size_bytes BIGINT NOT NULL,
  uploaded_by BIGINT NOT NULL,
  uploaded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_plt_cancel_file_tenant (tenant_id, kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='注销流程附件（书面确认与签收单）';

CREATE TABLE plt_tenant_export (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  package_no VARCHAR(48) NOT NULL COMMENT '迁出包编号（EXP-{租户id}-{时间戳}-{随机}）',
  object_key VARCHAR(512) NOT NULL COMMENT '加密包对象键（platform/ 前缀）',
  sha256 CHAR(64) NOT NULL COMMENT '整包（加密后）SHA-256',
  size_bytes BIGINT NOT NULL,
  table_count INT NOT NULL COMMENT '结构化数据表数',
  total_rows BIGINT NOT NULL COMMENT '全部表行数合计',
  file_count INT NOT NULL COMMENT '原始文件数',
  password_cipher VARCHAR(1024) NOT NULL COMMENT '解压密码密文（信封加密 purpose=plt-export-password；经短信另行发送，通道随 E09）',
  expires_at DATETIME(3) NOT NULL COMMENT '下载链接有效期（生成时刻 + 7 天，已确认）',
  max_downloads INT NOT NULL DEFAULT 5 COMMENT '最多下载次数（已确认 5 次）',
  download_count INT NOT NULL DEFAULT 0,
  generated_by BIGINT NOT NULL,
  generated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  acked_by BIGINT NULL COMMENT '医院签收确认人（平台账号代录）',
  acked_at DATETIME(3) NULL,
  ack_note VARCHAR(500) NULL,
  ack_file_id BIGINT NULL COMMENT '签收单（plt_cancel_file.id，kind=ACK）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_tenant_export_no (package_no),
  KEY idx_plt_tenant_export_tenant (tenant_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='标准数据迁出包（PLT-TEN-019）';

CREATE TABLE plt_tenant_retention (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  scope VARCHAR(200) NOT NULL COMMENT '保留数据范围说明（如 RCT 原始资料）',
  legal_basis VARCHAR(200) NOT NULL COMMENT '法律或合同依据',
  tables_csv VARCHAR(1000) NULL COMMENT '封存时导出的租户库表（逗号分隔，空=仅登记清单不导表）',
  retain_until DATE NOT NULL COMMENT '保留期满日（期满后按销毁流程处理并补充证明）',
  status VARCHAR(16) NOT NULL DEFAULT 'REGISTERED' COMMENT 'REGISTERED 已登记 / SEALED 已封存 / DESTROYED 已销毁',
  sealed_by BIGINT NULL,
  sealed_at DATETIME(3) NULL,
  sealed_object_key VARCHAR(512) NULL COMMENT '封存包对象键（platform/ 前缀，独立密钥加密）',
  sealed_key_cipher VARCHAR(1024) NULL COMMENT '封存包独立解压密码密文（信封加密 purpose=plt-retain-password）',
  sealed_sha256 CHAR(64) NULL,
  destroyed_by BIGINT NULL,
  destroyed_at DATETIME(3) NULL,
  certificate_id BIGINT NULL COMMENT '期满销毁补充证明（plt_destroy_certificate.id）',
  created_by BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_plt_tenant_retention_tenant (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='法定保留数据封存（PLT-TEN-021）';

CREATE TABLE plt_destroy_certificate (
  id BIGINT NOT NULL AUTO_INCREMENT,
  cert_no VARCHAR(48) NOT NULL COMMENT '证明编号（DEST-{id 补零}）',
  kind VARCHAR(16) NOT NULL DEFAULT 'TENANT' COMMENT 'TENANT 租户整体销毁 / RETENTION 保留数据期满销毁',
  tenant_id BIGINT NOT NULL,
  tenant_code VARCHAR(32) NOT NULL,
  tenant_name VARCHAR(128) NOT NULL,
  scope_items TEXT NOT NULL COMMENT '销毁范围清单 JSON（资源 → 处置与结果）',
  method_text TEXT NOT NULL COMMENT '销毁方式说明（删库与桶、密钥计划删除、备份自然失效）',
  verification TEXT NOT NULL COMMENT '销毁后校验结果（资源已不存在的检查）',
  retention_note TEXT NULL COMMENT '保留数据说明（封存清单与保留期满日；无封存为 NULL）',
  executed_by BIGINT NOT NULL COMMENT '执行人',
  executed_by_name VARCHAR(64) NOT NULL,
  reviewed_by BIGINT NOT NULL COMMENT '复核人（另一名平台管理员）',
  reviewed_by_name VARCHAR(64) NOT NULL,
  executed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  object_key VARCHAR(512) NOT NULL COMMENT '证明 PDF 对象键（platform/ 前缀，平台存档并交付医院）',
  sha256 CHAR(64) NOT NULL,
  size_bytes BIGINT NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_plt_destroy_certificate_no (cert_no),
  KEY idx_plt_destroy_certificate_tenant (tenant_id, kind)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据销毁证明（PLT-TEN-020）';

-- 资源销毁标记（PLT-TEN-020）：库与账号已删，行保留作销毁凭据（资源状态=已销毁）；密码密文作废。
ALTER TABLE plt_tenant_resource
  ADD COLUMN destroyed_at DATETIME(3) NULL COMMENT '已销毁（注销销毁执行时刻；此前为 NULL=正常）';

-- 权限码（platform-spec §2 权限点表，创建后不改名 OQ-11）+ 隐藏的「注销与数据处理」页（路由从租户详情进入）。
-- 平台管理员全部授予；实施人员可发起注销（规格：平台管理员或实施人员发起），审批 / 迁出 / 销毁 / 封存不授予。
INSERT INTO plt_menu
  (id, parent_id, side, type, module_code, title, route_name, path, component, perm_code, icon, sort_no, hidden, status) VALUES
  (135, 111, 'PLATFORM', 'BUTTON', 'base', '发起或撤销注销', NULL, NULL, NULL, 'platform:tenant:cancel', NULL, 50, 1, 'ENABLED'),
  (136, 111, 'PLATFORM', 'BUTTON', 'base', '审批注销', NULL, NULL, NULL, 'platform:tenant:cancel:approve', NULL, 60, 1, 'ENABLED'),
  (137, 111, 'PLATFORM', 'BUTTON', 'base', '数据迁出', NULL, NULL, NULL, 'platform:tenant:export', NULL, 70, 1, 'ENABLED'),
  (138, 111, 'PLATFORM', 'BUTTON', 'base', '数据销毁与证明', NULL, NULL, NULL, 'platform:tenant:destroy', NULL, 80, 1, 'ENABLED'),
  (139, 111, 'PLATFORM', 'BUTTON', 'base', '保留数据封存', NULL, NULL, NULL, 'platform:tenant:retain', NULL, 90, 1, 'ENABLED'),
  (140, 110, 'PLATFORM', 'MENU', 'base', '注销与数据处理', 'PlatformTenantCancel', '/platform/tenant/:id/cancel', 'platform/tenant/cancel', NULL, NULL, 65, 1, 'ENABLED');

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'PLATFORM_ADMIN' AND m.id IN (135, 136, 137, 138, 139, 140);

INSERT INTO plt_role_menu (role_id, menu_id)
SELECT r.id, m.id FROM plt_role r, plt_menu m
WHERE r.code = 'IMPLEMENTATION' AND m.id IN (135, 140) AND m.status = 'ENABLED';
