terraform {
  required_version = ">= 1.5.0"

  required_providers {
    huaweicloud = {
      source  = "huaweicloud/huaweicloud"
      # obs_bucket 的 multi_az / versioning / lifecycle_rule 在 1.56 前已具备；
      # 与 iac/rds-prod、iac/cce-prod 统一版本窗口，便于同一套 provider 缓存。
      version = ">= 1.56.0, < 2.0.0"
    }
  }
}

provider "huaweicloud" {
  region = var.region
}
