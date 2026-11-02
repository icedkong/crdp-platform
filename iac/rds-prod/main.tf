# E22-T04：RDS for MySQL 8.0 生产实例（主备、跨区域备份、参数基线）。
# 事实源是同目录 params-baseline.json（本地 / CI 由 scripts/check_rds_baseline.py 校验），
# 实例级参数（time_zone、lower_case_table_names）与参数模板条目都从该 JSON 取值，避免两处漂移。
# 真实 apply 由持华为云账号的运维在维护窗口执行（AGENTS.md 第 9 节）。

locals {
  baseline = jsondecode(file("${path.module}/params-baseline.json"))
}

# 参数基线模板：utf8mb4 / 慢日志口径 / 触发器可建（OQ-55）等，条目与出处见 JSON 与 docs/ops/rds-prod.md。
resource "huaweicloud_rds_parametergroup" "prod" {
  name        = "${var.instance_name}-baseline"
  description = "crdp 生产参数基线（E22-T04），与 iac/rds-prod/params-baseline.json 一致"

  values = { for p in local.baseline.parameters : p.name => p.value }

  datastore {
    type    = local.baseline.instance.engine
    version = local.baseline.instance.engine_version
  }
}

# 主备实例：两个可用区（列表第二个元素即备机 AZ）+ 半同步复制，RPO 约 0、分钟级自动切换（tech-plan §14）。
resource "huaweicloud_rds_instance" "prod" {
  name              = var.instance_name
  flavor            = var.flavor
  availability_zone = local.baseline.instance.availability_zones

  ha_replication_mode    = local.baseline.instance.ha_replication_mode
  switch_strategy        = local.baseline.instance.switch_strategy
  time_zone              = local.baseline.instance.time_zone
  lower_case_table_names = local.baseline.instance.lower_case_table_names
  ssl_enable             = local.baseline.instance.ssl_enable
  port                   = local.baseline.instance.port

  vpc_id            = var.vpc_id
  subnet_id         = var.subnet_id
  security_group_id = var.security_group_id

  volume {
    type = var.volume_type
    size = var.volume_size_gb
  }

  db {
    type     = local.baseline.instance.engine
    version  = local.baseline.instance.engine_version
    password = var.admin_password
  }

  # 自动全量备份 + Binlog 增量，保留 30 天（NFR-AVL-004）；Binlog 上传节奏由 RDS 托管（区域内 RPO ≤ 5 分钟）。
  backup_strategy {
    start_time = local.baseline.backup.start_time
    keep_days  = local.baseline.backup.keep_days
  }

  param_group_id = huaweicloud_rds_parametergroup.prod.id

  maintain_begin = var.maintain_begin
  maintain_end   = var.maintain_end

  charging_mode = var.charging_mode
  period_unit   = var.period_unit
  period        = var.period
  auto_renew    = var.auto_renew

  tags = var.tags

  # 删除实例时保留自动备份，让保留期内仍可恢复（销毁流程另见 platform-spec §销毁）
  delete_backup_selection = "true"
}

# 跨区域备份（E22-T04 验收项）：北京四 → 上海一（cn-east-3），全量 + Binlog，保留 30 天。
# 前置：先提交华为云工单开通跨区域备份（tech-plan §2 核实记录），异步复制不保证及时性 / 无 SLA（tech-plan §14 已确认接受）。
resource "huaweicloud_rds_cross_region_backup_strategy" "prod" {
  count = local.baseline.cross_region_backup.enabled ? 1 : 0

  instance_id            = huaweicloud_rds_instance.prod.id
  backup_type            = local.baseline.cross_region_backup.backup_type
  keep_days              = local.baseline.cross_region_backup.keep_days
  destination_region     = local.baseline.cross_region_backup.destination_region
  destination_project_id = var.destination_project_id
}
