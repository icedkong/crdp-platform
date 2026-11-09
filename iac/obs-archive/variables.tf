# E22-T08：审计归档 OBS WORM 桶。区域与桶形态锁定北京四 / 单可用区（worm-baseline.json 为唯一事实源）。

variable "region" {
  description = "区域（默认北京四，与 worm-baseline.json 一致；只写一份，不做异地副本）"
  type        = string
  default     = "cn-north-4"
}

variable "bucket_name" {
  description = "审计归档桶名（全局唯一；与业务桶分离，命名建议 crdp-audit-archive-{区域后缀}）"
  type        = string
}

variable "lifecycle_prefix" {
  description = "生命周期规则覆盖的对象键前缀（归档对象统一在 audit/ 前缀下）"
  type        = string
  default     = "audit/"
}
