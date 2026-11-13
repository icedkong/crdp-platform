# 医院 C 电子化、待归档区与转录用例：执行留痕（W42–W43 · E21-T27）

> 任务 E21-T27（测试，2.5 人天，依赖 **E21-T17** ✅；验收：**状态流转用例通过**）。tasks.csv 关联需求列为空——验收面 = 评审用例集 `docs/reviews/hospital-c-acceptance-review.md` §6 指派本任务的 **C-ACC-28…37**（电子化、待归档区、转录与报告单位置，含植入 / 程控分组位置）。
> 承接范围：C-ACC-28…37 的**机器 / 行为面**（生产侧动作随 E23-T23 UAT）。全部机器可判定用例 CI 与本机同口径执行；数据全部虚构（`IT-E21T27-` 前缀，自建自清）。

## 1. 验收口径

- 「状态流转用例通过」= 电子化状态机的全部流转在随包产品配置链上端到端通过：
  - **默认值**：创建时取名称覆盖 → 类型默认（`PENDING` 待录入 / `NO_ENTRY` 无需录入；C-SCAN-011）；
  - **PENDING/未设置 → DONE**：转录态提交 / 保存修改（E21-T17 `ReportTranscribeService`，写 `RPT_TRANSCRIBE`）；手工修改（E21-T15，写 `RPT_DIGITIZE_CHANGE`，改 `NO_ENTRY` 须填原因）；
  - **DONE → PENDING（本任务补实现）**：已电子化报告单**移除其随访记录位置**（移除后不再有任何随访位置）→ 自动回「待录入」，写 `RPT_DIGITIZE_CHANGE`（自动口径），**结构化记录不受影响**（C-SCAN-020、C 规格 §7「业务规则与边界情况」末段、评审 CL-10 / Q12 已确认——E16-T13 / E21-T15 落地时未接线，本任务按规格补齐）；
  - **进度口径**：已电子化 ÷（已电子化 + 待录入），「无需录入」不计入分母（C-DASH-007 / Q12）。
- 执行面（`CDigitizeTranscribeAcceptanceIT`，MySQL 门控 `CRDP_IT_MYSQL_PORT`）：demoa 租户模拟医院 C 形态——`scan_progress` 开关打开 + 按 runbook `docs/ops/hospital-c-report-preset.md` §3 激活路径启用 C 预置类型（`C_PROGRAM` / `C_PAPER_FORM` / `C_CONSENT`，随包 DISABLED，OQ-142①；测试动作收尾还原 DISABLED）。程控记录直接走随包 `C_PROGRAM_FU` 表单（V90，只读引用不删改种子），映射走 V94 预置 `C_PROGRAM_TO_FU`（21 项）。
- 与 E21-T25 / T26 套件同法：C 预置激活是测试动作、`@AfterAll` 还原，A / B 租户与其它 IT 不受影响。

## 2. 状态流转与转录用例执行映射

| 用例 | 要点 | 承载 |
|---|---|---|
| C-ACC-28 分屏转录 | 对照扫描件录入；保存 → 记录自动加入扫描件的位置、状态「待录入」→「已电子化」、可从扫描件跳到该记录 | 配置链面 `cAcc28PaperFormTranscribeMarksDoneAndJoinsRecord`（纸质随访表只挂基线位置 → 转录新建补录程控记录并提交：DONE + 自动插入「整条记录」位置行 + 患者内清单位置标签出现〔跳转数据源〕+ `RPT_TRANSCRIBE`）+ 行为面 `ReportTranscribeIT`（E21-T17 随实现交付：普通提交不动状态、开关关闭 40301 事务回滚、越范围 40400）与 `ReportTranscribeServiceTest` / `RecordEntryServiceTest`（幂等 / 40001 / 审计）；前端 `compare-entry-logic.spec`（转录入口按状态与开关显隐） |
| C-ACC-29 三态与默认值 | 待录入 / 已电子化 / 无需录入；默认按类型、名称可覆盖 | `cAcc29ThreeStatesDefaultsProgressAndRevertOnLocationRemoval`（纸质随访表默认 PENDING、知情同意书默认 NO_ENTRY）+ 行为面 `ReportServiceTest`（创建取名称覆盖 → 类型默认、A 租户恒 NULL）与 `ReportConfigServiceTest`（类型 / 名称配置取值约束） |
| C-ACC-29 手工改无需录入 | 须填原因并留痕 | `cAcc29ThreeStatesDefaultsProgressAndRevertOnLocationRemoval`（无原因 40001、填原因成功）+ 行为面 `ReportServiceTest.changeDigitizeStatusMarksNoEntryWithReasonAndAudits` 等单测（前后值 / 原因 / version+1 / 开关关闭 40301 / 越范围 40400） |
| C-ACC-29 补录进度 | 已电子化 ÷（已电子化 + 待录入），无需录入不计入 | `cAcc29ThreeStatesDefaultsProgressAndRevertOnLocationRemoval`（`GET /dashboard/cards/scan_progress` 按上传人过滤：转录前 1 待录入 0%→ 转录后 0 待录入 100% → 移除随访位置回退后 1 待录入 0%；知情同意书全程不在分母）+ 行为面 `ScanProgressServiceTest`（公式与开关闸门）、`DashboardKpiServiceTest`（scanPending 钻取） |
| C-ACC-29 / CL-10 移除随访位置回退 | 已电子化报告单被删除或移除随访位置 → 回「待录入」、结构化记录不受影响 | `cAcc29ThreeStatesDefaultsProgressAndRevertOnLocationRemoval`（移除整条随访位置 → PENDING、fu_record 仍 SUBMITTED、`RPT_DIGITIZE_CHANGE`）+ `cAcc34ProgramReportMapsIntoProgramGroupThenReverts`（移除程控分组位置同口径）；行为面 `ReportServiceTest.changeLocationsRevertsDoneToPendingWhenLastFollowupLocationRemoved` / `changeLocationsKeepsDigitizeStatusWhenFollowupAnchorRemains`（换记录 / 只移除基线 / 非 DONE 不触发）、`ReportLocationFallbackServiceTest.removingLastFollowupLocationRevertsDoneToPending` / `fallbackToWholeRecordOrNonDoneStatusKeepsDigitize`（退回作业：记录不存在移除随访行 → 回退；分组退回整条不触发；非 DONE 幂等不写）。**报告单被删除**：软删除后 `r.status='DELETED'`，进度与列表按 `status='NORMAL'` 天然不计（`ScanProgressMapper` / 检索条件树），状态列无可见消费者——不另设状态动作 |
| C-ACC-34 程控报告单映射写入 | 位置选第 3 次随访 → 电池电压 / 心室起搏阈值写入该记录（报告单不另存参数）、报告单变「已电子化」 | `cAcc34ProgramReportMapsIntoProgramGroupThenReverts`（位置 = 随访记录的**程控分组 battery** + 基线；V94 预置映射预览 → 确认：f322=2.85 / f313=0.9 落 fu_record、rpt_report.field_values 无参数键；随后转录态提交 → 报告单转 DONE——随访日期由转录 / 补录流程落，OQ-131② / OQ-142②）+ 行为面 `ReportMappingIT`（A 链 ECHO 映射端到端、时相 / 锁定 / 乐观锁 / 权限）、`ReportMappingServiceTest` / `MappingValuesTest`、预置面 `CReportPresetSqlTest`（映射 21 项与 V90 交叉核对）、`ReportConfigIT`（映射配置可见性） |
| C-ACC-34 移除映射目标位置 | 已写入参数不清除、状态回「待录入」 | `cAcc34ProgramReportMapsIntoProgramGroupThenReverts`（移除 battery 分组位置 → 状态 PENDING、f322/f313 仍在记录、记录仍 SUBMITTED）+ 行为面同上回退单测 |
| 含植入 / 程控分组位置（C-ACC-12 机器面 · E21-T27 份额） | 植入记录位置 E21-T32 前不可选；程控分组锚点须在记录固化版本分组清单内 | `positionGuardsRejectImplantAnchorWhileFeatureOff`（E21-T32 起改闸门口径：本套 implant 开关关闭 → 创建侧 40301；remove 侧不在册 40001；未知分组键 → 40001「随访分组不存在」；合法程控分组 battery 落行）+ 行为面 `ReportServiceTest` 植入位置系列（创建 / 开关 / 越患者 / 未知锚点 / 三粒度落行） |
| C-ACC-12 位置锚点完整链路（E21-T32 承接补行） | C 报告单可选基线资料（患者登记分组、植入记录整条或器械 / 电极）与随访记录（整条或程控分组）为位置；左栏跳转到基线资料页签中的植入记录；分组条已填 / 应填（程控含标红数） | 机器面：`CReportImplantLocationIT`（位置树植入根〔当前器械置顶 + device / lead 分组〕、三粒度落行〔模板版本 = C_IMPLANT 当前发布版〕、唯一性 40001、未知锚点 40001、越患者 40400、开关关闭 40301、修改位置 add+remove、患者内清单 baseline 侧含仅植入位置 + 标签「植入记录#N 日期 · 器械信息」）+ `ReportServiceTest`（树 / 标签 / scope）+ `ReportLocationFallbackServiceTest` 植入退回 3 例 + `ReportLegacyLocationMigrationServiceTest` IMPLANT 3 例；展示面：`implant-anchor-logic.spec`（锚点 key / 已填应填口径 OQ-151 / 分组条条目）、`attachment-nav-logic.spec`（home=baseline、itemsAtImplant）、`group.spec`（基线侧取数）、`implant-section` 锚点与内联缩略图条、`detail.vue` 左栏跳转切页签滚动定位（#cat=implant-…）、`followup-timeline` 分组条标红数（records/{id} 新增 highlightHits）——浏览器走查随 E21-T29 集成测试执行 |

## 3. 边界口径（实现读法，评审用例未覆盖的形态）

- **「移除其随访记录位置」的粒度**：报告单移除随访位置后**仍保有另一条随访位置**（含同场 add+remove 换记录）不触发回退——转录锚点可能指向任一在位记录，不误退（错误保持 DONE 可经手工改状态纠正；两读法在 C-ACC-29 / 34 的验收场景〔单记录位置〕行为一致）。规格未写多位置形态（C-SCAN-010 位置可多选、C-SCAN-012 转录只选一条），按「不误退」方向实现；若产品要求精确到「被移除的恰是转录目标记录」（需在 rpt_report 落转录目标列），随 E21-T32 位置模型扩展一并定夺。
- **退回作业的触发面**：E16-T15 巡检**移除**随访行（记录已不存在）→ 同口径回退；**分组退回整条**（记录仍在）不触发——随访位置未失去。基线位置移除 / 退回不影响电子化状态（转录锚点是随访记录）。
- **报告单被删除**：见 §2 C-ACC-29 行末——软删除即出列，状态列无消费者，不设状态动作（与规格「被删除……回到待录入」的可观察效果等价）。

## 4. 其余指派用例的承载与缺口

| 用例 | 状态 | 说明 |
|---|---|---|
| C-ACC-31 待归档区（C-SCAN-009，P1） | **未执行（开发任务未落地）** | 承接任务 E21-T16（S22 · W43–W44，晚于本任务）：未指定患者的文件进待归档区、逐个指定患者 / 类型 / 位置（≥1）后归档、按科室病区隔离。接口尚未实现（docs/api/report.md §4 为推断口径）；E21-T16 落地后由其随附用例 + 本表补行执行 |
| C-ACC-12 位置锚点完整链路 | 已承接（E21-T32） | 机器面与展示面用例见 §2 补行；E21-T27 份额只锁定守卫（现为开关闸门 40301 口径） |
| C-ACC-33 指标模板带出 | 通过（既有承载） | `CReportPresetSqlTest`（凝血四项 6 项名称 / 代码 / 单位随包）+ `ReportConfigIT`（配置接口可见）+ `ReportServiceTest.createRejectsIndicatorOutsideNameTemplate`（模板外指标 40001）；↑ / ↓ 参考范围解析 `ReportServiceTest.indicatorFlagParsesRangesTolerantly` |
| C-ACC-35 批量上传 | 通过（既有承载） | E16-T03 批量链路（`FileServiceAcceptanceIT` / `ReportServiceTest.batchMergeRejectsFileCountOverPerReportLimit` 等：每文件一份 / 合并一份、单份 ≤50、单个 ≤20MB、格式白名单）；C-SCAN-007 复用 A 链路（评审 §4 承接列） |
| C-ACC-36 双树 / 检索 / 修改位置 / 模板退回 | 通过（既有承载） | `ReportSearchIT`（类型 / 时间树、位置筛选含 UNASSIGNED、+N 折叠、科室病区隔离）、`ReportServiceTest` 修改位置系列、`ReportLocationFallbackServiceTest` + 位置退回作业 IT（E16-T15：退回 / 合并 / 移除 / 幂等 / `RPT_LOCATION_FALLBACK`） |
| C-ACC-37 预览与下载权限 | 通过（既有承载） | `FileServiceAcceptanceIT`（无下载权限可预览不可下载、下载记审计、水印链路）；C-SCAN-015/016 复用 A 链路 |

## 5. 执行记录

| 批次 | 环境 | 结果 |
|---|---|---|
| 2026-11-14（E21-T32 承接批） | 本机 docker compose MySQL（`CRDP_IT_MYSQL_PORT=13306`）：`CReportImplantLocationIT` 2 例 + 相关回归（ReportIT / ReportLocationIT / ReportSearchIT / ReportLocationFallbackIT / ReportLegacyLocationMigrationIT / CDigitizeTranscribeAcceptanceIT / ImplantRecordIT 等 43 例）全绿；前端 vitest 1827 例全绿 | 通过（C-ACC-12 完整链路机器面与展示面用例落库，浏览器走查随 E21-T29） |
| 2026-11-13（本任务交付批） | 本机 docker compose MySQL（`CRDP_IT_MYSQL_PORT=13306`）：`CDigitizeTranscribeAcceptanceIT` 4 例全绿；`ReportServiceTest` 50 例、`ReportLocationFallbackServiceTest` 11 例全绿（含回退新增 4 例）；全量 `mvn -B spotless:check test` 见 PR 描述 | 通过（§4 C-ACC-31 与 C-ACC-12 完整链路按承接任务顺延） |
| CI | 每次 push 门控执行（与 A / B 全量回归同套） | 见 PR checks |

> 用例文件为唯一机器面载体：新增 / 修改行为时以本表 C-ACC 编号回链；状态流转任一迁移（默认值 / →DONE / DONE→PENDING / 进度口径）缺失即视为 §1 未通过。
