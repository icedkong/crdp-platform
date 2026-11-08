# 首页 Dashboard 与统计（stats）

> 后端：`crdp-followup`（统计子域）。页面：`docs/design/pages.md` §2（首页）；卡片配置见 `system.md` §6。
> 全部路径 **推断，待确认**。需求：A-DASH-001…006、008…014；C-DASH-001…010。**A-DASH-007 生存分析首期不做**。

## 1. 原则

- **一个口径一个查询**：首页卡片、统计页、任务看板用同一个后端查询服务（A-DASH-003、A-DASH-012），不允许前端自己算。
- 全部数字按当前用户数据范围统计（A-DASH-005、C-DASH-008）；范围为空 → 全 0，不报错。
- 结果缓存 60s，缓存 key 含 `tenantId + userId(或范围哈希) + cardCode + 参数`；权限变更（`permissionVersion` 变化）时失效。
- 卡片只返回聚合值；列表型卡片（如接近 ERI、异常标红）返回的患者信息按字段策略脱敏。
- **节点随访率的数据底座是 `st_node_occurrence` 节点随访事实表**（E19-T01，followup V76）：每日 00:10
  在任务创建与状态刷新之后整租户重建（作业编码 `st-node-refresh`，幂等）；`node_rate` / `yearly_rate`
  共用 `NodeOccurrenceStatsService` 聚合（按节点 × 第 N 次出应随访、已随访数，Q38 口径），保证同一筛选下两处数字一致。
  刷新时点与无锚点补录归属等暂行口径见 `docs/open-questions.md` OQ-106。

## 2. 接口

卡片接口已随 E19-T02 落地 `kpi` 与 `trend` 两张卡（`DashboardCardController`），钻取条件**内联在 kpi 响应里**（每项指标带 `drill.target` 与列表页路由 query，前端不自行拼业务条件，原「`POST /stats/drilldown` 返回条件树」的推断不再单独设接口）；E19-T03 落地 `task_board` / `task_table`，E19-T04 落地 `yearly_rate` / `node_rate`，E19-T05 落地 `baseline_completeness` / `followup_completeness`；其余卡片随 E19-T06、E21 接入同一 `{cardCode}` 入口。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/dashboard/layout` | 登录 | 当前用户合并后的卡片 `[{ code, title, order, visible, permission, feature }]`（角色布局 ∩ 功能开关 ∩ 用户隐藏；E19-T06） |
| GET | `/api/hospital/followup/dashboard/cards/{cardCode}?wardId=&from=&to=&timeDimension=&granularity=&groupBy=&scope=&assigneeId=&tab=&planId=&cohortYear=&patientType=&timeField=&year=` | 卡片登记的权限码（已落地各卡均 `followup:dashboard:view`，按钮 plt_menu 234） | 单卡数据；`cardCode` ∈ `kpi` / `trend` / `task_board` / `task_table` / `yearly_rate` / `node_rate` / `baseline_completeness` / `followup_completeness`（其余随后续任务）；`kpi`：`{ style: A\|C, asOfMonth, metrics: [{ code, title, value, available, drill: { target: PATIENT_LIST\|TASK_LIST, query } }] }`；`trend`：`{ timeDimension, granularity, groupBy, from, to, buckets: [{ bucket, total, groups: [{ key, label, count }] }], legend }`（周期连续、空周期补 0）；`task_board`（A-DASH-003/004、C-DASH-003，E19-T03 已落地）：`{ scope, doneColumn, doneFrom, doneTo, columns: [{ status, title, total, tasks: [任务行], drill }] }`——四列待随访 / 进行中 / 逾期 / 本月已完成（C 本周完成，按 `implant` 开关，OQ-107③ 同判定），每列前 20 张卡片（未完成列按窗口结束日升序、已完成列按完成时间倒序，OQ-108），`scope` = MINE 默认 / WARD / ALL（A-DASH-004，↔ 列表页签）、`assigneeId` 负责人筛选（↔ 列表 doctorId），行同任务列表行（脱敏姓名 / 病区 / 节点 / 窗口 / 负责人 / 最近联系结果）；`task_table`（A-DASH-008，E19-T03 已落地）：`{ tab, today, total, tabs: [{ tab: TODAY\|OVERDUE\|MINE, title, total, drill }], items }`——今日任务（今天处于窗口内且未完成五态，含当天到期的跟进任务与站内提醒）/ 超时任务（OVERDUE）/ 我负责的任务（负责人或责任医生为本人且未完成），`tab` 缺省 TODAY，当前页签前 10 行（行同任务列表，可直接进入随访工作台）；各列 / 页签钻取 query 内联（tab / status / windowFrom+windowTo / completedFrom+completedTo，与列表检索同参数）；`yearly_rate`（A-DASH-006，E19-T04 已落地，口径 Q38）：`{ asOf, cohortYear, cohortYears: [年], rows: [{ label: 第N年, dueCount, followedCount, inWindowCount, lostRatePct, deathCount, followRatePct, inWindowRatePct }] }`——年度节点按随访年度 N 一行（患者级去重，多计划并行同一患者同一年度只计一次，OQ-109②），只列有应随访患者的年度；`cohortYear` 出院年份队列筛选、候选随响应下发；`node_rate`（A-DASH-012、C-DASH-004，E19-T04 已落地）：`{ asOf, planId, planName, plans: [{ id, name }], cohortYear, cohortYears, rows: [同上 + label / nodeName / occurrence] }`——一行 = 节点 × 第 N 次（行标签：年度循环 = 第 N 年、其余循环 = 第 N 次、固定节点 = 节点名，OQ-109③），`planId` 缺省预置计划（id 最小的已发布版本）；开关 `node_follow_rate` 关闭返回 40301（前端 `GET /auth/features` 判定后隐藏，AGENTS §5.7）；两卡 `asOf` = 事实表最近刷新日，逾期完成 / 补录 / 历史导入 / 窗口外日期都计入已随访（Q38），点击数字下钻患者列表随 E19-T08 口径核对后接入（OQ-109①）；`baseline_completeness`（A-DASH-010，E19-T05 已落地，口径 Q39）：`{ patientCount, averagePct, medianPct, distribution: [{band, label, count, pct}]（五段：100 / 90–99 / 70–89 / 50–69 / 低于50）, categories: [{categoryKey, label, patientCount, averagePct}], topMissing: [{fieldKey, label, categoryKey, missingCount, missingRatePct}]（前 10，缺失率 = 缺失人数 ÷ patientCount）, patientTypes }`——分母 = 范围内全部患者（未保存分节按 0 计入），单位完整度 = round(已填×100÷应填)（应填 0 视为 100，与患者列表基线完整度列同口径），分类明细按分类内同算法平均（该分类无应填字段的患者不计入该分类分母）；`followup_completeness`（A-DASH-011、C-DASH-009，E19-T05 已落地，口径 Q40）：`{ patientCount, recordCount, averagePct, medianPct, distribution（患者级）, years: [{year, label, recordCount, averagePct, medianPct, distribution（记录级）}]（只列有记录的年度）, topMissing（缺失率 = 缺失记录数 ÷ recordCount）, patientTypes, yearOptions }`——计入的记录 = 已提交的年度随访记录（任务行挂年度循环节点〔子计划 CYCLE+YEAR，随访年度 = 任务次数〕或 E18 导入锚点行；计划外与无锚点补录不计，OQ-110③），卡片汇总按患者平均（患者值 = 其记录平均值）；两卡筛选 `patientType` 患者类型（候选 = 范围内 DISTINCT 值随响应下发）、`from`+`to` 时间范围（基线 `timeField` 出院 DISCHARGE 默认 / 登记 REGISTERED，随访 VISIT 实际随访日期默认 / DISCHARGE 出院——任务录入记录的实际随访日期按模板版本解析 `followup_date` 字段）、随访卡另有 `year`；两卡按当前数据实时计算（Q39 / Q40「统计时点」，无 asOf），**最常缺失字段点击钻取患者列表**：患者检索 `completenessDrill`（见 patient.md §1）与卡片同一计算，topMissing.missingCount = 列表 total |
| GET | `/api/hospital/followup/dashboard/wards` | `followup:dashboard:view` | 病区筛选候选（A-DASH-005 / C-DASH-008「默认本人全部可见病区」，E19-T03 已落地）：`{ wards: [{ id, name }] }`——登录快照带病区清单时按清单交集，全院 / 科室 / 本人快照回落全部启用病区（行级隔离仍由各卡片 `@DataScope` 出行，选到范围外病区结果恒为空） |
| PUT | `/api/hospital/followup/dashboard/user-pref` | 登录 | 用户隐藏 / 显示（P1，A-DASH-014；E19-T06） |
| GET | `/api/hospital/followup/stats/doctor-workload?wardId=` | `followup:dashboard:view` | 医护负责患者数（A-DASH-009，P1） |
| GET | `/api/hospital/followup/stats/scan-progress?wardId=&uploaderId=&year=` | `followup:dashboard:view`；开关 `scan_progress` | C 补录进度（C-DASH-007；E21-T15） |

原「`GET /stats/node-follow-rate`」的推断不再单独设接口（E19-T04 定稿走 `GET /dashboard/cards/node_rate`，与首页同一 `{cardCode}` 入口、「一个口径一个查询」）。

## 3. 卡片目录（`cardCode`，种子配置）

| cardCode | 需求 | 说明 |
|---|---|---|
| `kpi`（E19-T02 已落地） | A-DASH-001、C-DASH-001 | 指标卡，A 与 C 指标不同，按租户配置——暂行以 `implant` 开关判定（开 → C 七项、关 → A 七项，OQ-107③），随 E19-T06 卡片目录落库；C 的 `eriNear` / `scanPending` 数据源随 E21，先行 `available=false` 占位 |
| `trend`（E19-T02 已落地） | A-DASH-002 | 默认按手术日期、按月、不分组、最近 24 个月；时间维度（手术 / 出院 / 入院）× 粒度（月 / 季度 / 年）× 分组（不分组 / 病区 / 性别 / 出院状态 / 患者类型 / 年龄段）可切换；指标暂只有患者数（死亡人数 / 随访完成数与术者 / 手术方式 / 急诊手术分组随后续任务，OQ-107⑤） |
| `task_board` / `task_table`（E19-T03 已落地） | A-DASH-003/004/008、C-DASH-003/008 | 任务看板（待随访 / 进行中 / 逾期 / 本月已完成〔C 本周完成〕，每列前 20 张卡片）与任务表（今日 / 超时 / 我负责，页签显示数量）；计数与任务列表检索同一条件树（scope / 病区 / 负责人 / 窗口 / 完成时间）；病区筛选候选 `GET /dashboard/wards` |
| `yearly_rate`（E19-T04 已落地） | A-DASH-006 | 每年随访率 = 年度节点的节点随访率，口径一致（Q38）：按随访年度 N 一行，应随访 / 已随访（含逾期完成、补录及窗口外日期）/ 随访率（主指标）/ 失访率 / 死亡人数 + 窗口内完成率（参考）；cohortYear 出院年份队列筛选；患者级去重（OQ-109②） |
| `node_rate`（E19-T04 已落地） | A-DASH-012、C-DASH-004 | 分母为窗口已结束的应随访（Q38 同口径）：一行 = 节点 × 第 N 次（固定节点各一行、循环节点按次分行）；先选随访计划（缺省预置计划）；开关 `node_follow_rate` 关闭 40301、前端隐藏（`GET /auth/features`） |
| `baseline_completeness` / `followup_completeness`（E19-T05 已落地） | A-DASH-010/011、C-DASH-009 | 因条件不显示的字段不计入分母（字段级求值复用 FormProgressEngine 口径，E11-T11 共享用例锁定）；暂行口径见 OQ-110（实时全量计算无缓存、随访记录按固化模板版本求值、无锚点补录不计、随访钻取为患者级、患者类型候选 label = 编码） |
| `device_dist` | C-DASH-002 | 器械类型分布 |
| `eri_list` | C-DASH-005 | 接近 ERI 器械（开关 `eri_cycle`） |
| `abnormal_records` | C-DASH-006 | 近 30 天含标红参数的记录，只查看 |
| `scan_progress` | C-DASH-007 | 补录进度 |

`doctor-workload` 同时满足 A-DASH-009 与 C-DASH-010（医护负责患者数，P1）。

## 4. 测试要点

- 同一数据：首页 `node_rate` 与统计页、任务看板计数一致（同一查询）。
- 完整度两卡（E19-T05）：最常缺失字段缺失人数 = 患者列表 `completenessDrill` 检索 total（同一计算）；计划外记录不计入随访完整度；任务录入记录的实际随访日期（表单字段）参与时间筛选。
- 用户只有病区 1 权限时，任何卡片不含病区 2 的数据。
- 60s 内重复请求命中缓存；角色权限变更后立即失效。
