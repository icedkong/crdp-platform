# 首页 Dashboard 与统计（stats）

> 后端：`crdp-followup`（统计子域）。页面：`docs/design/pages.md` §2（首页）；卡片配置见 `system.md` §6。
> 标为已实现的接口按本文契约执行，其余保留为设计约定。需求：A-DASH-001…006、008…014；C-DASH-001…010。**A-DASH-007 生存分析首期不做**。

## 1. 原则

- **一个口径一个查询**：首页卡片、统计页、任务看板用同一个后端查询服务（A-DASH-003、A-DASH-012），不允许前端自己算。
- 全部数字按当前用户数据范围统计（A-DASH-005、C-DASH-008）；范围为空 → 全 0，不报错。
- 当前不缓存统计结果或患者集合；每次请求按当前权限 / 范围计算。每日事实刷新与实际刷新时间另见 §5.3，不以实时查询时间冒充事实刷新时间。
- 卡片只返回聚合值；列表型卡片（如接近 ERI、异常标红）返回的患者信息按字段策略脱敏。
- **节点随访率的数据底座是 `st_node_occurrence` 节点随访事实表**（E19-T01，followup V76）：每日 00:10
  在任务创建与状态刷新之后整租户重建（作业编码 `st-node-refresh`，幂等）；`node_rate` / `yearly_rate`
  共用 `NodeOccurrenceStatsService` 聚合（按节点 × 第 N 次出应随访、已随访数，Q38 口径），保证同一筛选下两处数字一致。
  刷新时点与无锚点补录归属等暂行口径见 `docs/open-questions.md` OQ-106。
- **Q38 口径由 E19-T08 统计口径核对测试锁定**（`StatsQ38CaliberIT`，MySQL 门控）：一套覆盖 Q38 全部条款的虚构数据，
  手工按口径表逐行算出期望数字后与系统卡片逐格比对（应随访 / 已随访 / 窗口内完成含首尾两天 / 失访 / 死亡 / 三率、
  病区与队列筛选、年度不设上限、计划外不计入、Q06 历史未随访两态、多计划并行与移除的 OQ-106⑤ / OQ-109② 口径）。

## 2. 接口

卡片接口已随 E19-T02 落地 `kpi` 与 `trend` 两张卡（`DashboardCardController`），钻取条件**内联在 kpi 响应里**（每项指标带 `drill.target` 与列表页路由 query，前端不自行拼业务条件，原「`POST /stats/drilldown` 返回条件树」的推断不再单独设接口）；E19-T03 落地 `task_board` / `task_table`，E19-T04 落地 `yearly_rate` / `node_rate`，E19-T05 落地 `baseline_completeness` / `followup_completeness`；其余卡片随 E19-T06、E21 接入同一 `{cardCode}` 入口。

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/dashboard/layout` | 登录（@PublicApi） | **已实现**（E19-T06）。当前用户合并后的卡片：`{ source: DEFAULT\|ROLE_CONFIG, cards: [{ code, title, width: FULL\|HALF, order, visible, allowUserHide, userHidden, permission, feature }] }`——目录（`dash_card`，followup V77）∩ 功能开关（`feature` 关闭 / 无行即不出现）∩ 权限（会话未持 `permission` 即不出现）× 角色布局（`dash_role_layout`，未配置角色用目录默认；多角色取并集、顺序取第一个已配置角色〔角色 id 升序，OQ-111〕）∩ 用户隐藏（visible=false 仅来自 `dash_user_pref`，行保留供「卡片设置」重新显示；allowUserHide=false 的忽略）；cards 按展示顺序、order 从 1 起 |
| GET | `/api/hospital/followup/dashboard/cards/{cardCode}?wardId=&from=&to=&timeDimension=&granularity=&groupBy=&scope=&assigneeId=&tab=&planId=&cohortYear=&patientType=&timeField=&year=` | 卡片登记的权限码（已落地各卡均 `followup:dashboard:view`，按钮 plt_menu 234） | 单卡数据；`cardCode` ∈ `kpi` / `trend` / `task_board` / `task_table` / `yearly_rate` / `node_rate` / `baseline_completeness` / `followup_completeness`（其余随后续任务）；`kpi`：`{ style: A\|C, asOfMonth, metrics: [{ code, title, value, available, drill: { target: PATIENT_LIST\|TASK_LIST\|REPORT_LIST\|FOLLOWUP_STATS, query } }] }`；`trend`：`{ timeDimension, granularity, groupBy, from, to, buckets: [{ bucket, total, groups: [{ key, label, count }] }], legend }`（周期连续、空周期补 0）；`task_board`（A-DASH-003/004、C-DASH-003，E19-T03 已落地）：`{ scope, doneColumn, doneFrom, doneTo, columns: [{ status, title, total, tasks: [任务行], drill }] }`——四列待随访 / 进行中 / 逾期 / 本月已完成（C 本周完成，按 `implant` 开关，OQ-107③ 同判定），每列前 20 张卡片（未完成列按窗口结束日升序、已完成列按完成时间倒序，OQ-108），`scope` = MINE 默认 / WARD / ALL（A-DASH-004，↔ 列表页签）、`assigneeId` 负责人筛选（↔ 列表 doctorId），行同任务列表行（脱敏姓名 / 病区 / 节点 / 窗口 / 负责人 / 最近联系结果）；`task_table`（A-DASH-008，E19-T03 已落地）：`{ tab, today, total, tabs: [{ tab: TODAY\|OVERDUE\|MINE, title, total, drill }], items }`——今日任务（今天处于窗口内且未完成五态，含当天到期的跟进任务与站内提醒）/ 超时任务（OVERDUE）/ 我负责的任务（负责人或责任医生为本人且未完成），`tab` 缺省 TODAY，当前页签前 10 行（行同任务列表，可直接进入随访工作台）；各列 / 页签钻取 query 内联（tab / status / windowFrom+windowTo / completedFrom+completedTo，与列表检索同参数）；`yearly_rate`（A-DASH-006，E19-T04 已落地，口径 Q38）：`{ asOf, cohortYear, cohortYears: [年], rows: [{ label: 第N年, dueCount, followedCount, inWindowCount, lostRatePct, deathCount, followRatePct, inWindowRatePct }] }`——年度节点按随访年度 N 一行（患者级去重，多计划并行同一患者同一年度只计一次，OQ-109②），只列有应随访患者的年度；`cohortYear` 出院年份队列筛选、候选随响应下发；`node_rate`（A-DASH-012、C-DASH-004，E19-T04 已落地）：`{ asOf, planId, planName, plans: [{ id, name }], cohortYear, cohortYears, rows: [同上 + label / nodeName / occurrence] }`——一行 = 节点 × 第 N 次（行标签：年度循环 = 第 N 年、其余循环 = 第 N 次、固定节点 = 节点名，OQ-109③），`planId` 缺省预置计划（id 最小的已发布版本）；开关 `node_follow_rate` 关闭返回 40301（前端 `GET /auth/features` 判定后隐藏，AGENTS §5.7）；两卡 `asOf` = 事实表最近刷新日，逾期完成 / 补录 / 历史导入 / 窗口外日期都计入已随访（Q38），点击数字进入按年度患者 / 节点次数计数的专用明细（OQR-05B，§5）；`baseline_completeness`（A-DASH-010，E19-T05 已落地，口径 Q39）：`{ patientCount, averagePct, medianPct, distribution: [{band, label, count, pct}]（五段：100 / 90–99 / 70–89 / 50–69 / 低于50）, categories: [{categoryKey, label, patientCount, averagePct}], topMissing: [{fieldKey, label, categoryKey, missingCount, missingRatePct}]（前 10，缺失率 = 缺失人数 ÷ patientCount）, patientTypes }`——分母 = 范围内全部患者（未保存分节按 0 计入），单位完整度 = round(已填×100÷应填)（应填 0 视为 100，与患者列表基线完整度列同口径），分类明细按分类内同算法平均（该分类无应填字段的患者不计入该分类分母）；`followup_completeness`（A-DASH-011、C-DASH-009，E19-T05 已落地，口径 Q40）：`{ patientCount, recordCount, averagePct, medianPct, distribution（患者级）, years: [{year, label, recordCount, averagePct, medianPct, distribution（记录级）}]（只列有记录的年度）, topMissing（缺失率 = 缺失记录数 ÷ recordCount）, patientTypes, yearOptions }`——计入的记录 = 已提交的年度随访记录（任务行挂年度循环节点〔子计划 CYCLE+YEAR，随访年度 = 任务次数〕或 E18 导入锚点行；计划外与无锚点补录不计，OQ-110③），卡片汇总按患者平均（患者值 = 其记录平均值）；两卡筛选 `patientType` 患者类型（候选 = 范围内 DISTINCT 值随响应下发）、`from`+`to` 时间范围（基线 `timeField` 出院 DISCHARGE 默认 / 登记 REGISTERED，随访 VISIT 实际随访日期默认 / DISCHARGE 出院——任务录入记录的实际随访日期按模板版本解析 `followup_date` 字段）、随访卡另有 `year`；两卡按当前数据实时计算（Q39 / Q40「统计时点」，无 asOf），**最常缺失字段点击钻取统计明细**：基线按患者、随访按年度记录计数，topMissing.missingCount = 明细 total（§5）；旧患者检索 completenessDrill 保留兼容，不作为记录级缺失计数的替代 |
| POST | `/api/hospital/followup/stats/drill` | `followup:dashboard:view` 且 `followup:patient:view` | **OQR-05B 已实现**。按统计单位分页读取脱敏患者、年度 / 次数及记录定位，口径与请求字段见 §5；NODE 另受 node_follow_rate 开关控制 |
| GET | `/api/hospital/followup/dashboard/wards` | `followup:dashboard:view` | 病区筛选候选（A-DASH-005 / C-DASH-008「默认本人全部可见病区」，E19-T03 已落地）：`{ wards: [{ id, name }] }`——登录快照带病区清单时按清单交集，全院 / 科室 / 本人快照回落全部启用病区（行级隔离仍由各卡片 `@DataScope` 出行，选到范围外病区结果恒为空） |
| PUT | `/api/hospital/followup/dashboard/user-pref` | 登录（@PublicApi） | **已实现**（E19-T06，A-DASH-014 P1）。`{ hidden: [cardCode] }` 全量覆盖本人隐藏清单（空 = 恢复角色默认）；只能在角色允许且 allowUserHide 的卡片内隐藏（否则 40001）；用户不能加入角色未允许的卡片、不能调顺序（无顺序字段）；写 `DASHBOARD_USER_PREF` 审计；返回与 GET /layout 同构的新布局 |
| GET | `/api/hospital/followup/stats/doctor-workload?wardId=` | `followup:dashboard:view` | **已实现**（E19-T07 / E19 统计页，A-DASH-009 + C-DASH-010，P1）。`{ asOfMonth, asOf, rows: [{ doctorId, doctorName, totalPatients 负责患者数, activePatients 在随访, monthDue 本月应随访, overdue 逾期, rateDueCount / rateFollowedCount / followRatePct 每年随访率（Q38 口径，患者 × 年度去重汇总 = 各年度行之和）, totalDrill / activeDrill / monthDueDrill / overdueDrill }] }`——按**患者责任医生**归口（面板口径，行锚点 = 至少负责 1 名在册患者的医生、按负责患者数降序，OQ-118）：患者两列 = 患者列表 `filters.doctorId` 同条件树、本月应随访 = 指标卡同一去重集合（含未建单次数，§5）、逾期 = `status=OVERDUE`、随访率来自 `st_node_occurrence`（`asOf` = 最近刷新日，患者 / 任务列实时）；本月应随访进入 FOLLOWUP_STATS 专用明细，其他列沿用内联钻取（患者列表 `doctorId`〔/ `status=ACTIVE`〕带 `doctorName` 供标签展示、任务列表 `doctorId` + `tab=all` + `dueFrom`/`dueTo` / `status=OVERDUE`，列表页按路由 query 解析并显示可关闭的「责任医生」标签）；无责任医生的患者不出行；wardId 病区收窄（范围内无患者 → 空 rows）；统计页菜单 plt_menu 42（平台库 V61，路由 `/followup/stats`，页签：每年随访率 / 节点随访率〔开关〕/ 数据完整度 / 医护负责患者数） |
| GET | `/api/hospital/followup/dashboard/cards/scan_progress?wardId=&uploaderId=&year=` | `followup:dashboard:view`；开关 `scan_progress` | C 补录进度（C-DASH-007）。**已实现（E21-T15）**：`{ pendingCount, doneCount, noEntryCount, donePct（已电子化 ÷〔待录入 + 已电子化〕百分整数——「无需录入」不进分母，分母 0 为 0）, backfillRecordCount（补录随访记录条数 = 已提交的 source=BACKFILL fu_record，只收病区筛选）, uploaders[]（候选）, years[]（文档年份 = report_date 年份，降序） }`；筛选 wardId 科室病区（页面级）、uploaderId 上传人（rpt_report.created_by）、year 文档年份；计数与全院报告单检索（E16-T05）同一条件树（NORMAL 在册、`digitize_status IS NOT NULL`——A 租户行恒 NULL 不计），数据范围 `@DataScope(resource="patient")` 出行（范围内无扫描件全 0 不报错）；开关关闭（含无行）40301、前端隐藏。原「GET /stats/scan-progress」推断不再单独设接口，与 kpi 等卡片同一 `{cardCode}` 入口（§2 表头口径） |
| GET | `/api/hospital/followup/dashboard/cards/device_dist?wardId=` | `followup:dashboard:view`；开关 `implant` | C 器械类型分布（C-DASH-002）。**已实现（E21-T19）**：`{ followedCount（范围内在随访患者数，与指标卡「在随访患者数」同条件树）, rows: [{ deviceType, label, count, pct（count ÷ followedCount 百分整数，分母 0 为 0）, brands: [{ brand, label, count }] }] }`——按在用植入记录（pt_implant.status=ACTIVE，一人至多一条）的起搏器类型统计在随访患者数、可按品牌拆分；行序与品牌序按业务字典 sortNo（含停用项，C-FLD-009），无在用器械的患者归 `deviceType=null`「未登记在用器械」行（品牌未填归「未填品牌」）；数据范围 `@DataScope(resource="patient")` 出行；开关关闭（含无行）40301、前端隐藏。目录行随 followup V96（OQ-146③⑤） |
| GET | `/api/hospital/followup/dashboard/cards/eri_list?wardId=` | `followup:dashboard:view`；开关 `eri_cycle` | C 接近 ERI 器械列表（C-DASH-005）。**已实现（E21-T19）**：`{ total（与指标卡「接近 ERI 器械数」同查询）, items: [{ patientId, name（脱敏）, visitNo, deviceType/label, brand/label, model, lastVisitDate（pt_patient_summary.latest_followup_date，缺失回落证据随访日期）, nextPlanDate（summary.next_plan_date，无在组计划 null）, eriStatusCode（0/1/2）, eriStatusName, batteryLife }], batteryLifeThreshold（年，=1）, limit（20） }`——患者 = 随访中（followup_status=ACTIVE、在册）∩ 范围 ∩ 病区（已死亡 / 已终止 / 失访不出行）；命中判定 = 每患者**最近一条**含 ERI 字段（f323 / f324，OQ-130①）的已提交记录按有效随访日期（visit_date ?? 表单 f303 ?? 任务计划日期）取最新，谓词与计划切换 SW-7 / SW-8 共享 `plan.domain.EriEval`（接近 / 已达，或电池寿命 ≤ 1 年；E21-T06 之前历史行、fu_enrollment.mode 派生标记不参与，OQ-146④）；items 按证据随访日期倒序前 20 行；行点击进患者详情；数据范围 `@DataScope` 出行；开关关闭（含无行）40301、前端隐藏（开关归属见 OQ-146①） |
| GET | `/api/hospital/followup/dashboard/cards/abnormal_records?wardId=&days=` | `followup:dashboard:view`；开关 `implant` | C 异常标红记录（C-DASH-006）。**已实现（E21-T19）**：`{ days（缺省 30，1–365）, since（窗口起点业务日）, total, unknownSubmittedAtCount（当前范围内首次提交时间未知的标红记录数）, limit（20）, items: [{ recordId, patientId, name（脱敏）, visitNo, visitDate（visit_date ?? 任务计划日期近似，OQ-134①）, submittedAt（first_submitted_at，ISO-8601 +08:00）, hits: [{ key（标红项目）, message（规则文案）, previousValue, change, changeText（变化率） }] }] }`——近 N 天**提交**的含标红参数（fu_record.highlight，E17-T04 与录入页实时标红同源）的已提交随访记录，只做查看不需处理（无处置动作）；软删行不在册（deleted=0）；days=30 时 total 与指标卡「近 30 天含标红参数的记录数」同一条件树（「近 N 天提交」按 first_submitted_at 事实，OQR-05A）；行点击进患者详情随访记录页签并定位该记录（`?tab=followup` + `#rec=<recordId>`）；数据范围 `@DataScope` 出行；开关关闭（含无行）40301、前端隐藏（compare_highlight 是 A 也开的能力、不能作本卡闸门，OQ-146③） |

原「`GET /stats/node-follow-rate`」的推断不再单独设接口（E19-T04 定稿走 `GET /dashboard/cards/node_rate`，与首页同一 `{cardCode}` 入口、「一个口径一个查询」）。

## 3. 卡片目录（`cardCode`，种子配置）

目录已随 E19-T06 落库（`dash_card`，followup V77）：固定卡片清单随卡片发版以迁移落库，无运行期增删改接口；列含 `title` / `width`（FULL 整行 / HALF 半行）/ `default_visible` / `default_order` / `permission_code` / `feature_code` / `allow_user_hide`。医院 A / B 的默认差异走 `default_visible`（节点随访率 A 默认 0，B / C 初始化后经角色布局开启，A 规格 §14），不写死租户编码。E21 的 C 卡片（device_dist / eri_list / abnormal_records 随 V96、scan_progress 随 V85）已全部追加目录行；三卡 default_order 45 / 62 / 63 落在医院 C 默认顺序位（pages.md §2；「并列」以整行上下排布实现——目录宽度全租户共享、跨区并排不可表达，OQ-146②）。

| cardCode | 需求 | 说明 |
|---|---|---|
| `kpi`（E19-T02 已落地） | A-DASH-001、C-DASH-001 | 指标卡，A 与 C 指标不同，按租户配置——暂行以 `implant` 开关判定（开 → C 七项、关 → A 七项，OQ-107③），随 E19-T06 卡片目录落库；`eriNear`（接近 ERI 器械数）已随 E21-T19 落地——`eri_cycle` 开关开启时出数（与 eri_list 卡片 total 同一查询）、关闭仍 `available=false` 占位；`scanPending`（待录入扫描件数）已随 E21-T15 落地——`scan_progress` 开关开启时出数并钻取全院报告单列表 `digitizeStatus=PENDING`（drill target=REPORT_LIST），关闭仍占位 |
| `trend`（E19-T02 已落地） | A-DASH-002 | 默认按手术日期、按月、不分组、最近 24 个月；时间维度（手术 / 出院 / 入院）× 粒度（月 / 季度 / 年）× 分组（不分组 / 病区 / 性别 / 出院状态 / 患者类型 / 年龄段）可切换；指标暂只有患者数（死亡人数 / 随访完成数与术者 / 手术方式 / 急诊手术分组随后续任务，OQ-107⑤） |
| `task_board` / `task_table`（E19-T03 已落地） | A-DASH-003/004/008、C-DASH-003/008 | 任务看板（待随访 / 进行中 / 逾期 / 本月已完成〔C 本周完成〕，每列前 20 张卡片）与任务表（今日 / 超时 / 我负责，页签显示数量）；计数与任务列表检索同一条件树（scope / 病区 / 负责人 / 窗口 / 完成时间）；病区筛选候选 `GET /dashboard/wards` |
| `yearly_rate`（E19-T04 已落地） | A-DASH-006 | 每年随访率 = 年度节点的节点随访率，口径一致（Q38）：按随访年度 N 一行，应随访 / 已随访（含逾期完成、补录及窗口外日期）/ 随访率（主指标）/ 失访率 / 死亡人数 + 窗口内完成率（参考；口径 G2 修正：按**表单实际随访日期**落在窗口内判定，含首尾两天——实际随访日期 = `fu_record.visit_date`，任务录入记录按记录模板版本解析表单字段 `followup_date`〔A 规格 f214〕，补录日期落在原窗口内的计入；不再按完成方式〔提交日〕判定，实际随访日期未知的行不计）；cohortYear 出院年份队列筛选；患者级去重（OQ-109②） |
| `node_rate`（E19-T04 已落地） | A-DASH-012、C-DASH-004 | 分母为窗口已结束的应随访（Q38 同口径）：一行 = 节点 × 第 N 次（固定节点各一行、循环节点按次分行）；先选随访计划（缺省预置计划）；开关 `node_follow_rate` 关闭 40301、前端隐藏（`GET /auth/features`） |
| `baseline_completeness` / `followup_completeness`（E19-T05 已落地） | A-DASH-010/011、C-DASH-009 | 因条件不显示的字段不计入分母（字段级求值复用 FormProgressEngine 口径，E11-T11 共享用例锁定）；暂行口径见 OQ-110（实时全量计算无缓存、随访记录按固化模板版本求值、无锚点补录不计；OQR-05B 新明细按年度记录定位，患者类型候选使用业务只读字典标签（旧未知码回落编码）） |
| `device_dist`（E21-T19 已落地） | C-DASH-002 | 器械类型分布：在随访患者按在用器械类型统计、可按品牌拆分（followup V96 目录行，`implant` 开关关闭时布局不含本卡） |
| `eri_list`（E21-T19 已落地） | C-DASH-005 | 接近 ERI 器械（开关 `eri_cycle`）：最近一次 ERI 状态为接近ERI或已达ERI(EOI)、或预计电池寿命 ≤ 1 年的患者，含器械、最近随访日期、下次计划日期（目录行随 followup V96；开关归属暂行口径 OQ-146①） |
| `abnormal_records`（E21-T19 已落地） | C-DASH-006 | 近 N 天（默认 30 天）提交的含标红参数的记录，显示标红项目与变化率，点击进入记录，只查看（`implant` 开关闭卡，OQ-146③） |
| `scan_progress`（E21-T15 已落地） | C-DASH-007 | 补录进度：待录入 / 已电子化 / 无需录入份数与已电子化比例 + 补录随访记录条数；上传人 / 文档年份筛选（病区页面级）；目录行随 followup V85 落库（`scan_progress` 开关关闭时布局不含本卡） |

`doctor-workload` 同时满足 A-DASH-009 与 C-DASH-010（医护负责患者数，P1）。

## 4. 测试要点

- 同一数据：首页 `node_rate` 与统计页、任务看板计数一致（同一查询）。
- 医护负责患者数（E19-T07）：患者两列 = 患者列表 `doctorId` 筛选 total（同一条件树）；每年随访率按患者 × 年度去重汇总（与 `yearly_rate` 同事实表，各年度行之和）；无责任医生的患者不出行；按责任医生归口的暂行口径见 OQ-118。
- 完整度两卡：最常缺失字段计数 = 专用明细 total（基线按患者、随访按年度记录，同一计算）；计划外记录不计入随访完整度；任务录入记录的实际随访日期（表单字段）参与时间筛选。
- C 三卡（E21-T19）：`eri_list.total` = 指标卡 `eriNear`（同查询）；`abnormal_records` days=30 的 total = 指标卡 `highlightRecent`（同条件树）；`device_dist` 分母 = 指标卡「在随访患者数」（同条件树）；ERI 命中与计划切换 SW-7 / SW-8 同一谓词（EriEval）。
- **C 卡片数与列表核对由 E21-T28 测试锁定**（`CDashboardCardsCaliberIT`，MySQL 门控）：同一套虚构数据下用独立列表接口重算卡片数字——`device_dist` 分母 = 患者列表 `status=ACTIVE` 检索 total、每类型 / 品牌人数 = 患者列表逐患者植入记录（current 行）独立重算、各行之和 = 分母；`eri_list` 命中患者 ⊆ 患者列表 ACTIVE、器械类型显示名 / 下次计划日期 = 概览 `device.typeLabel` / `nextVisit.planDate`、最近随访日期 = 概览 `latestFollowup.date`（E15 写入方落地前概览为 null，卡片按 §2 回落证据记录有效随访日期 = 记录详情 `visitDate`，口径锁定）、状态命中行 ERI 文案 = 概览 ERI 标记（电池视同命中行概览「视同接近ERI」、卡片按字典状态展示）；`abnormal_records` 每行命中数 = 记录详情 `highlightHits` = 时间轴节点 `highlightCount`、患者列表逐患者时间轴标红记录数 = 卡片 total、窗口外（first_submitted_at 回拨）记录不进卡片与指标卡但仍在详情与时间轴（差异只在窗口，OQ-107④）；卡片 `wardId` 与患者列表 `wardIds` 筛选同一收窄（转病区后双侧同步）。
- 用户只有病区 1 权限时，任何卡片不含病区 2 的数据。
- 重复请求与翻页重新校验权限 / 范围，不复用旧患者集合；权限或范围改变后不显示旧明细。


### OQR-05A 提交时间窗口与未知值

指标卡 highlightRecent 与 abnormal_records 的计数 / 明细统一按 first_submitted_at ≥ 北京业务日窗口起点查询，修改、映射、重算不会把旧记录重新算作近期提交。null 不归入任何日期窗口，也不计入 total。abnormal_records 另返回 unknownSubmittedAtCount：当前数据范围与病区内、在册已提交且含标红参数、首次提交时间未知的数量（不限窗口，因为无法判断所属日期）；卡片明确提示这部分未计入近 N 天。姓名仍脱敏，未知数量也经过数据范围默认拒绝。原 visitDate 的有效日期回落与临床完整度日期口径本项未重定义。

应随访集合、统计钻取与医院 C 补录关联已分别在 OQR-05B / OQR-01 本地实施；本节只说明 OQR-05A 的时间事实，部署和医院验收另行执行。


## 5. 统计集合与明细（OQR-05B，2026-11-19）

### 5.1 本月应随访

KPI 与医护负责患者数的本月应随访共用 MonthlyDueService：在当前范围和病区内，读取 ACTIVE / PAUSED 入组与有效患者，按固定的计划版本、起算快照、模式、滚动状态和医生指定日期调用现有 TaskCreationPlanner。将月内尚未生成任务的次数与已生成常规任务按 `(enrollmentId, nodeId, occurrence)` 去重。既有任务日期优先，即使某次已改到另一个月，也不能再由默认排期生成一个本月虚拟副本。

延续原常规节点类型口径（不额外把站内提醒改成随访表单），排除临时和跟进任务；已移除 / 已切换入组及死亡 / 终止 / 失访患者不投射新次数。暂停仍纳入应随访，不在查询时恢复或生成任务。滚动节点只按现有调度规则投射可计算的下一次，不推测尚未发生的随访测量。查询不落任务、不改变 next_plan_date 或医生指定字段。

KPI 的 monthDue.drill 与医护表 monthDueDrill 改为 `target: FOLLOWUP_STATS`，query 含 `{ kind: MONTH, from, to, wardId?, doctorId? }`，由前端打开统计明细抽屉；不能再拿普通任务列表总数代替包含虚拟次数的总数。医护行按患者当前责任医生分组，无责任医生的次数仍计入 KPI、不分摊到医生行。其他既有医护列沿用 OQ-118 口径，本项不改任务负责人匹配规则。

KPI 其他列表钻取携带所选 wardId，患者 / 任务 / 报告列表接收该病区，避免卡片按某病区统计、列表却显示全部可见病区。

### 5.2 统一明细接口

POST body：`{ kind, metric?, wardId?, planId?, nodeId?, occurrence?, year?, cohortYear?, patientType?, fieldKey?, from?, to?, timeField?, doctorId?, pageNo?, pageSize?, expectedCount? }`。日期使用 YYYY-MM-DD，ID 可用十进制字符串。pageNo 从 1 起、pageSize 默认 20（最大 100，界面固定 20）；非法参数 40001。

| kind | 统计单位与条件 |
|---|---|
| MONTH | 入组 × 节点 × 次数；from / to 为同一有效月份范围，缺省当前北京业务月；可再按责任医生收窄 |
| YEAR | 第 year 年的患者，沿用 Q38 对患者去重；保留该患者所有符合当前指标的年度 / 节点上下文；可按 wardId / cohortYear 筛选 |
| NODE | 指定 planId / nodeId / occurrence 的入组次数；不把多次入组强行去重成一名患者，关闭 node_follow_rate 为 40301 |
| BASELINE_ALL / BASELINE_MISSING | 基线患者全集 / 缺 fieldKey 的患者；沿用完整度的病区、患者类型及时间维度筛选 |
| FOLLOWUP_ALL / FOLLOWUP_MISSING | 已提交年度记录全集 / 缺 fieldKey 的记录；返回患者、年度和对应 recordId，可用 year 限定年度；不强行纳入无年度锚点补录 |

YEAR / NODE 的 metric 白名单为 DUE（默认）、FOLLOWED、IN_WINDOW、LOST、DEATH，始终先限定 due=1；与卡片按同一病区 / 队列、年度或节点次数计算。比例仍由原计数计算，不新增另一套分母。完整度求值继续按保存的模板版本和条件显示规则，不从客户端字段列表推断。

响应 `{ items, total, pageNo, pageSize, unit, timeBasis, refreshedAt, changed }`；每条 item 为 `{ key, patientId, name（脱敏或策略隐藏）, contexts: [{ enrollmentId?, planId?, planName?, nodeId?, nodeName?, occurrence?, year?, planDate?, taskId?, recordId?, virtual }] }`。MONTH 的 virtual=true 仅表示该排期尚未生成任务；可查看患者，不提供虚假的任务操作。年度患者去重后可有多个 contexts，节点 / 记录明细按其原统计单位分行，total 与对应卡片计数同粒度。

权限为 dashboard:view 与 patient:view 的交集；缺任一项 40300。数据范围为空返回空列表，重新请求 / 翻页按当前令牌权限范围计算，不缓存患者集合。姓名始终脱敏并遵守 HIDDEN / 租户视角字段规则；记录跳转仍单独经过原 task:view 权限校验。新增 STATS_DRILL 审计不记录请求载荷、姓名或临床明文。

expectedCount 仅用于提示：当前 total 与点击时计数不同时 changed=true，明细展示当前可见结果并提示统计 / 范围已变化，不返回旧范围患者，不把客户端计数作为授权依据。

### 5.3 实时与每日汇总时点

MONTH 与完整度明细 timeBasis=LIVE，以同一次只读事务内的实时集合计算；YEAR / NODE 为 DAILY，沿用每日节点事实表，不承诺提交后立即重算全部随访率。

V144 新增 st_refresh_state，事实重建与成功刷新时间同事务写入；失败时事实和刷新标记一并回滚。yearly_rate / node_rate 增加 refreshedAt（ISO-8601 +08:00），rows 增加 nodeId 供精确节点钻取，原 asOf 仍是逻辑日。旧汇总没有可靠实际刷新时刻时 refreshedAt 为 null，不从逻辑日期伪造时间。该表是运行时统计元数据，配置模板明确不导出。界面展示实际刷新时刻，并将旧未知时刻标明。

无新增 60 秒缓存、结果快照服务或自动刷新承诺；原跨日 / 暂停 / 计划切换 / 补录和删除的统计口径按原事实刷新保留。数字与明细在同一数据时点、相同权限范围下对应；两次请求之间刷新或授权变化会显示 changed 提示。
