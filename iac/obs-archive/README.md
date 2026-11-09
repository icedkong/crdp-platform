# E22-T08 · 审计归档 OBS WORM 桶 IaC（单可用区、桶级 WORM、生命周期分层）

> 按 `AGENTS.md` 第 9 节：涉及华为云真实资源的部分**不在本地执行**。本目录是可评审的 IaC 与 WORM 基线，
> `terraform apply` 与桶级 WORM 开启由持云账号的运维按 `docs/ops/audit-worm-archive.md` §2 执行（顺序不可换：
> 先建桶、再开 WORM、最后配 180 天转深度归档）。

## 交付范围（对照 tasks.csv E22-T08 验收「归档对象在保留期内不可删改」）

- **桶形态**：`huaweicloud_obs_bucket` 单独的单可用区桶（`multi_az = false`，北京四）、STANDARD、私有读写、
  版本控制（WORM 开启后自动启用且不可停用，IaC 显式声明与最终态一致）。只写北京四一份，
  **不配置跨区域复制**（tech-plan §14 已确认；WORM 桶也不支持作为跨区域复制源）。
- **生命周期**：`audit/` 前缀 30 天 → 归档存储（COLD）在 IaC 内；**不配置任何 expiration**
  （归档对象到期后默认不自动删除）。
- **provider 能力边界（OQ-123，运维经控制台 / SDK 落实，见 runbook §2）**：
  1. 桶级 WORM（对象锁定，合规模式默认保留 15 年）——terraform-provider-huaweicloud 无对应资源
     （2026-11 核实），控制台或 `esdk-obs-java setObjectLockConfiguration` 开启，开启后不可关闭；
  2. 180 天 → 深度归档（DEEP_ARCHIVE）——provider transition 仅支持 WARM/COLD，
     控制台或 OBS API `PutBucketLifecycle` 在同一规则上追加。
- **基线单源**：`worm-baseline.json` 是唯一事实源（桶形态、保留策略、生命周期、对象键前缀），
  `main.tf` 用 `jsondecode(file(...))` 从同一份 JSON 取值，`scripts/check_worm_baseline.py`
  在 CI 里校验两者与 `application.yml` / 适配器 / runbook 不漂移。
- **应用侧**（不在本目录）：`crdp.audit.archive.storage.type=obs` 装配 `ObsAuditArchiveStorage`
  （单次 PUT + 对象级 COMPLIANCE 保留 = 归档日 + 15 年，epoch 毫秒）；默认 `none` 只做分区维护。

## 文件

| 文件 | 作用 |
|---|---|
| `worm-baseline.json` | WORM 桶基线（唯一事实源，校验脚本与 runbook §1 表都对照它） |
| `versions.tf` | terraform ≥ 1.5、huaweicloud provider ≥ 1.56（与 rds-prod / cce-prod 同窗口） |
| `variables.tf` | 桶名（全局唯一）与生命周期前缀；区域锁定北京四 |
| `main.tf` | 归档桶（单可用区、版本控制、30 天转 COLD、无 expiration、无复制）与两处人工步骤说明 |
| `outputs.tf` | 桶名（接 `CRDP_OBS_ARCHIVE_BUCKET`）与待运维落实的两步 |
| `terraform.tfvars.example` | 变量样例；真实值经 `terraform.tfvars`（不入库）注入 |

## 使用（持云账号的运维执行）

```bash
cd iac/obs-archive
terraform init
terraform validate
terraform plan -var-file=terraform.tfvars   # 人工核对：cn-north-4、multi_az=false、30 天 COLD、无 expiration
terraform apply
# 紧接着（顺序不可换）：
#   控制台 / SDK 开启桶级 WORM：合规模式、默认保留 15 年（开启后不可关闭）
#   控制台 / API 追加 180 天 → DEEP_ARCHIVE 转换
# 然后按 docs/ops/audit-worm-archive.md §3 接入应用、§4 验证、§7 留痕
```
