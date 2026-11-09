# 审计归档 OBS WORM 桶运维手册（保留策略开通、验证与归档演练）

> 任务 E22-T08（依赖 E08-T03）。按 `AGENTS.md` 第 9 节，涉及华为云真实资源的操作**不在代码仓库内执行**：
> 本手册与 `iac/obs-archive/` 是可评审的交付物，实际开通、验证与演练由持云账号的运维执行并按 §7 留痕。
>
> 依据：`docs/requirements/platform-spec.md` §9 PLT-AUD-008/010/011（防篡改、长期留存与归档、日志只读）、
> `docs/requirements/tech-plan.md` §14「审计 · 归档桶与保留」（单可用区、桶级 WORM 合规 15 年、对象级 15 年、
> 30 天转归档 / 180 天转深度归档、保留期只能延长、到期不自动删除、只写北京四一份）、ADR 0005（归档设计与端口）、
> OQ-123（terraform provider 无 WORM / DEEP_ARCHIVE 资源的暂行做法）。
>
> 任务验收：**归档对象在保留期内不可删改**。本地（CI）证据见 §8；生产证据按 §4 逐项留痕。

## 1. 基线（唯一事实源）

`iac/obs-archive/worm-baseline.json`，CI 由 `scripts/check_worm_baseline.py` 防漂移。要点：

| 项 | 值 | 出处 |
|---|---|---|
| 区域 / 副本 | 北京四（`cn-north-4`），只写一份，无跨区域复制 | tech-plan §14（已确认不做异地 WORM 副本） |
| 桶形态 | 单独的**单可用区**桶，STANDARD、私有读写 | tech-plan §14；归档 / 深度归档类别仅支持单 AZ |
| 桶级 WORM | 合规模式（COMPLIANCE）默认保留 **15 年** | tech-plan §14；OBS 仅支持合规模式，开启后不可关闭 |
| 对象级保留 | 每次写入设 = 归档日 + 15 年 | `ObsAuditArchiveStorage`（E22-T08），保留期只能延长 |
| 版本控制 | 开启 WORM 自动启用，不可停用 | OBS 对象锁定语义（IaC 显式声明与最终态一致） |
| 生命周期 | 30 天 → 归档存储（COLD，IaC）；180 天 → 深度归档（DEEP_ARCHIVE，控制台 / API） | tech-plan §14；provider 能力边界见 OQ-123 |
| 自动删除 | 无任何 expiration：到期后默认不自动删除 | tech-plan §14 |
| 在线保留 | 12 个月（RDS 内），到期分区导出 JSONL.gz + 清单后 DROP PARTITION | E08-T03 / ADR 0005 |

对象键：租户 `audit/tenant/{租户编码}/{表}/{yyyy-MM}.jsonl.gz` 与 `.manifest.json`，平台库 `audit/platform/…`
（同分区重跑同键，WORM 下不产生第二份对象）。

## 2. 开通步骤（持云账号运维执行，顺序不可换）

```bash
cd iac/obs-archive
terraform init && terraform validate
terraform plan -var-file=terraform.tfvars   # 人工核对：cn-north-4、multi_az=false、lifecycle 30 天 COLD、无 expiration
terraform apply
```

1. **terraform apply 建桶**（单可用区、私有、版本控制声明、30 天转 COLD 生命周期）。
2. **开启桶级 WORM**（terraform provider 无此资源，OQ-123）：OBS 控制台 → 对应桶 →「对象锁定 / WORM」
   → 开启并设置桶级默认保留策略：**合规模式、15 年**。或经 SDK（`esdk-obs-java` 的
   `setObjectLockConfiguration`，`DefaultRetention(mode=COMPLIANCE, years=15)`）。
   注意：**开启后不可关闭**；开启即自动启用版本控制；obsutil 与 OBS Browser+ **不能**配置 WORM
   （华为云对象锁定用户手册），只能控制台或 API/SDK。
3. **追加 180 天转深度归档**：控制台桶「生命周期规则」在 `audit-archive-tiering` 规则（IaC 创建）上
   追加一条转换：**180 天 → 深度归档存储**；或经 OBS API `PutBucketLifecycle`。不得引入任何「定时删除」。
4. **开通核验**（控制台对象锁定页与生命周期页截图留痕）：合规模式、默认保留 15 年、版本控制已开启、
   生命周期含 30 天 COLD 与 180 天 DEEP_ARCHIVE、无删除规则。

## 3. 应用接入（生产配置）

前置：§2 全部完成（尤其是桶级 WORM 已开启——否则对象上传后不受保护）。

```bash
# 归档桶专用子账号（只授予该桶读写；见 §6 账号分离），AK/SK 经环境变量注入，不落盘不进仓库
export CRDP_OBS_ARCHIVE_ENDPOINT='obs.cn-north-4.myhuaweicloud.com'
export CRDP_OBS_ARCHIVE_AK='...'
export CRDP_OBS_ARCHIVE_SK='...'
export CRDP_OBS_ARCHIVE_BUCKET='<terraform output audit_archive_bucket>'
# application-prod.yml（或部署参数）：crdp.audit.archive.storage.type=obs
```

- `type=obs` 必填项缺失时启动即失败（fail-fast）；`type=none`（默认）作业只做分区维护、不删在线分区
  （ADR 0005 失败安全口径：桶没配好绝不会删在线数据）。
- 归档作业每日 04:10（北京时间）按租户扇出 + 平台库（E08-T03 `AuditArchiveService`）；对象写入 =
  单次 PUT + 对象级 COMPLIANCE 保留（读回校验、登记 `aud_archive`、DROP PARTITION 的顺序见 ADR 0005）。
- 作业失败 / 冲突（`aud-archive` 作业 FAILED）经 E22-T03 告警通道通知；分区保留在线，重跑自动续作。

## 4. 生产验证清单（首次归档后执行；验收「不可删改」的直接证据）

选一个已归档对象（`aud_archive.object_key`，如 `audit/tenant/demoa/aud_log/2026-01.jsonl.gz`）：

- [ ] **对象存在且内容一致**：`obsutil stat obs://<桶>/<对象键>` 存在；下载后
      `shasum -a 256`（解压后）与 `aud_archive.sha256` 一致；`.manifest.json` 的 rowCount / lastHash 与登记一致。
- [ ] **删除尝试必须失败**（保留期内任何账号都不可删——桶拥有者也不例外）：
      `obsutil rm obs://<桶>/<对象键>` 预期报错（对象处于合规保留期，`ObjectLocked` / 403 类错误）。
      **如果删除成功，立即停用归档作业（storage.type=none）并按安全事故上报。**
- [ ] **覆盖尝试必须失败**：向同键 `obsutil cp` 一个不同内容的文件，预期被拒（同上）。
- [ ] **保留期只能延长**：控制台 / SDK 将该对象保留截止日**延长**（如 +1 年）应成功；**缩短**应被拒。
- [ ] **对象级保留生效**：控制台对象详情的保留截止日 = `aud_archive.retention_until`（归档日 + 15 年）。
- [ ] **无自动删除**：桶生命周期规则不含任何「定时删除 / expiration」项（§2 第 3 步核验的复核）。
- [ ] **在线数据如约收缩**：归档成功后 `information_schema.PARTITIONS` 不再有该月分区，`aud_archive`
      有对应登记行（含空分区行数 0 的登记）。

## 5. 归档演练（每半年一次，与恢复演练 E22-T07 同窗口排期）

目的：完整走一遍「导出 → 上传 WORM → 读回校验 → 登记 → 删在线分区」链路，并复核 §4 清单。

1. 演练库（或新开通的测试租户）造数：补录一组到期月份的审计行（虚构数据，带「示例」标记）。
2. 生产桶路径外的**演练前缀**不可用（WORM 桶不分前缀放开保留），因此演练对象直接写
   `audit/tenant/{演练租户编码}/…` 前缀，演练后对象随 15 年保留留存在桶内（不删除、不清理——
   这正是 WORM 的语义；演练租户注销后对象仍在保留期内，可读不可删）。
3. 以 `runForDate(逻辑日)` 口径触发作业（生产等 04:10 定时；演练环境可直接调用
   `AuditArchiveService.runForDate`，与 CI 的 §8 同一入口）。
4. 逐项复核 §4 清单 + 验链作业（PLT-AUD-009）当日 SUCCESS（归档洞由 `aud_archive` 桥接，ADR 0005 §3）。

## 6. 账号与权限分离（ADR 0005 遗留项，随本任务落实）

- **OBS 侧**：归档桶使用**专用子账号**（只授予该桶读写 + `PutObjectRetention`；不给删除类权限——
  虽然合规保留本身已拒绝删除，权限仍按最小化收紧）。AK/SK 只经环境变量 / 凭据管理注入。
- **数据库侧**：归档作业使用的应用账号按 tech-plan §14「校验后由归档专用账号删除该分区」收敛——
  生产为作业账号的权限组只保留 DML + `ALTER`（分区维护 DDL：REORGANIZE / DROP PARTITION），
  与迁移账号（DDL）和报表账号（只读）分离；具体授权 SQL 由 DBA 按 `iac/rds-prod` 实例落实并留痕。
- **平台账号欠费风险**（tech-plan §14 风险行）：账号销户或欠费超期会删除 WORM 数据——
  余额告警与专人续费接入 E22-T03 的 CES 告警（阈值与责任人见监控 runbook），演练时复核告警在位。

## 7. 演练记录模板（存档到运维 wiki，本仓库不留执行记录）

```text
审计归档演练记录（E22-T08 / PLT-AUD-010）
日期 / 执行人 / 复核人：
逻辑日与归档对象（表 / 分区 / 对象键）：
§4 清单逐项结果（含删除尝试与覆盖尝试的报错原文截图）：
保留截止日（对象级）与登记 retention_until 一致性：
验链作业结果（PLT-AUD-009）：
账号分离与余额告警复核（§6）：
遗留问题与处置：
```

## 8. 本地 / CI 演练（不依赖云账号）

- `backend/crdp-server/src/test/java/com/crdp/server/AuditWormDrillMySqlIT.java`（门控
  `CRDP_IT_MYSQL_PORT`，CI 全量跑）：用执行 WORM 语义的本地桶替身在真实 MySQL 上跑完整归档作业，
  断言「不可删改」三层证据——端口 / 适配器无删除与覆盖语义（反射）、覆盖不同内容与缩短保留被拒、
  延长允许；桶外直改文件可被发现（读不回 / 摘要与 `aud_archive.sha256` 对不上）。
- `ObsAuditArchiveStorageTest`：离线锁定适配器请求形态（单次 PUT + `setObjectRetention`
  COMPLIANCE 到归档日 + 15 年的 epoch 毫秒、404 → null、错误统一转作业可感知异常）。
- `python3 scripts/check_worm_baseline.py`：基线 JSON ↔ IaC ↔ 应用配置 ↔ 本手册的静态防漂移。
