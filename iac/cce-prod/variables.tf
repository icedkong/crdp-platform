# 区域与网络沿用 E01-T06（北京四 VPC / 子网，尚未落地时先由运维在 tfvars 里给值；
# 与 iac/rds-prod 同一套网络，真实值不入库）。
variable "region" {
  description = "华为云区域（tech-plan §1：华北-北京四）"
  type        = string
  default     = "cn-north-4"
}

variable "vpc_id" {
  description = "E01-T06 建的 VPC ID"
  type        = string
}

variable "subnet_id" {
  description = "应用子网 ID（需配置 DNS，见 provider cce_cluster 文档）"
  type        = string
}

variable "elb_subnet_id" {
  description = "ELB 所在 IPv4 子网 ID（同一 VPC）"
  type        = string
}

variable "cluster_name" {
  description = "CCE 集群名"
  type        = string
  default     = "crdp-prod"
}

variable "cluster_version" {
  description = "CCE 集群版本（留空用当前默认；升级走 cce_cluster_upgrade，不在此改）"
  type        = string
  default     = ""
}

variable "cluster_flavor_id" {
  description = "集群规格（cce.s1.small 支撑 50 节点，首期 2 节点足够；购买前以控制台可购为准）"
  type        = string
  default     = "cce.s1.small"
}

variable "service_network_cidr" {
  description = "Service 网段（不得与 VPC / 容器网段冲突）"
  type        = string
  default     = "10.248.0.0/16"
}

variable "node_flavor_id" {
  description = "工作节点规格（购买前以控制台可购为准）"
  type        = string
  default     = "c7.xlarge.2"
}

variable "node_key_pair" {
  description = "节点登录密钥对名（基线禁用密码登录；私钥由运维保管，不入库）"
  type        = string
}

variable "node_root_volume_type" {
  type    = string
  default = "SSD"
}

variable "node_root_volume_gb" {
  type    = number
  default = 100
}

variable "node_data_volume_type" {
  type    = string
  default = "SSD"
}

variable "node_data_volume_gb" {
  type    = number
  default = 100
}

# 入口域名与证书：*.平台域名 通配符证书（tech-plan §3），由运维在 SCM 购买 / 导入后
# 导出 PEM，经环境变量注入（与 rds-prod 的 admin_password 同口径：不落盘、不进 shell 历史）。
variable "tls_certificate_pem" {
  description = "通配符证书 PEM（含中间证书）"
  type        = string
  sensitive   = true
}

variable "tls_private_key_pem" {
  description = "通配符证书私钥 PEM"
  type        = string
  sensitive   = true
}

variable "hospital_wildcard_domain" {
  description = "医院端通配符域名（如 *.crdp.example.com，覆盖 {租户编码}.平台域名）"
  type        = string
}

variable "platform_domain" {
  description = "平台端独立域名（NFR-SEC-007：平台后台独立域名与独立登录）"
  type        = string
}

variable "elb_bandwidth_size" {
  description = "ELB 公网带宽（Mbit/s）"
  type        = number
  default     = 100
}

variable "waf_certificate_name" {
  description = "WAF 证书名（与 ELB 同一张通配符证书）"
  type        = string
  default     = "crdp-prod-wildcard"
}

variable "hss_period_unit" {
  description = "HSS 配额计费周期单位（month / year）"
  type        = string
  default     = "month"
}

variable "hss_period" {
  description = "HSS 配额购买时长（月：1–9）"
  type        = number
  default     = 1
}

variable "elb_charging_mode" {
  description = "ELB 计费模式（postPaid / prePaid）"
  type        = string
  default     = "postPaid"
}

variable "tags" {
  description = "统一标签"
  type        = map(string)
  default = {
    project = "crdp"
    env     = "prod"
  }
}
