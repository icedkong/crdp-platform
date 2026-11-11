# 医院 B 回归手册：A 功能不受影响（W38 · E23-T28）

> 任务 E23-T28（测试，2 人天，依赖 **E23-T17** ✅；验收：**A 回归用例通过**）。tasks.csv 本行未关联需求编号：回归面 = 医院 B 上线（E23-T15 配置模板开通 → T16 预置激活 → T17 验收 → T18 用户病区角色 → T19 切换）引入的**共享面变更**逐项核对医院 A 不受影响 + A 上线基线（`docs/testing/hospital-a-golive-smoke.md` S-x 用例集）主链复跑。
> 按 `AGENTS.md` 第 9 节：生产 A 回归由**测试在场执行**（B 上线切换后立即，`docs/ops/hospital-b-go-live.md` §9 衔接行），仓库交付——用例集（§3）+ 机器侧自动化 `BRegressionAUnaffectedIT`（门控 `CRDP_IT_MYSQL_PORT`，CI / 本机同口径，§7）+ 防漂移校验 `scripts/check_hospital_b_regression.py`（CI 恒跑）。
> 排期：S19 · W38（2027-08-02 当周，随 E23-T19 切换窗口之后立即）；预算 ≤ 半天（含 §4 生产动线）。

## 1. 目标与验收口径

- 「A 回归用例通过」= §3 用例集**全部通过**：机器可判定子集在 CI 每次构建执行（§7），生产侧动作按 §4 由人执行留痕（§5）；
- 回归时机与形态：B 处于**上线后状态**（B 库：A 预置「冠心病术后年度随访」停用、B 预置「心脏术后随访（医院 B）」已发布 + 自动入组、`node_rate` 卡片角色布局已开、B 用户 / 病区 / 角色已配齐）时，A 的计划、患者主链、开关、首页、隔离红线全部与 A 上线基线一致；
- 回归基线（对照物）：A 上线冒烟手册 S-x 用例集（E23-T12）+ `docs/ops/hospital-a-go-live.md` 上线基线 + `docs/ops/hospital-b-go-live.md` §4 第 6 项「A 不受影响（只读）」终检；
- 任一用例不通过 = B 上线影响了 A → 按 `docs/ops/hospital-b-go-live.md` §8 处置（B 侧租户级收敛 action=DISABLE，不动平台入口），缺陷入 hypercare 台账（P0 = 15 分钟响应），修复后**全组复测**；
- 与既有覆盖分工（不重复执行）：A 冒烟全链路 S-2~S-12 由 `GoliveSmokeIT` CI 恒跑锁定（RA-5 引用）；B 侧开通「只写 B 库」红线由 `TenantBOnboardingMySqlIT` 锁定（RA-6 并行补证）；本手册的机器面是**B 上线形态之下**的 A 侧行为回归（既有 IT 跑在 B 随包草稿态，时序不同，§6）。

## 2. 执行面分工

| 执行面 | 内容 | 载体 |
|---|---|---|
| 机器 CI | RA-1~RA-4、RA-6 的机器可判定部分（demob 作 B 化身置为上线态 + demoa 作 A 化身复跑 A 基线，全虚构数据，与生产同链路） | `BRegressionAUnaffectedIT`，门控 `CRDP_IT_MYSQL_PORT` |
| 生产人工 | RA-0 / RA-7 全部 + RA-1~RA-4、RA-6 的生产侧动作（真实入口、A 管理员 / 医生账号操作） | 本手册 §4 流程 + §5 执行记录 |

## 3. A 回归用例集（共 8 条）

> 级别全部为**阻断**（任一不通过 = 回归不通过）；「依据」列括号内为口径来源（A 冒烟用例编号 / 需求编号 / 确认问题 / 仓库口径）。

| 编号 | 级别 | 执行面 | 角色 | 步骤 | 预期（依据） |
|---|---|---|---|---|---|
| RA-0 | 阻断 | 生产人工 | A 租户管理员 / 四类角色 | B 上线后（E23-T19 §6 冒烟完成后立即）：浏览器开医院端入口，A 管理员 + 科主任 / 病区医生 / 随访员 / 数据录入员各 1 名按 A 租户编码登录 | 登录页可达、证书有效（B 复用入口未影响 A）；登录成功、医院名称 = 医院 A；菜单与本角色权限一致（S-3 口径；A 规格 §10 矩阵） |
| RA-1 | 阻断 | 机器 CI + 生产人工 | A 租户管理员 | B 库处于上线态（A 预置停用、B 预置已发布）时，A 管理员查随访计划列表与节点随访率卡片（不选计划）；生产侧 A 管理员目检计划列表 | A 库：A 预置「冠心病术后年度随访」仍**已发布 + 自动入组**、B 预置草稿仍**未发布**且惰性（自动入组候选只有 A 预置，V80 对 A 惰性、OQ-128）；卡片缺省计划 = A 预置、候选不含 B 草稿（B 侧「停用 A 预置 / 发布 B 预置」只写 B 库） |
| RA-2 | 阻断 | 机器 CI + 生产人工 | 病区医生 / 随访员 | 登记「周回归（示例）」（出院日期 = 一年前、出院状态存活）→ 查患者详情随访页签；认领第 1 年任务 → 进入 A_ANNUAL_FU 表单填写提交 | 自动加入预置计划一条、起算日期 = **出院日期**（Q35）；第 1 年任务：计划日 = 出院 +1 年、提前 7 天提醒、窗口前后各 7 天、表单 A_ANNUAL_FU（A-FU-002~004、V61）；提交后「已完成（按时）」、记录生效（S-6 / S-7 口径；E23-T17 `TaskCreationPlanner` 共享代码修复后的 A 向回归） |
| RA-3 | 阻断 | 机器 CI + 生产人工 | 病区医生 | 登记「吴补录（示例）」（出院日期 = 13 个月前）→ 查随访页签与任务 | 第 1 年窗口已结束 → 补建一条「补录」任务（`create_type = BACKFILL`、不写 TaskOpened，Q44）；**未来年度不预生成**、缓存指向第 2 年（出院 +2 年、开启日 = 前 7 天，长期不设结束年度 A-FU-016；FIXED 排完返回 null 的修复不改变 A 无限循环排期） |
| RA-4 | 阻断 | 机器 CI + 生产人工 | A 租户管理员 / 随访员 | A 管理员 `GET /api/hospital/auth/features`；随访员登录看首页卡片 | 开关与 A 基线一致：`wide_import` / `report` / `compare_highlight` 开、`implant` 关（S-2 口径）；首页**无节点随访率卡片**（`node_rate` 目录 `default_visible = 0` 未被 B 侧角色布局动作改动，V77 / A-DASH-013「医院 A 默认不显示」） |
| RA-5 | 阻断 | 机器 CI（引用） | —— | A 冒烟主链（S-2~S-12：四角色登录 → 列表脱敏 → 登记 / 查重 → 入组 → 认领提交 → 未联系到跟进 → 报告单 → 首页 → 越范围 / 跨端 → 审计）与 V136 B 预置命名空间在 A 库独立落位 | `GoliveSmokeIT`（E23-T12，CI 门控恒跑）与 `BPresetWardUserVerifyIT`（E23-T18 demoa 侧双租户断言）已锁定，本手册不重复执行 |
| RA-6 | 阻断 | 机器 CI + 生产人工 | —— | B 激活 / 上线动作的落点核对：A 库 `fu_plan_log` 无操作人非空的 `PLAN_DISABLE` / `PLAN_PUBLISH` 行、`aud_log` 无对应动作、A 角色布局无 B 侧 `node_rate` 开启行；医院令牌访问平台接口 | B 侧动作**只写 B 库**（E23-T15~T18 红线）：A 库无 B 激活痕迹（V61 随包系统行操作人为空）；医院账号与平台账号互不相通 40300（AGENTS §5.1~5.2、S-11 口径） |
| RA-7 | 阻断 | 生产人工 | 测试 + A 管理员 | 生产 A 回归动线汇总：按 §4 复走 S-3 / S-4 / S-5 / S-6 / S-7 / S-10 + B runbook §4 第 6 项只读核对，审计中心核对当日留痕，删除示例患者 | 全部通过 + 执行记录归档（§5）；示例患者删除确认（数据红线） |

## 4. 生产回归执行流程（B 上线切换后立即，≤ 半天）

> 时点：E23-T19 §6 B 冒烟（BG-0~BG-10）通过之后同窗口执行（§9 衔接）；测试执行，A 管理员配合账号。
> 数据红线：只用带「（示例）」标记的虚构患者（RA-2 / RA-3 各 1 名，建议命名「周回归（示例）」「吴补录（示例）」）；**不打开真实患者明文**、不修改真实患者（`AGENTS.md` §5.5）；回归结束删除示例患者并在执行记录确认。

| # | 动作 | 用例 | 预算 |
|---|---|---|---|
| 1 | A 入口连通性与四类角色登录、菜单核对 | RA-0 | 15 分钟 |
| 2 | A 管理员计划列表与节点随访率卡片缺省计划目检 | RA-1 | 10 分钟 |
| 3 | 登记示例患者（出院一年前），随访页签核对入组与第 1 年任务参数 | RA-2 | 20 分钟 |
| 4 | 认领 → 表单填写提交，核对「已完成（按时）」 | RA-2 | 15 分钟 |
| 5 | 登记第二名示例患者（出院 13 个月前），核对补录任务与未来年度不预生成 | RA-3 | 15 分钟 |
| 6 | A 管理员 features 开关核对、随访员首页无节点随访率卡片目检 | RA-4 | 10 分钟 |
| 7 | B runbook §4 第 6 项只读核对（A 的计划 / 开关 / 菜单与 A 上线基线一致） | RA-6 | 15 分钟 |
| 8 | 审计核对、示例患者删除、执行记录归档（§5） | RA-7 | 15 分钟 |

- 失败处置：任一用例不通过 → `docs/ops/hospital-b-go-live.md` §8（B 侧租户级收敛，不动平台入口、A 不受影响的动作面）+ hypercare 台账（P0 = 15 分钟响应）；修复后 §3 全组复测；
- 机器侧（CI `BRegressionAUnaffectedIT`）失败：同「不带病上线」纪律——B 上线窗口内发现即暂停后续动作，修复复绿后再继续。

## 5. 执行记录（归档运维 wiki，不进仓库）

| 项 | 值 |
|---|---|
| 日期 / 执行人 / 复核人 / 后端版本号 | |
| 逐条结果（RA-0~RA-7：通过 / 不通过 + 证据编号） | |
| 示例患者清理确认（RA-2 / RA-3 两名已删除） | |
| 不通过项处置（hypercare 台账编号 / B 租户收敛决定） | |
| 结论（是否「A 回归用例通过」、遗留与期限） | |

## 6. 回归面与 B 上线变更的映射（风险面清单）

> B 上线各任务引入的、可能波及 A 的**共享面**变更，逐项映射到用例（回归依据）：

| B 上线变更 | 为什么可能影响 A | 覆盖用例 |
|---|---|---|
| followup V80：B 预置计划草稿随包进**所有**租户库（含 A 库，`TenantSchemaMigrator` 同一 Flyway 历史） | A 库多出一行计划草稿：若候选过滤遗漏会进 A 的自动入组 / 卡片候选 | RA-1（惰性与候选）、RA-2 / RA-3（A 登记仍只入组 A 预置） |
| E23-T17 修复 `TaskCreationPlanner.occurrenceScheduleOf`（FIXED 固定次数排完 → null，共享域代码） | A 与 B 共用排期代码：修复若波及 CYCLE 无限循环或回落取值，A 的年度排期 / 补录窗口会错 | RA-2（第 1 年正常创建 + 完成）、RA-3（第 1 年补录 + 第 2 年缓存 = 无限循环仍可取） |
| E23-T16 / T17 B 侧激活动作（停 A 预置 + 发布 B 预置）经 HTTP 正口写库 | 激活若误写 A 库（租户路由缺陷）会停掉 A 的生产计划 | RA-1（A 预置状态不变）、RA-6（A 库无激活痕迹） |
| E23-T17 B-6 / E23-T19 BG-7：B 侧 `node_rate` 角色布局开启、`DASHBOARD_ROLE_LAYOUT` 审计 | 布局若按角色编码跨库生效会让 A 首页出现节点随访率卡片（A 默认不显示） | RA-4（A 布局与目录 `default_visible=0` 不变）、RA-6（A 无 B 侧布局行） |
| tenant/seed V136：B 用户 / 病区 / 角色预置与 A 预置 V123 同库并存（演示 / IT 库；生产 A 库不执行种子） | 命名空间若冲突会污染 A 的登录 / 角色 / 病区面 | RA-5（引用 `BPresetWardUserVerifyIT` demoa 侧双租户断言） |
| B 上线切换动作（转正 / 到期设置 / 观察期）只动平台库 B 租户行 | 平台库缺陷会波及 A 租户行 | RA-6（跨端 40300）+ `hospital-b-go-live.md` §4 第 6 项只读核对（RA-7 步骤 7） |

既有 IT 的时序差异（本手册机器面的存在理由）：`GoliveSmokeIT` / `APresetPlanVerifyIT` 等 A 侧 IT 跑在 **B 随包草稿态**（B 预置未发布、demob A 预置在册），且 `BPresetPlanVerifyIT` 的 A 侧断言先于 B 侧激活执行；生产 W38 形态是 **B 已激活上线**。`BRegressionAUnaffectedIT` 把 demob 置为上线态（停 A 预置 + 发布 B 预置 + `node_rate` 布局开启）后再跑 A 侧断言，时序与生产一致。

## 7. 机器侧自动化对照（CI / 本机门控）

| 用例 | 承载（`BRegressionAUnaffectedIT`，demoa 作 A 化身 + demob 作 B 化身，自建自清 `IT-E23T28-` 前缀） |
|---|---|
| 前置 | `@BeforeAll` 把 demob 置为 **B 上线态**（停用 A 预置 + 发布 B 预置 + 管理员角色布局开启 `node_rate`，即 E23-T16 激活 + E23-T17 B-6 的组合态）；demoa 恢复 A 基线（A 预置已发布 + 自动入组、受检角色布局清回目录默认）；`@AfterAll` 两库恢复随包状态 |
| RA-1 | `ra1BGoLiveStateLeavesAPlansAtBaseline`：demob 激活态自证（A 预置 DISABLED / B 预置 PUBLISHED）→ demoa：A 预置仍 PUBLISHED + `auto_enroll=1`、B 草稿仍 UNPUBLISHED、自动入组候选只有 A 预置；计划列表接口 A 预置已发布在列；`node_rate` 卡片缺省 `planId` = A 预置、候选 1 条（B 草稿退出） |
| RA-2 | `ra2ARegistrationEnrollsAndCompletesOnTime`：病区医生登记「周回归（示例）」（出院 = 一年前）→ 只入组 A 预置一条、起算 = 出院日期（Q35）；第 1 年任务行级参数按 V61（计划日 / 提醒 / 窗口 ±7 / 表单 A_ANNUAL_FU / NORMAL、写 1 条 TaskOpened）→ 随访员认领 → start → 提交 → `COMPLETED` + `ON_TIME`（S-6 / S-7 机器面） |
| RA-3 | `ra3ABackfillAndSecondYearCacheKeepAShape`：登记「吴补录（示例）」（出院 = 13 个月前）→ 第 1 年窗口已结束补建 1 条 BACKFILL、不写 TaskOpened（Q44）；`next_plan_date` = 出院 +2 年、`next_open_date` = 前 7 天（A-FU-016 长期循环不设结束） |
| RA-4 | `ra4AFeaturesAndDashboardStayAtABaseline`：demoa features `wide_import` / `report` / `compare_highlight` = true、`implant` = false（S-2 基线）；随访员首页布局可见卡不含 `node_rate`（`default_visible=0`）且目录行未被改动 |
| RA-6 | `ra6BActivationWritesStayInBDatabaseOnly`：demoa `fu_plan_log` 无操作人非空的 `PLAN_DISABLE` / `PLAN_PUBLISH` 行（V61 系统行操作人为空）、激活型动作审计（停用 / 发布 / 布局）较激活前基线**零新增**（共享演示库其他 IT 的历史行不计，判据 = B 侧激活只写 B 库）、受检角色无布局行；医院令牌调 `/api/platform/menus` 40300 |
| RA-5 | 引用型：`GoliveSmokeIT`（S-2~S-12）与 `BPresetWardUserVerifyIT`（demoa 侧双租户）CI 恒跑，不重复执行 |

## 8. 由人执行的事项（按 `AGENTS.md` 第 9 节）

- 生产 A 回归动线（§4 全部）——测试执行，A 管理员配合账号，B 上线窗口内衔接 E23-T19 §6 之后；
- 示例患者账号与数据清理确认；
- 执行记录与证据归档运维 wiki（§5）；
- 失败时与医院 A / B 沟通、B 租户收敛决策（`hospital-b-go-live.md` §8 口径）。

## 9. 防漂移

`scripts/check_hospital_b_regression.py`（CI 恒跑）校验：本手册 ↔ `BRegressionAUnaffectedIT` 用例锚点（RA-x）↔ `docs/ops/hospital-b-go-live.md` §9 衔接 ↔ `docs/README.md` 索引 ↔ `docs/tasks/tasks.csv` 行 ↔ `.github/workflows/ci.yml` 接线；接线见 ci.yml「医院 B 回归校验（E23-T28）」步骤。
