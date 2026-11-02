terraform {
  required_version = ">= 1.5.0"

  required_providers {
    huaweicloud = {
      source  = "huaweicloud/huaweicloud"
      # rds_cross_region_backup_strategy 自 1.56 起提供（provider 提交 #3417，2023-09）
      version = ">= 1.56.0, < 2.0.0"
    }
  }
}

provider "huaweicloud" {
  region = var.region
}
