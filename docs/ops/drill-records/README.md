# 恢复演练报告存档（E22-T07）

> 验收口径（`docs/tasks/tasks.csv` E22-T07）：**演练报告存档，区域内 RPO ≤ 5 分钟**。
> 演练手册与流程见 `docs/ops/cross-region-drill.md`；脚本 `scripts/cross_region_drill.sh`；
> 本目录只放演练报告与登记表，不放转储 / 凭据 / 实例口令。

## 存档规则

| 环境 | 报告全文 | 登记 |
|---|---|---|
| 本地 / CI 演练（`check_cross_region_drill.py --drill`，不依赖云账号、全部虚构数据） | 入仓库本目录：`<日期>-<环境>.md`（由脚本 `report.md` 加环境说明头生成） | 下表追加一行 |
| 生产演练（持云账号运维执行，含实例 ID 与真实耗时） | 归档运维 wiki，**不入仓库**（与 `docs/ops/rds-prod.md` §5 同口径） | 下表追加一行登记（结论可查，全文按编号到运维 wiki 调） |

达标判据：报告中**区域内 RPO 实测 ≤ 300 秒**（NFR-AVL-002 验收门；Binlog 每 5 分钟上传）且
**RTO 实测 ≤ 4 小时**；跨区域 RPO 无 SLA、照实记录（tech-plan §14 已确认接受）。任一 FAIL
不得登记「达标」，处置后重新演练。

## 演练登记表

| 日期 | 演练编号 | 环境 | 区域内 RPO（实测） | 跨区域 RPO（实测） | RTO（实测） | 结论 | 报告 |
|---|---|---|---|---|---|---|---|
| 2026-11-10 | xrg-local-check | 本地 CI 化身（compose MySQL 8.0） | 1 秒 | 7 秒 | 9 秒 | 机制达标（化身环境，生产值待首次生产演练） | `2026-11-10-local-ci.md` |

## 核对登记表（E22-T12 · 按清单核对恢复数据）

核对清单与方法见 `docs/ops/drill-recovery-checklist.md`（runbook §4 九项）；机器核对工具
`scripts/drill_recovery_check.py`（默认存档核对 / `--drill` 化身核对 / `--selftest` 自检）。
核对行引用的演练编号必须已在上面演练登记表登记。

| 日期 | 演练编号 | 核对方式 | 清单结果 | 结论 |
|---|---|---|---|---|
| 2026-11-10 | xrg-local-check | scripts/drill_recovery_check.py --drill（化身）+ 默认存档核对 | 机器核对项全 PASS：三态标记 1/0/0、迁移历史版本集与仓库脚本及源库一致、注册表全部改写无故障区域残留、对象对齐内容逐字节一致、审计链全量重算 0 BAD_HASH 且链行指纹与快照逐行一致、shedlock 随库恢复、登记表 ↔ 存档报告数值一致 | 通过（化身环境）；健康检查 / 医院端冒烟 / DEW 解密 / ShedLock 抢锁为生产核对项，待首次生产演练由人核对 |
