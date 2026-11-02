# RDS 8.0 生产实例运维手册（主备、跨区域备份与参数基线）

> 任务 E22-T04。按 `AGENTS.md` 第 9 节，涉及华为云真实资源的操作**不在代码仓库内执行**：
> 本手册与 `iac/rds-prod/` 是可评审的交付物，实际开通、切换与核验由持云账号的运维执行并按 §5 留痕。
>
> 依据：`docs/requirements/tech-plan.md` §1（北京四 / RDS for MySQL 8.0 已确认）、§2（RDS 能力核实记录：
> 自动备份不可关闭、Binlog 每 5 分钟上传、跨区域备份目标区域与工单要求、加密备份不支持跨区域）、
> §14（备份、容灾与可用性）；`docs/open-questions.md` OQ-55（binlog 与触发器）；NFR-AVL-002/004（RPO ≤ 5 分钟、
> RTO ≤ 4 小时、备份保留 30 天）。

## 1. 参数基线

事实源：`iac/rds-prod/params-baseline.json`（CI 由 `scripts/check_rds_baseline.py` 校验）。下表与其一致，改动时两处同步。

### 实例级（创建时固定，ForceNew，不可后改）

| 参数 | 值 | 说明 |
|---|---|---|
| 引擎 / 版本 | MySQL 8.0 | tech-plan §1 已确认 8.0 |
| 主备形态 | 跨可用区（`cn-north-4a` + `cn-north-4b`） | tech-plan §3：主备实例跨可用区，RPO 约 0、分钟级自动切换 |
| 复制模式 | `semisync`（半同步） | 支撑 §14「RPO 约 0」；半同步超时后 RDS 自动降级异步并告警 |
| 切换策略 | `reliability`（可靠性优先） | 医疗数据优先不丢；代价是切换可能略慢（等待事务对齐） |
| 时区 | `UTC+08:00`（北京时间） | OQ-56 ⑤：与 JDBC `connectionTimeZone=Asia/Shanghai` 对齐，created_at 库内钟面与应用一致 |
| 表名大小写 | `lower_case_table_names = 1` | RDS 默认值显式固化；开发容器（Linux）默认 0，本仓库表名全小写，两边行为一致 |
| 传输加密 | `ssl_enable = true` | 内网也走 SSL；应用连接串需 `useSSL=true`（见 §3.4） |
| 端口 | 3306 | 白名单只放行 CCE 应用节点 |

### 参数模板（参数组，可热更）

| 参数 | 值 | 出处 |
|---|---|---|
| `character_set_server` | `utf8mb4` | 与全部 Flyway 脚本、开发容器 `docker-compose.yml` 一致 |
| `collation_server` | `utf8mb4_0900_ai_ci` | 同上，MySQL 8.0 默认排序规则 |
| `log_bin_trust_function_creators` | `ON` | OQ-55 ①：binlog 常开时非 SUPER 迁移账号建审计只读触发器（V17/V26）需要它，否则 MySQL 1419 |
| `long_query_time` | `1` | 慢日志统一 1 秒口径，供 E22-T03 的 CES 告警与 LTS 检索 |

**刻意不设置的参数**：`max_connections`（RDS 按规格内存给默认值，应用侧 Hikari 按租户分池、总量远低于默认上限，
不收紧也不放大）；`binlog_format` / Binlog 上传节奏（RDS 托管，自动备份不可关闭，tech-plan §2 核实记录）；
复制相关参数（`server_id`、`gtid_mode` 等，RDS 托管，校验脚本禁止它们进基线）。

## 2. 验收一：主备切换验证

目的：证明主备关系健康、计划内切换可预期、应用可自动恢复。生产首次开通后执行一次，之后每年恢复演练（E22-T07）复测。

### 2.1 前置

1. `terraform output instance_id / nodes / private_ips` 记录切换前快照（哪个 AZ 是 master）。
2. 停写窗口：选维护窗口（默认北京时间 22:00–23:00），通知租户停随访录入（写路径全部暂停）。
3. 应用副本缩到 1 或保持 2 副本（Hikari 连接池会自动重连，无需改配置）。

### 2.2 执行（控制台：实例 → 实例操作 → 主备切换）

按华为云用户指南「管理主备切换」执行计划内切换。切换期间读写内网地址（浮动 IP）**保持不变**，
会出现秒级连接闪断，Hikari 自动重连即可；`semisync` 下切换不丢已提交事务。

### 2.3 验证清单（逐项勾选并记录到 §5）

- [ ] 切换后 `nodes` 角色互换（原 master AZ 变 slave），实例状态「正常」。
- [ ] 应用 `/actuator/health` 在 1 分钟内回到 `UP`（记录实际耗时 = 本次切换 RTO）。
- [ ] 停写窗口内**无**数据丢失诉求：切换后对账停写前后 `aud_log` 链值（E08 验链作业当日结果为 SUCCESS），
      或人工跑一次验链 SQL，确认链未断（RPO = 0）。
- [ ] 冒烟：医院端登录 → 写一条随访记录（虚构测试数据）→ 查询可见；明文查看接口正常。
- [ ] 备份状态：控制台「备份管理」最近一次自动全量 + 增量备份仍在，切换不清理备份。

### 2.4 故障自动切换（说明，不主动演练）

单机 / 单可用区故障由 RDS 自动切换（可靠性优先策略）；验证手段是 E22-T03 的 CES 告警（实例状态、复制状态延迟）
与 §2.3 同一套恢复核对。`replication_status` 输出可纳入巡检。

## 3. 验收二：跨区域备份开启

### 3.1 前置：工单开通

北京四 → 上海一的跨区域备份需**先提交华为云工单**开通该能力（tech-plan §2 核实记录），工单通过后再执行下一步。

### 3.2 开启（IaC）

`iac/rds-prod/main.tf` 的 `huaweicloud_rds_cross_region_backup_strategy`：
`backup_type = all`（全量 + Binlog 增量）、`keep_days = 30`（与区域内一致）、`destination_region = cn-east-3`（上海一）。
执行 `terraform apply`（README §使用）。注意：**加密备份不支持跨区域**——敏感字段本就应用层加密（NFR-SEC-002），
该限制已由 tech-plan §14 确认接受。

### 3.3 验证清单

- [ ] 北京四控制台「跨区域备份管理」：策略显示目标区域上海一、类型全量 + 增量、保留 30 天。
- [ ] 上海一控制台（切换区域后）能在「备份管理」看到同步过来的备份文件。
- [ ] 等待 ≥ 1 个备份窗口（次日 02:00–03:00 之后）复核，确认全量与 Binlog 都在持续同步。
- [ ] 记录本次观测到的同步延迟（异步复制，官方不保证及时性、无 SLA——这是 §14 已确认接受的风险口径，
      区域级 RPO 以 E22-T07 演练实测为准）。

### 3.4 应用侧配套（部署时核对，随 E22-T05）

- `CRDP_PLATFORM_JDBC_URL` 指向 `terraform output private_dns_names`（优先域名），带
  `useSSL=true&connectionTimeZone=Asia/Shanghai`；平台库与全部租户库同一实例。
- root / 迁移 / 应用账号口令全部经 DEW 凭据管理注入，不入库、不进文档。

## 4. 与后续任务的衔接

| 任务 | 依赖本任务的什么 |
|---|---|
| E22-T06 单院恢复 | 30 天自动备份 + Binlog 支持实例级 PITR；库表级恢复不可用（生成列表），按「恢复到新实例 → 导出院库 → 导入」流程 |
| E22-T07 跨区域恢复演练 | 上海一的跨区域备份 + `iac/rds-prod` IaC 重建（RTO 4 小时目标），实测 RPO/RTO 记录取代 §3.3 的观测值 |
| E22-T03 监控 | `replication_status`、慢日志（`long_query_time=1`）、备份失败告警接入 CES / LTS |

## 5. 验证留痕模板

每次执行 §2 / §3 清单后填写（存档到运维 wiki，本仓库不留执行记录）：

| 项 | 值 |
|---|---|
| 日期 / 执行人 | |
| 实例 ID / 参数组 ID | |
| 切换前 master AZ → 切换后 master AZ | |
| 停写窗口（起止） | |
| 实测恢复耗时（health UP）/ 数据对账结果 | |
| 跨区域备份首个同步文件时间 / 观测同步延迟 | |
| 未通过项与处置 | |
