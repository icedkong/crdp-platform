# ADR 0005：审计表按月分区与 OBS WORM 归档

- 状态：已接受（E08-T03 / PLT-AUD-010）
- 日期：2026-11-02
- 关联：`docs/requirements/tech-plan.md` §14「审计」、`docs/requirements/platform-spec.md` §9 PLT-AUD-008/009/010/011、ADR 无前置（新增决策）

## 背景与决策

PLT-AUD-010：审计日志在线保留 1 年后转归档存储，归档对象锁定 15 年；tech-plan §14 定了做法（按月分区、JSONL+清单、单次
PUT 写 WORM 桶、校验后删分区、只写北京四一份）。本 ADR 记录把该做法落到代码时的四个关键取舍。

### 1. 分区键与主键改造

- 审计表（租户库 `aud_log` / `aud_login_log` / `aud_data_access_log`，平台库 `plt_audit_log` / `aud_login_log` /
  `aud_platform_access_log`）改 `PARTITION BY RANGE (TO_DAYS(created_at))` 按月分区，静态建到 2027-12 + `pmax` 兜底
  （迁移 V18 / V27）。
- MySQL 分区要求分区列进入全部唯一键 → 主键从 `(id)` 扩为 `(id, created_at)`。`id` 仍是主键最左前缀，`id` 单列自增与
  按 `id` 的链序扫描（写入取链尾、验链分页）不受影响。
- 分区命名固定 `pYYYYMM`（覆盖该自然月，上界 = 次月首日）；`pmax` 永不删除、只作为 REORGANIZE 的拆分对象。非该命名的
  分区不参与到期判断，也不被删除（保守口径）。
- 未来月份由归档作业 `AuditArchiver.ensurePartitions` 用单条 `REORGANIZE pmax` 预建（本月 + 3 个月，幂等），MySQL
  无自动建分区，应用侧补齐；静态分区只是装库时的初始集。

### 2. 删除路径：DROP PARTITION，不是 DELETE

在线保留清理是**删整月分区**（`ALTER TABLE … DROP PARTITION`），不触发行级只读触发器（PLT-AUD-011 的逃生口只留给测试
自清理与运维清算，见 V17 / V26 注释）。行级 DELETE 在数据库层被触发器拒绝，二者共同构成「只有分区级维护能收缩在线数据」。

### 3. 哈希链跨归档段可验（桥接）

删分区后在线链出现「归档洞」，两条配套规则保证链仍可验、且攻击者不能用归档做掩护：

- **验链**（`AuditChainVerifier`）：衔接不上时查归档登记 `aud_archive`（每库一张，只追加）——`prev_hash` 等于某归档段
  `last_hash` 且该段 `last_id` 小于当前行 id，判定合法桥接；查无登记仍报 `BROKEN_LINK`。`last_hash` 由 SHA-256
  密码学绑定归档内容，伪造需同时改登记表（登记表自身有只读触发器 + WORM 对象可回对）。
- **写入续锚**（`AuditChainWriter`）：在线已无已封存行时（整表归档过，如 `aud_platform_access_log`
  长期无流量），新写入的前序哈希取 `aud_archive` 的链尾而不是重锚 GENESIS，链跨归档段保持连续。

登记表 `aud_archive` 记录：表、分区、月份、对象键、行数、首尾 id、首行 prev_hash、末行链尾哈希、JSONL 摘要、
WORM 保留截止。空分区（到期但无数据）也登记行数 0 后删除，保证「该月无数据」可追溯。

### 4. 存储端口与失败安全

- 新增端口 `AuditArchiveStorage`（`crdp-framework` storage 包，与业务桶 `ObjectStorage` 分离）：`putWithRetention`
  单次 PUT + 对象级保留（= 归档日 + 15 年）、`get` 读回校验、`enabled` 标记。OBS 适配器随 E10 / E22 接云凭证时替换
  `UnsupportedAuditArchiveStorage`；**接口签名按 AGENTS §6 走本 ADR 立案**。
- 删除顺序严格：导出 → 上传（数据 + 清单）→ 读回校验 → 登记 `aud_archive` → `DROP PARTITION`。任何一步失败分区保留
  在线（作业 FAILED）；登记已写但删分区前中断时，重跑按对象摘要校验后直接删（不重传——WORM 对象不可覆盖）；桶中已有
  同键不同内容对象 → CONFLICT 转人工。
- 对象键确定性：`audit/tenant/{租户编码}/{表}/{yyyy-MM}.jsonl.gz` 与 `.manifest.json`，同分区重跑不产生第二份对象。
- 本机 / CI 无云凭证（`enabled=false`）：作业只做分区维护，不上传、不删在线数据。

## 部署侧约定（不在本任务代码内，由运维 E01-T08 / E22-T08 落地）

- 归档桶为单独单可用区 OBS 桶，桶级 WORM（合规模式）默认保留 15 年；**只写北京四一份**，不做异地 / 跨区域副本
  （tech-plan §14 已确认）。生命周期 30 天转归档存储、180 天转深度归档由桶策略配置（WORM 允许转存储类别）。
- tech-plan 写「校验后由**归档专用账号**删除该分区」：本任务代码由应用连接执行 `DROP
  PARTITION`（本机 / CI 无独立账号）；生产建议给作业使用的数据库账号收敛到只允许 DDL 分区维护的权限组，账号分离
  留给 E22-T08 演练时落实。

## 后果

- 审计表带 `TO_DAYS(created_at)` 分区函数：`created_at` 永远非空（NOT NULL DEFAULT），无「落不进分区」的行。
- 分区数稳定在约 15 个/表（12 个月在线 + 预建 3 + pmax）；月度滚动删除与预建，`information_schema.PARTITIONS`
  是分区清单的唯一事实来源（按 `pYYYYMM` 命名约定解析上界，不解析 `PARTITION_DESCRIPTION`，避免不同 MySQL
  版本展示差异）。
- 恢复查询（「更早的按需先取回再导入只读恢复表」，PLT-AUD-010）属 E08-T04 / 后续查询任务，本任务只保证对象与登记
  可回溯（键名、摘要、链尾）。
