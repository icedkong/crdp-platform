# 验证清单（docs/ops/cce-prod.md §6）与后续任务引用：E22-T09 渗透测试的靶机入口、
# E22-T03 监控告警的对象清单、E23 上线切换的 DNS 接入值。

output "cluster_id" {
  description = "CCE 集群 ID"
  value       = huaweicloud_cce_cluster.prod.id
}

output "node_server_ids" {
  description = "工作节点 ECS 实例 ID（HSS 防护核对清单）"
  value       = local.node_server_ids
}

output "node_private_ips" {
  description = "工作节点私网 IP（ELB 后端成员核对）"
  value       = local.node_private_ips
}

output "elb_public_ip" {
  description = "ELB 公网 EIP（WAF 回源地址、DNS 解析核对）"
  value       = huaweicloud_vpc_eip.elb.address
}

output "waf_domain_ids" {
  description = "WAF 防护域名 ID（接入状态核对：access_status=1）"
  value       = { for k, d in huaweicloud_waf_domain.prod : k => d.id }
}

output "hss_quota_ids" {
  description = "HSS 企业版配额 ID（每节点一席，status=normal / used_status=used）"
  value       = [for q in huaweicloud_hss_quota.prod : q.id]
}
