terraform {
  required_version = ">= 1.5.0"

  required_providers {
    huaweicloud = {
      source  = "huaweicloud/huaweicloud"
      # hss_host_protection 自 1.63 提供、hss_quota 自 1.65 提供
      # （provider CHANGELOG GH-4474 / GH-4881）
      version = ">= 1.65.0, < 2.0.0"
    }
  }
}

provider "huaweicloud" {
  region = var.region
}
