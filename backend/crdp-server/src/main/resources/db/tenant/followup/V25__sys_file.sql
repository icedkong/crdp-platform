-- E10-T01 / A-ATT-008、C-SCAN-018、PLT-CFG-010：文件服务上传台账（sys_file）。
--   上传配置（类型白名单、大小与每份数量上限）在平台库 V33（plt_param，租户可经 cfg_param 覆盖）；
--   本表记录每个上传文件的对象键、原文件名、Tika 识别出的真实类型、大小与 SHA-256（A-ATT-006 审计、
--   C-SCAN-017 重复提示共用该哈希）。租户库表不带 tenant_id（AGENTS §5.1）。
--   body 暂存文件体（LONGBLOB）：对象存储（OBS）在 E10-T02 接入，落地后迁桶并清理本列，
--   接口与哈希口径不变（与 OQ-56 job_export.file_body 同款暂行做法）。
--   status：PENDING 上传未关联（报告单随 E16 关联时置 ATTACHED）；DELETED 软删除（删除随 E16-T09）。
-- 另：上传按钮（平台库 V33，plt_menu 231）授予 TENANT_ADMIN（与 V22 同口径：老库补授、
--   全新 local/dev 库本脚本先于租户种子 V100 执行同样命中；幂等）。

CREATE TABLE sys_file (
  id BIGINT NOT NULL AUTO_INCREMENT,
  object_key VARCHAR(255) NOT NULL COMMENT '对象键（files/{年月}/{uuid}.{扩展名}；E10-T02 起按租户前缀入桶）',
  original_name VARCHAR(255) NOT NULL COMMENT '原文件名（含扩展名，已去除路径部分）',
  format_code VARCHAR(16) NOT NULL COMMENT '真实类型（FileFormat 编码，Tika 按内容识别，不信任扩展名）',
  mime_type VARCHAR(128) NOT NULL COMMENT 'Tika 识别出的 MIME 类型',
  size_bytes BIGINT NOT NULL COMMENT '文件大小（字节）',
  sha256 CHAR(64) NOT NULL COMMENT '文件内容 SHA-256（小写十六进制）',
  pages INT NULL COMMENT '页数（图片 / PDF；随 E10-T03 缩略图任务回填）',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING 上传未关联 / ATTACHED 已关联报告单 / DELETED 软删除',
  body LONGBLOB NULL COMMENT '文件体（E10-T02 OBS 接入前的暂行存储；迁桶后置 NULL）',
  uploader_id BIGINT NOT NULL COMMENT '上传人（sys_user.id）',
  uploaded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_sys_file_object_key (object_key),
  KEY idx_sys_file_sha256 (sha256),
  KEY idx_sys_file_uploader (status, uploader_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='文件服务上传台账（A-ATT-008 / C-SCAN-018 / PLT-CFG-010）';

-- 上传按钮（plt_menu 231，followup:scan:upload）授予 TENANT_ADMIN。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 231
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 231
  );
