# 首页 Dashboard 与统计（stats）

> 后端：`crdp-followup`（统计子域）。页面：`docs/design/pages.md` §2（首页）；卡片配置见 `system.md` §6。
> 全部路径 **推断，待确认**。需求：A-DASH-001…006、008…014；C-DASH-001…010。**A-DASH-007 生存分析首期不做**。

## 1. 原则

- **一个口径一个查询**：首页卡片、统计页、任务看板用同一个后端查询服务（A-DASH-003、A-DASH-012），不允许前端自己算。
- 全部数字按当前用户数据范围统计（A-DASH-005、C-DASH-008）；范围为空 → 全 0，不报错。
- 结果缓存 60s，缓存 key 含 `tenantId + userId(或范围哈希) + cardCode + 参数`；权限变更（`permissionVersion` 变化）时失效。
- 卡片只返回聚合值；列表型卡片（如接近 ERI、异常标红）返回的患者信息按字段策略脱敏。

## 2. 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/hospital/followup/dashboard/layout` | 登录 | 当前用户合并后的卡片 `[{ code, title, order, visible, permission, feature }]`（角色布局 ∩ 功能开关 ∩ 用户隐藏） |
| GET | `/api/hospital/followup/dashboard/cards/{cardCode}?wardId=&from=&to=&granularity=&groupBy=` | 卡片登记的权限码（多为 `followup:dashboard:view`） | 单卡数据；功能开关关闭 → 40301 |
| PUT | `/api/hospital/followup/dashboard/user-pref` | 登录 | 用户隐藏 / 显示（P1，A-DASH-014） |
| GET | `/api/hospital/followup/stats/doctor-workload?wardId=` | `followup:dashboard:view` | 医护负责患者数（A-DASH-009，P1） |
| GET | `/api/hospital/followup/stats/node-follow-rate?planId=&wardId=` | `followup:dashboard:view`；开关 `node_follow_rate` | 按节点随访率（A-DASH-012、C-DASH-004） |
| GET | `/api/hospital/followup/stats/scan-progress?wardId=&uploaderId=&year=` | `followup:dashboard:view`；开关 `scan_progress` | C 补录进度（C-DASH-007） |
| POST | `/api/hospital/followup/stats/drilldown` | 同对应卡片 | 点指标卡进入列表时生成的筛选条件（返回条件树，前端跳患者 / 任务列表） |

## 3. 卡片目录（`cardCode`，种子配置）

| cardCode | 需求 | 说明 |
|---|---|---|
| `kpi` | A-DASH-001、C-DASH-001 | 指标卡，A 与 C 指标不同，按租户配置 |
| `trend` | A-DASH-002 | 默认按手术日期、按月 |
| `task_board` / `task_table` | A-DASH-003/004/008、C-DASH-003 | 看板与任务表（今日 / 超时 / 我负责） |
| `yearly_rate` | A-DASH-006 | 每年随访率 = 年度节点的节点随访率，口径一致 |
| `node_rate` | A-DASH-012、C-DASH-004 | 分母为窗口已结束的应随访任务 |
| `baseline_completeness` / `followup_completeness` | A-DASH-010/011、C-DASH-009 | 因条件不显示的字段不计入分母 |
| `device_dist` | C-DASH-002 | 器械类型分布 |
| `eri_list` | C-DASH-005 | 接近 ERI 器械（开关 `eri_cycle`） |
| `abnormal_records` | C-DASH-006 | 近 30 天含标红参数的记录，只查看 |
| `scan_progress` | C-DASH-007 | 补录进度 |

`doctor-workload` 同时满足 A-DASH-009 与 C-DASH-010（医护负责患者数，P1）。

## 4. 测试要点

- 同一数据：首页 `node_rate` 与统计页、任务看板计数一致（同一查询）。
- 用户只有病区 1 权限时，任何卡片不含病区 2 的数据。
- 60s 内重复请求命中缓存；角色权限变更后立即失效。
