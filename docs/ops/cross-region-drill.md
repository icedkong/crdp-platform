# 跨区域恢复演练 runbook（实测 RPO / RTO 与报告存档）

> 任务 E22-T07（依赖 E22-T06）。验收：**演练报告存档，区域内 RPO ≤ 5 分钟**。
> 按 `AGENTS.md` 第 9 节，演练涉及华为云真实资源（上海一的跨区域备份、备用区域 IaC 重建、PITR
> 恢复新实例），**不在代码仓库内执行**：本手册与 `scripts/cross_region_drill.sh` 是可评审的交付物，
> 生产演练由持云账号的运维按 §3 执行，实测 RPO / RTO 记入脚本生成的报告并按 §5 存档。
>
> 依据：`docs/requirements/tech-plan.md` §14「RDS 跨区域备份（北京四 → 上海一）+ IaC 重建」（RTO
> 4 小时目标内重建恢复；跨区域备份异步、官方不保证及时性与 SLA，区域级 RPO 不保证、**以演练实测
> 记录**；区域内 RPO ≤ 5 分钟不变）与 §2（RDS 能力核实记录：Binlog 每 5 分钟上传、加密备份不支持
> 跨区域）、`docs/requirements/platform-spec.md` NFR-AVL-002（RPO ≤ 5 分钟、RTO ≤ 4 小时）、
> NFR-AVL-004（每半年恢复演练）；备份与跨区域策略来自 E22-T04（`iac/rds-prod/`，
> `params-baseline.json` 的 `cross_region_backup.destination_region = cn-east-3`），备用区域应用
> 重建入口来自 E22-T05（`iac/cce-prod/`）；单院（区域内）恢复见 E22-T06
> （`docs/ops/tenant-restore.md`），与本手册互补，不混用。

## 1. 场景与口径

**北京四（cn-north-4）整个区域不可用**：RDS 实例、CCE 集群、ELB / WAF 入口、OBS 业务桶同时失联。
恢复路径 = 在上海一（cn-east-3）用 IaC 重建应用与实例骨架，从**已同步到上海一的跨区域备份**做
时间点恢复，注册表与入口切到备用区域。两个 RPO 口径必须分开记录：

| 口径 | 机制 | 目标 | 实测方法 |
|---|---|---|---|
| 区域内 RPO | RDS 自动全量 + Binlog 增量（Binlog 每 5 分钟上传，官方机制不可自定义） | **≤ 5 分钟**（NFR-AVL-002，验收门） | 北京四控制台「备份管理」的**最近可恢复时间点**：宣告时刻 − 该时间点 |
| 跨区域 RPO | 跨区域备份异步复制（全量 + Binlog 同步到上海一，官方不保证及时性、无 SLA） | 无 SLA，**仅实测记录**（tech-plan §14 已确认接受） | 上海一控制台（切换区域）「备份管理」的最近可恢复时间点：宣告时刻 − 该时间点 |

区域级 RPO 不设验收门：跨区域副本滞后多少就记录多少，数据丢失量 = 跨区域恢复点之后的写入
（演练用标记行核对其确实不在恢复后的库中）。RTO 口径 = **宣告时刻 → 验证完成（服务恢复）**，
目标 ≤ 4 小时（NFR-AVL-002）。区域内 RPO 超过 5 分钟 = 演练直接判失败：说明 Binlog 上传或备份
链路异常，先按 E22-T03 告警与 `docs/ops/rds-prod.md` §1 排查，不得继续重建。

## 2. 前置条件

- [ ] E22-T04 已落地：主备实例、30 天自动备份、跨区域备份开启（工单通过 + IaC apply，
      `terraform output cross_region_backup_strategy_id` 有值）。
- [ ] 上海一控制台能看到持续同步的全量 + Binlog 备份文件（`docs/ops/rds-prod.md` §3.3 已核验）。
- [ ] E22-T05 已落地：CCE / ELB / WAF / HSS 基线 IaC 可在 `region = cn-east-3` 重建
      （`iac/cce-prod` 的 `variable "region"`）。
- [ ] 演练窗口：**每半年一次**（NFR-AVL-004），与 E22-T08 审计归档演练同窗口排期；首次演练在
      A 上线（E23-T11）后、等保测评（E22-T14）前的窗口执行。
- [ ] DEW / KMS 凭据与密钥的跨区域可用性已核对：注册表 `password_cipher` 与敏感字段密文依赖
      应用层密钥（NFR-SEC-002），DEW 密钥是**区域级资源**——备用区域须预置同版密钥或确认密文
      可解，否则恢复后应用连不上租户库（演练核对项，见 §4）。
- [ ] 演练租户与数据：用演示 / 演练租户的**虚构数据**做标记行与核对（AGENTS.md 第 5 节第 5 条），
      不使用真实患者数据。
- [ ] 运维机具备 mysql 客户端 8.0、python3、terraform、本仓库脚本；口令经环境变量注入。

## 3. 演练流程（七阶段，与脚本 `phase_*` 一一对应）

生产模式示例（参数按实际填写；先不加 `--yes` 看计划，确认后重跑加 `--yes`）：

```bash
export CRDP_DRILL_STANDBY_PASSWORD='<上海一重建实例迁移账号口令>'
scripts/cross_region_drill.sh --mode rds --drill-id xrg-2027h1 \
  --region-failed cn-north-4 --region-standby cn-east-3 \
  --tenant-code <演练租户编码> \
  --last-restorable-in-region '<北京四最近可恢复时间点 ISO-8601 带时区>' \
  --last-restorable-cross-region '<上海一最近可恢复时间点 ISO-8601 带时区>' \
  --region-a-jdbc-host <北京四实例地址:3306> \
  --standby-host <上海一重建实例地址> --standby-port 3306 --standby-user <迁移账号> \
  --standby-jdbc-host <上海一重建实例地址:3306> \
  --proof-file <验证清单 tsv> --health-url 'https://<备用区域入口>/actuator/health' \
  --report-archive-dir <存档目录> --yes
```

### 3.1 plan（宣告灾难，T0）

记录宣告时刻 `declared_at`（T0，RTO 计时起点；可 `--declared-at` 回填真实宣告时间），核对区域
白名单（cn-north-4 / cn-east-3，与 `iac/rds-prod/params-baseline.json` 一致）、时间戳 ISO-8601
带时区、备用实例连通性。未加 `--yes` 只输出计划、零改动。

### 3.2 inventory（实测 RPO，区域内验收门）

`--last-restorable-in-region` / `--last-restorable-cross-region` 由运维从两个区域的控制台
「备份管理 → 最近可恢复时间点」**读取真实值**填入（这是生产实测的口径，不用估算）。脚本计算
`区域内 RPO = T0 − 区域内最近可恢复点`、`跨区域 RPO = T0 − 跨区域最近可恢复点`；区域内 > 300 秒
即判失败中止（不做任何变更）。

### 3.3 rebuild（备用区域重建，云账号运维执行）

1. `cd iac/rds-prod && terraform plan -var 'region=cn-east-3' …`：在上海一按同一参数基线重建
   RDS 实例（主备、semisync、备份保留 30 天；**跨区域备份策略不重建**——备用实例暂不设跨区域
   副本，回切北京四后再恢复原策略）。
2. 上海一控制台：从同步过来的跨区域备份对新建实例做「恢复到指定时间点」（恢复点 =
   `--last-restorable-cross-region`），整实例恢复（平台库 + 全部租户库一起回来）。
3. `cd iac/cce-prod && terraform plan -var 'region=cn-east-3' …`：重建集群、ELB、WAF、证书、HSS
   （`docs/ops/cce-prod.md` 的基线不变），应用副本以当前镜像拉起。
4. 完成时刻用 `--rebuild-done-at` 回填（后续阶段同理：`--restore-done-at`、`--switch-done-at`），
   RTO 按真实里程碑计算，不按脚本运行时刻。

### 3.4 restore（数据就位核对）

生产模式数据已由 PITR 整实例恢复，脚本核对平台库与演练租户库在重建实例上存在。本地演练模式
（CI）由脚本从「跨区域备份副本化身目录」导入平台库化身与租户库化身、对象副本就位，并逐表比对
行数（导入完整性）。

### 3.5 switch（注册表与入口切换）

恢复出来的 `plt_tenant_resource.jdbc_url` 仍指向北京四实例地址；脚本把**全部租户**的 jdbc_url
主机改写到上海一实例地址（`REPLACE(jdbc_url, '//北京四主机/', '//上海一主机/')`，命中行数与
残留为 0 都要核对）。随后运维执行：

1. `CRDP_PLATFORM_JDBC_URL` 指向上海一实例（DEW 注入，含 `useSSL=true&connectionTimeZone=Asia/Shanghai`）；
2. `kubectl rollout restart` 滚动重启应用，连接池按新地址与注册表重建（与 E22-T06 §3.5 同机制）；
3. DNS / WAF CNAME 切到上海一入口（`iac/cce-prod` 输出的 ELB / WAF 域名）。

### 3.6 verify（验证清单）

见 §4。健康检查 `--health-url` 返回 UP 即记 `verified_at`（服务恢复），RTO 到此为止。

### 3.7 report（报告与存档）

脚本生成 `report.json` + `report.md`（时间线、实测 RPO / RTO 对照目标、验证清单结果、结论），
并复制到 `--report-archive-dir`。任一判定 FAIL（区域内 RPO、RTO、验证清单、对象对齐）以非零
退出——失败也存档，按 §4 处置后重新演练。

## 4. 验证清单（逐项核对，`--proof-file` 同格式：表<TAB>条件<TAB>期望行数）

- [ ] **恢复点三态标记**（数据丢失量的直接证据）：跨区域恢复点**之前**写入的标记行在恢复后的
      租户库中（期望 1）；恢复点**之后**写入的标记行不在（期望 0）。本地演练的对照见 §6。
- [ ] 迁移历史随库恢复：租户库 `flyway_schema_history` 非空；应用启动时 Flyway 校验通过
      （恢复点早于最新版本时自动补齐，属预期：只回数据不回表结构版本）。
- [ ] 注册表：`plt_tenant_resource` 无任何行指向北京四地址；演练租户行 `db_name` 与恢复库一致。
- [ ] 健康检查：备用区域入口 `/actuator/health` UP。
- [ ] 医院端登录与冒烟（虚构测试数据）：写一条随访记录 → 查询可见；明文查看、报告单预览水印正常。
- [ ] **DEW 密钥跨区域可用**：应用能解开 `password_cipher` 并连上租户库（连不上 = §2 前置缺失，
      演练记录为问题项）。
- [ ] **对象对齐**（tech-plan §14「与数据库恢复时间点对齐」）：OBS 业务桶跨区域复制的上海一副本
      中，数据库恢复点**之前**上传的对象必须存在（`obsutil ls` 抽样 + 报告单预览取回一个对象）；
      恢复点之后的对象多余存在不致错（引用它的库行已随恢复点消失）。本地演练以对象化身目录 +
      `--objects-manifest` 清单核对（清单 = 恢复点前已上传对象，缺失即 FAIL）。
- [ ] 审计链完整：恢复点处 `aud_log` 链值连续（验链作业 SUCCESS 或人工验链 SQL；恢复点之后的
      链值缺失属预期——那段数据本就丢了）。
- [ ] 定时作业恢复执行：ShedLock 正常抢锁，随访计划生成等作业无异常堆积。

失败处置：任一项不过 → 报告 FAIL 项照实记录 → 排障（多数问题在重建实例参数 / 密钥 / 入口配置，
数据本身由 PITR 保证）→ 重新演练出具新报告；**不得手工改库凑验证**。

## 5. 报告与存档（验收：演练报告存档）

- **本地 / CI 演练报告**（不依赖云账号，见 §6）：脚本生成的 `report.md` 经人工确认后存入
  `docs/ops/drill-records/`（文件名 `<日期>-<环境>.md`），并在 `drill-records/README.md` 登记。
- **生产演练报告**：含实例 ID、真实耗时与告警截图，**归档运维 wiki，不入仓库**（与
  `docs/ops/rds-prod.md` §5 同口径）；仓库内只在 `drill-records/README.md` 追加一行登记
  （日期 / 演练编号 / 实测区域内 RPO / 跨区域 RPO / RTO / 结论），保证「报告已存档且结论可查」。
- 存档判据：报告含演练编号、两个 RPO 实测值、RTO 实测值、验证清单结果与结论；区域内 RPO ≤ 300
  秒且 RTO ≤ 14400 秒方可登记结论「达标」。

## 6. 本地 / CI 演练（不依赖云账号）

`python3 scripts/check_cross_region_drill.py --drill`（CI 在 MySQL 起来后跑，与本地一致）：

- 夹具复制演示库造「北京四生产」化身（`xrga_` 前缀库 + 区域 A 对象化身目录），按两个口径各做
  一次**逻辑快照**充当备份副本：跨区域副本先做（滞后）、区域内副本后做——快照节奏即 RDS
  「Binlog 每 5 分钟上传」的化身（本地无 binlog，`docker-compose.yml` 按开发口径 `--skip-log-bin`）；
- 三个标记行锚定恢复点：M0 = 跨区域同步前、M1 = 跨区域同步后、M2 = 区域内同步后，演练断言
  恢复后 M0 在 / M1 与 M2 不在；再把区域内副本单独导入对照库断言 M0/M1 在 / M2 不在——
  **「区域内恢复丢数据仅限最后一次同步之后」的数据证据**；
- 跑脚本 local-drill 全流程：区域内 RPO 实测（宣告 − 区域内同步点）≤ 300 秒验收门、跨区域 RPO
  实测（宣告 − 跨区域同步点，数值上更大）、RTO 实测（宣告 → 验证完成）；报告落盘并断言字段与
  重算一致、验收门全 PASS、报告存档到 `--report-archive-dir`；
- 注册表全部 jdbc_url 主机改写到备用区域地址、对象对齐（恢复点之前的对象在备用区域副本、
  之后的对象不进清单）；
- 护栏：非法区域 / 非法时间戳 / 区域内 RPO 超 5 分钟（不建任何化身库）/ 未加 `--yes`（零改动）
  必须拒绝。化身库（`xrga_` / `xrgi_` / `xrgb_` 前缀与演练租户库）演练后清理，不留残留。

生产 rds 模式与演练 local-drill 模式共用切换 / 验证 / 报告代码路径，差异只在「重建与恢复」
（PITR 整实例恢复 vs 化身库导入）与时间戳来源（控制台实测 + 里程碑回填 vs 夹具快照时刻）。

## 7. 半年演练与回切

- 每半年（NFR-AVL-004）按本手册重演一次，与 E22-T08 归档演练同窗口；E22-T12「恢复演练测试
  核对」按 §4 清单与最新存档报告核对恢复数据。
- 演练结束（真实灾难恢复或演练验证通过）后回切北京四：反向走一遍单院恢复口径不适用——整实例
  回迁用 DRS / 导出导入由运维专项评估，演练报告记录「回切方案与耗时」一行即可（本手册不展开）。
- 演练用的上海一重建实例与集群在验证完成后**当日删除**（避免持续计费），删除动作与耗时记入报告。

## 8. 由人执行的事项（按 `AGENTS.md` 第 9 节）

- 生产演练的排期、宣告、控制台操作（PITR、DEW 密钥预置、DNS / WAF 切换）与报告归档；
- §2 的 DEW / KMS 跨区域密钥预置决策（若需密钥同步方案，先补 ADR 或 OQ）；
- `drill-records/README.md` 的生产演练登记与结论确认（E22-T12 核对的输入）。
