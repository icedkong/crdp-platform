# E22-T04 · RDS 8.0 生产实例 IaC（主备、跨区域备份与参数基线）

> 按 `AGENTS.md` 第 9 节：涉及华为云真实资源的部分**不在本地执行**。本目录是可评审的 IaC 与参数基线，
> `terraform apply` 由持云账号的运维在维护窗口执行（步骤与验证清单见 `docs/ops/rds-prod.md`）。

## 交付范围（对照 tasks.csv E22-T04 验收）

- **主备**：`huaweicloud_rds_instance` 两个可用区（`cn-north-4a/b`）+ `ha_replication_mode = "semisync"`，
  切换策略 `reliability`（可靠性优先）。切换验证 runbook 见 `docs/ops/rds-prod.md` §2。
- **跨区域备份开启**：`huaweicloud_rds_cross_region_backup_strategy`，目标 `cn-east-3`（上海一），
  类型 `all`（全量 + Binlog）、保留 30 天。**前置**：北京四 → 上海一的跨区域备份需先提交华为云工单开通
  （tech-plan §2 核实记录），开通后再 `apply` 本目录。
- **参数基线**：`params-baseline.json` 是唯一事实源（实例级 time_zone / lower_case_table_names 除外，
  它们在 RDS 创建时以参数固定）。`main.tf` 用 `jsondecode(file(...))` 从同一份 JSON 取值，
  `scripts/check_rds_baseline.py` 在 CI 里校验两者不漂移、参数取值符合规格出处。

## 文件

| 文件 | 作用 |
|---|---|
| `params-baseline.json` | 参数基线与实例 / 备份 / 跨区域配置（唯一事实源，校验脚本与文档表都对照它） |
| `versions.tf` | terraform ≥ 1.5、huaweicloud provider ≥ 1.56（`rds_cross_region_backup_strategy` 自 1.56 提供） |
| `variables.tf` | 网络与规格输入（VPC / 子网 / 安全组沿用 E01-T06，规格购买前以控制台可购为准） |
| `main.tf` | 参数组、主备实例、自动备份策略、跨区域备份策略 |
| `outputs.tf` | 实例 ID、私网地址、参数组 ID（供验证清单与 E22-T06/T07 恢复演练引用） |
| `terraform.tfvars.example` | 变量样例；真实值经环境变量或 `terraform.tfvars`（不入库）注入 |

## 使用（持云账号的运维执行）

```bash
cd iac/rds-prod
terraform init
terraform validate
# 密码经 DEW 凭据管理取值后以 TF_VAR_admin_password 注入，不落盘、不进 shell 历史
export TF_VAR_admin_password='...'
terraform plan -var-file=terraform.tfvars   # 人工核对：主备两 AZ、semisync、备份 30 天、跨区域目标 cn-east-3
terraform apply
```

验收所需的**主备切换验证**与**跨区域备份开启后核验**按 `docs/ops/rds-prod.md` 的清单逐项执行并留痕。
