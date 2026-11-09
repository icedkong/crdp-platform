# E22-T08：审计归档 OBS WORM 桶（PLT-AUD-010「审计日志长期留存，在线保留 1 年后转归档存储」）。
# 事实源是同目录 worm-baseline.json（本地 / CI 由 scripts/check_worm_baseline.py 校验），
# 桶形态（单可用区、STANDARD、版本控制）与 30 天转归档（COLD）从该 JSON 取值，避免两处漂移。
# 真实 apply 由持华为云账号的运维在维护窗口执行（AGENTS.md 第 9 节）。
#
# 【provider 能力边界，运维按 docs/ops/audit-worm-archive.md 落实（不在本 IaC 内）】
# 1. 桶级 WORM（对象锁定，合规模式默认保留 15 年）：terraform-provider-huaweicloud 无对应资源
#    （2026-11 核实，OQ-123），terraform apply 建桶后由运维在控制台或经 SDK 开启；
#    开启后不可关闭，且自动启用版本控制（不可停用）。
# 2. 180 天转深度归档（DEEP_ARCHIVE）：provider 的 lifecycle transition 仅支持 WARM/COLD，
#    由运维经控制台 / OBS API（PutBucketLifecycle）在同一规则上追加。
# 3. 有意不配置任何 expiration / 自动删除：归档对象到期后默认不自动删除（tech-plan §14）。
# 4. 有意不配置 replication_configuration：只写北京四一份（tech-plan §14 已确认；
#    WORM 桶也不支持作为跨区域复制源）。

locals {
  baseline = jsondecode(file("${path.module}/worm-baseline.json"))
}

resource "huaweicloud_obs_bucket" "audit_archive" {
  bucket        = var.bucket_name
  region        = local.baseline.region
  acl           = local.baseline.bucket.acl
  storage_class = local.baseline.bucket.storage_class
  # 单可用区（false）：归档桶不做多 AZ（tech-plan §14；归档 / 深度归档存储类别本身仅支持单 AZ）
  multi_az = local.baseline.bucket.multi_az
  # WORM 开启后自动启用且不可停用；显式声明与最终态一致（provider 文档：版本控制一旦开启不可回到未版本化）
  versioning = local.baseline.bucket.versioning

  # 归档对象 30 天后转归档存储（COLD）：WORM 允许转换存储类别，保留策略不受影响
  lifecycle_rule {
    name    = "audit-archive-tiering"
    prefix  = var.lifecycle_prefix
    enabled = true

    transition {
      days          = local.baseline.lifecycle[0].days
      storage_class = local.baseline.lifecycle[0].storage_class
    }
    # 180 天转深度归档（DEEP_ARCHIVE）由运维经控制台 / API 追加到本规则（provider 不支持，
    # 见文件头说明与 docs/ops/audit-worm-archive.md §2）；不得因此引入 expiration。
  }
}
