-- E16-T09 / A-ATT-007、C-SCAN-016：报告单软删除列（V63 建表时预留——「软删除列（删除原因 / 删除人 /
-- 删除时间）随 E16-T09 落地，本脚本先建 status 列」）。删除整份报告单：status NORMAL → DELETED
-- 并记原因 / 删除人 / 删除时间（数据长期保留，A-NFR-008 只软删口径）；其中单个文件的删除不另加列——
-- 文件可见性以 sys_file.status 为准（DELETED 后预览 / 下载一律 40400），rpt_report_file 关联行保留
-- 作为「曾挂在哪份报告单」的台账（与沟通录音删除同口径，E15-T04）。
-- 另：删除按钮（平台库 V55，plt_menu 233）授予内置 TENANT_ADMIN（与 V25 的 231 同口径：
-- 老库补授、全新 local/dev 库本脚本先于租户种子 V100 执行同样命中；幂等）。

ALTER TABLE rpt_report
  ADD COLUMN delete_reason VARCHAR(255) NULL COMMENT '删除原因（A-ATT-007 必填；审计 aud_log 同步留痕）' AFTER status,
  ADD COLUMN deleted_by BIGINT NULL COMMENT '删除人 sys_user.id' AFTER delete_reason,
  ADD COLUMN deleted_at DATETIME(3) NULL COMMENT '删除时间' AFTER deleted_by;

-- 删除按钮（plt_menu 233，followup:scan:delete）授予 TENANT_ADMIN。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT r.id, 233
FROM sys_role r
WHERE r.code = 'TENANT_ADMIN'
  AND r.built_in = 1
  AND NOT EXISTS (
    SELECT 1 FROM sys_role_menu rm WHERE rm.role_id = r.id AND rm.menu_id = 233
  );
