output "audit_archive_bucket" {
  description = "审计归档桶名（应用配置 crdp.audit.archive.storage.obs.bucket / 环境变量 CRDP_OBS_ARCHIVE_BUCKET）"
  value       = huaweicloud_obs_bucket.audit_archive.bucket
}

output "audit_archive_region" {
  description = "桶所在区域（只写北京四一份，不做异地副本）"
  value       = huaweicloud_obs_bucket.audit_archive.region
}

output "worm_pending_ops_steps" {
  description = "terraform 未覆盖、需运维按 runbook 落实的两步（桶级 WORM 15 年合规、180 天转深度归档）"
  value       = ["console-or-sdk: bucket-level WORM COMPLIANCE 15y", "console-or-api: lifecycle transition 180d -> DEEP_ARCHIVE"]
}
