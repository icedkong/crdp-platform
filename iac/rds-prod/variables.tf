# 网络 / 规格输入。VPC、子网、安全组沿用 E01-T06 的开发与测试 IaC 同一套北京四网络；
# 该 IaC 尚未入库（E01-T06 在控制台交付），因此这里以变量注入 ID，不重复建模。

variable "region" {
  description = "区域。基线固定华北-北京四（tech-plan §1），一般不需要改"
  type        = string
  default     = "cn-north-4"
}

variable "instance_name" {
  description = "RDS 实例名（同类型内唯一）"
  type        = string
  default     = "crdp-prod-mysql80"
}

variable "flavor" {
  description = "规格代码。购买前以控制台可购规格为准（tech-plan §1：其余服务购买前确认规格）"
  type        = string
  default     = "rds.mysql.x1.large.2.ha"
}

variable "volume_type" {
  description = "磁盘类型。主备实例用云盘（ESSD），跨可用区高可用"
  type        = string
  default     = "ESSD"
}

variable "volume_size_gb" {
  description = "磁盘容量（GB），40–4000 且为 10 的倍数"
  type        = number
  default     = 100
}

variable "vpc_id" {
  description = "VPC ID（E01-T06 北京四网络）"
  type        = string
}

variable "subnet_id" {
  description = "子网 ID（E01-T06 北京四网络）"
  type        = string
}

variable "security_group_id" {
  description = "安全组 ID：只放行 CCE 应用节点到实例端口（默认 3306）"
  type        = string
}

variable "admin_password" {
  description = "root 管理口令。经 DEW 凭据管理取值后以 TF_VAR_admin_password 注入，不落盘、不进仓库"
  type        = string
  sensitive   = true
}

variable "maintain_begin" {
  description = "维护窗口开始（北京时间），主备切换等运维动作默认落在此窗口"
  type        = string
  default     = "22:00"
}

variable "maintain_end" {
  description = "维护窗口结束（北京时间）"
  type        = string
  default     = "23:00"
}

variable "charging_mode" {
  description = "付费模式：prePaid（包周期）/ postPaid（按需）。生产默认包周期，避免欠费导致实例与备份被清（tech-plan §14 WORM 风险条目）"
  type        = string
  default     = "prePaid"
}

variable "period_unit" {
  description = "包周期单位（month / year）"
  type        = string
  default     = "month"
}

variable "period" {
  description = "包周期时长"
  type        = number
  default     = 1
}

variable "auto_renew" {
  description = "自动续费（true / false）。生产开启"
  type        = string
  default     = "true"
}

variable "destination_project_id" {
  description = "跨区域备份目标区域（上海一）的项目 ID。需先提交工单开通北京四 → 上海一的跨区域备份能力"
  type        = string
}

variable "tags" {
  description = "实例标签"
  type        = map(string)
  default = {
    project = "crdp"
    env     = "prod"
    task    = "E22-T04"
  }
}
