# 单院恢复 runbook（单租户库恢复到指定时间点）

> 任务 E22-T06。验收：**可把单个医院恢复到指定时间点**。
> 按 `AGENTS.md` 第 9 节，生产恢复涉及华为云真实资源（RDS 时间点恢复），**不在代码仓库内执行**：
> 本手册与 `scripts/restore_tenant.sh` 是可评审的交付物，实际恢复由持云账号的运维按本手册执行并按 §7 留痕。
>
> 依据：`docs/requirements/tech-plan.md` §14「单院恢复」（实例级时间点恢复到新实例 → 导出该院库 →
> 导入新库 → 切换数据源注册表）与 §2（RDS 能力核实记录：Binlog 每 5 分钟上传，区域内 RPO ≤ 5 分钟；
> 库表级时间点恢复**不支持含生成列的表**，本方案在热门 JSON 字段与 pt_implant 唯一约束上用了生成列，
> 因此不走库表级恢复）。备份与实例来自 E22-T04（`iac/rds-prod/`，自动备份保留 30 天 + Binlog 增量）。
> 跨区域（整个区域不可用）的恢复是 E22-T07 的演练范围，本手册只覆盖区域内单院恢复。

## 1. 适用场景与原理

单个医院需要回滚（误操作、逻辑损坏、数据被污染）而**不影响其他医院**。平台库与全部租户库同在
一个 RDS 实例上，实例整体回退不可行；采用：

```
RDS 时间点恢复 → 新的临时实例（最近全量备份 + 回放 Binlog 到恢复点）
      ↓ mysqldump 单个租户库（剥离 DEFINER）
生产实例新建 t_<code>_rst<时间戳> 库并导入、授权租户账号
      ↓ UPDATE plt_tenant_resource（db_name / jdbc_url）
启用租户 + 滚动重启应用 → 连接池按注册表重建 → 该院回到恢复点
```

旧库 `t_<code>` 原样保留，观察 ≥7 天后按 §6 清理；期间可随时按 §5 回滚。

## 2. 前置条件

- [ ] RDS 实例与备份策略已按 E22-T04 落地（`terraform -chdir=iac/rds-prod output` 记录 `instance_id`、
      `private_dns_names`；自动备份保留 30 天、Binlog 增量在传）。
- [ ] 恢复点在自动备份保留窗口内（30 天，NFR-AVL-004；Binlog 粒度使实际 RPO ≤ 5 分钟）。
- [ ] 与医院确认恢复点（北京时间，ISO-8601 带时区，如 `2027-07-13T02:30:00+08:00`）与影响：
      **恢复点之后该院的全部数据将不可见**（保留在旧库中，必要时可人工找回）。
- [ ] 恢复窗口内：正式租户先在平台端**停用**（吊销在线会话、阻断登录写入；停用 / 启用自带平台审计）。
      试用租户（TRIAL，尚无真实数据）可直接恢复。脚本在 rds 模式会校验状态并拒绝未停用的正式租户。
- [ ] 运维机具备：mysql / mysqldump 客户端 8.0、python3、本仓库脚本
      （`scripts/restore_tenant.sh`）；目标实例使用迁移 / 特权账号，口令经环境变量注入，不进命令行。
- [ ] 报告单等文件在 OBS（版本控制 + 跨区域复制），与数据库恢复时间点对齐由 E22-T07 演练覆盖；
      本手册不搬移对象文件。

## 3. 恢复流程

脚本五阶段与下文一一对应；任何阶段失败都会中止且**不做注册表切换**，旧库不受影响，可直接排障重跑
（新库带时间戳，不冲突）。

### 3.1 PITR：恢复到新实例（控制台 / hcloud，云账号运维执行）

按华为云用户指南「恢复到指定时间点」，以**恢复到新实例**方式从生产实例创建临时实例：
规格可用最小可用的同版本（MySQL 8.0）单机、同 VPC 内网、放到与生产不同的可用区更稳妥；
恢复点即 §2 确认的时间。等待实例状态「正常」（全量 + Binlog 回放，视数据量分钟到小时级）。

### 3.2 preflight：核对再动手

```bash
export CRDP_RESTORE_TARGET_PASSWORD='<迁移账号口令>'
export CRDP_RESTORE_SOURCE_PASSWORD='<临时实例账号口令>'
scripts/restore_tenant.sh \
  --tenant hospa --restore-point '2027-07-13T02:30:00+08:00' \
  --mode rds \
  --source-host <临时实例地址> --source-port 3306 --source-user <账号> \
  --target-host <生产实例地址> --target-port 3306 --target-user <迁移账号> \
  --platform-db crdp_platform
```

不加 `--yes` 只输出计划（模式 / 租户 / 状态 / `t_hospa → t_hospa_rst*` / 恢复点 / 工作目录），
并完成全部校验：租户编码白名单、恢复点格式与窗口、目标连通、租户已登记且状态可恢复
（rds 模式要求 DISABLED 或 TRIAL）、目标新库不重名、`jdbc_url` 可自动重写。确认无误后加 `--yes` 执行。

### 3.3 export → import：导出、导入与比对

- 从临时实例 `mysqldump` 租户库（`--single-transaction`，含触发器 / 事件 / 存储对象，hex-blob），
  **剥离 `DEFINER=`** 后落工作目录（sha256 记入 `rollback.env`）——生产迁移账号无 SUPER，
  保留 DEFINER 的转储导入会因权限失败；剥离后以导入账号为定义者，租户库内触发器（审计只读触发器）
  功能不受影响。
- 生产实例 `CREATE DATABASE t_<code>_rst<时间戳>`（utf8mb4 / utf8mb4_0900_ai_ci，与开通编排一致）→
  导入 → `GRANT ALL PRIVILEGES ON 新库.* TO 租户账号@'%'`（账号与口令不变，只加新库授权）。
- 逐表比对源与新库行数（`source-counts.tsv` vs `new-counts.tsv`），不一致即中止且未切换。
- 可用 `--stop-after import` 先导入、人工抽查后再切换。

### 3.4 switch：切换数据源注册表

写 `rollback.env`（旧 / 新库名、旧 `jdbc_url`、恢复点、dump sha256）后，
`UPDATE plt_tenant_resource SET db_name=新库, jdbc_url=REPLACE(jdbc_url, '/旧库?', '/新库?')`，
命中行数必须为 1，回读复核。平台库是控制面，其他医院的路由不受影响。

### 3.5 生效：启用 + 滚动重启

连接池在应用启动时按 `plt_tenant_resource` 构建（`DataSourceConfig` → `TenantSchemaMigrator.loadTenants`），
注册表行变更不热生效，因此：

1. 平台端**启用**该租户（若 §2 已停用；启用→重启之间的登录会报错，属预期，维持窗口到重启完成）；
2. 滚动重启应用（`kubectl rollout restart` CCE 工作负载，E22-T05 的发布流程）；
3. 启动时 Flyway 对新库 `flyway_schema_history` 校验通过后照常迁移（恢复点早于最新版本时自动补齐
   后续版本，属预期：只回数据不回表结构版本）。

## 4. 验证清单（逐项勾选并记录到 §7）

- [ ] 应用 `/actuator/health` UP；重启完成时间记为本次 RTO 组成部分。
- [ ] 医院端用该院账号登录成功；其他医院（另选一家）登录不受影响。
- [ ] 恢复点核对：抽查恢复点**之前**写入的患者 / 随访记录存在、行数与恢复点快照一致；
      恢复点**之后**写入的数据在新库不可见（脚本行数比对 + `--source-db` 快照已保证，人工抽查复核）。
- [ ] 审计链完整：租户库 `aud_log` 链值在恢复点处连续（E08 验链作业当日 SUCCESS，或人工验链 SQL）；
      审计只读触发器仍生效（对 `aud_log` 的 UPDATE 被拒绝）。
- [ ] 明文查看、报告单预览水印、导出等敏感路径冒烟（用虚构测试数据）。
- [ ] 定时作业（随访计划生成等）对该租户恢复执行，无「停用期间未执行」异常堆积（停用补偿规则见
      tech-plan §13）。

## 5. 回滚

任何一步（含验证不通过）都可回到旧库：注册表行改回即可，数据从未被改动。

```bash
scripts/restore_tenant.sh --rollback <工作目录>/rollback.env --yes \
  --target-host <生产实例地址> --target-port 3306 --target-user <迁移账号>   # 口令仍走环境变量
```

脚本按 `rollback.env` 原值恢复 `db_name` / `jdbc_url`（带幂等保护：当前必须指向新库才执行），
随后**再次滚动重启应用**（§3.5）。恢复点之后又产生的数据如需抢救，从新库（此时仍未删除）人工导出。

## 6. 清理（验证通过并观察 ≥7 天后，人工执行）

以下对象脚本一律不自动删除，由运维核对 `rollback.env` 后手工清理并在 §7 记录：

- [ ] 旧库 `t_<code>`（必要时先归档「恢复点之后」的数据片段）；
- [ ] 恢复用新库如已回滚则同样处理；
- [ ] 华为云 PITR 临时实例（控制台删除，避免继续计费）；
- [ ] 运维机上的工作目录（`restore-work/`，含转储文件；转储含患者数据，不得随意外发，用后销毁）。

## 7. 留痕模板（存档到运维 wiki，本仓库不留执行记录）

| 项 | 值 |
|---|---|
| 日期 / 执行人 / 复核人 | |
| 医院（租户编码）/ 事故原因 | |
| 恢复点（北京时间）/ 与医院确认人 | |
| 临时实例 ID / 起止时间 | |
| 新库 / 旧库 / dump sha256 | |
| 停用 → 切换 → 重启完成时间（实测 RTO）/ 行数比对结果 | |
| §4 验证清单结果（未通过项与处置） | |
| 清理日期（旧库 / 临时实例 / 工作目录） | |

## 8. 本地演练（CI）

`scripts/check_restore_script.py --drill` 在 docker compose 的 MySQL 上以虚构租户 `rstcheck`
走完整流程（快照库模拟 PITR 临时实例、恢复点后写入标记行验证时间点生效、切换 / 回滚 / 清理），
CI 与本地验证命令一致：先 `docker compose up -d --wait` 并启动一次应用完成租户库迁移，再执行
`python3 scripts/check_restore_script.py --drill`。生产 rds 模式与演练 local-drill 模式共用同一
套导出 / 导入 / 切换代码路径，仅「恢复源」不同（PITR 临时实例 vs 本地快照库）。
