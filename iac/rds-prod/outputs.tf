# 供运维验证清单与后续任务（E22-T06 单院恢复、E22-T07 跨区域演练）引用。

output "instance_id" {
  description = "RDS 实例 ID"
  value       = huaweicloud_rds_instance.prod.id
}

output "private_ips" {
  description = "私网地址（应用侧 CRDP_PLATFORM_JDBC_URL 指向它；内网浮动 VIP，主备切换后不变）"
  value       = huaweicloud_rds_instance.prod.private_ips
}

output "private_dns_names" {
  description = "私网域名（优先用域名对接，规避 IP 变更）"
  value       = huaweicloud_rds_instance.prod.private_dns_names
}

output "parametergroup_id" {
  description = "参数基线模板 ID"
  value       = huaweicloud_rds_parametergroup.prod.id
}

output "nodes" {
  description = "主备节点（角色与可用区），主备切换验证时记录切换前后快照"
  value       = huaweicloud_rds_instance.prod.nodes
}

output "cross_region_backup_strategy_id" {
  description = "跨区域备份策略 ID（工单开通并 apply 后生成）"
  value       = try(huaweicloud_rds_cross_region_backup_strategy.prod[0].id, null)
}
