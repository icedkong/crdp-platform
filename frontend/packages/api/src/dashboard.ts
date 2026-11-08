/**
 * E19-T02 / A-DASH-001、A-DASH-002、C-DASH-001；E19-T03 / A-DASH-003、004、005、008、C-DASH-003、008；
 * E19-T04 / A-DASH-006、A-DASH-012、C-DASH-004：
 * 首页指标卡、趋势图、任务看板、任务表与随访率卡片的类型与纯逻辑
 * （接口契约 docs/api/stats.md §2、§3，后端 `com.crdp.followup.dashboard.api.DashboardCardController`；
 * 页面 pages.md §2 首页）。
 *
 * 「数字与列表钻取一致」：后端卡片与列表接口共用同一条件树，响应内联每项指标 / 每列 /
 * 每页签的钻取目标（target + query）；前端用 `kpiMetricRoute` / `boardColumnRoute` /
 * `taskTableTabRoute` 把它换成列表页路由（患者列表 /followup/patient?status=…、任务列表
 * /followup/task?tab=all&status=…），不自行拼业务条件。数据源未落地的指标（C 的 ERI /
 * 扫描件随 E21）available=false，前端隐藏。随访率卡片（Q38 口径）数据时点 = 节点随访
 * 事实表最近一次每日刷新（asOf 回显）；点击数字下钻患者列表需要患者检索支持按随访
 * 年度 / 节点筛选，随 E19-T08 口径核对后接入（OQ-109①）。
 */
import type { RouteLocationRaw } from 'vue-router';

import type { TaskRow } from './task';

/** 一项指标的钻取目标（query 为列表页路由 query 参数）。 */
export interface KpiDrill {
  query?: Record<string, unknown>;
  target: 'PATIENT_LIST' | 'TASK_LIST';
}

/** 指标卡单行（value=null 且 available=false 表示数据源随 E21 落地，前端隐藏）。 */
export interface KpiMetric {
  available: boolean;
  code: string;
  drill?: KpiDrill | null;
  title: string;
  value?: null | number;
}

/** 指标卡数据（style=A 医院 A 七项 / C 医院 C 七项，按 implant 开关；asOfMonth=「本月」口径月）。 */
export interface KpiCardView {
  asOfMonth: string;
  metrics: KpiMetric[];
  style: 'A' | 'C';
}

/** 趋势图分组取值（图例：key 为后端分组键，label 为中文展示）。 */
export interface TrendGroup {
  key: string;
  label: string;
}

/** 趋势图周期内一个分组的计数。 */
export interface TrendGroupCount {
  count: number;
  key: string;
  label: string;
}

/** 趋势图周期（bucket 键：YYYY-MM / YYYY-Qn / YYYY；groups 与 legend 对齐、空周期补 0）。 */
export interface TrendBucket {
  bucket: string;
  groups: TrendGroupCount[];
  total: number;
}

/** 趋势图数据（回显生效参数；默认手术日期 · 月 · 不分组 · 最近 24 个月）。 */
export interface TrendCardView {
  buckets: TrendBucket[];
  from: string;
  granularity: TrendGranularity;
  groupBy: TrendGroupBy;
  legend: TrendGroup[];
  timeDimension: TrendTimeDimension;
  to: string;
}

export type TrendGranularity = 'MONTH' | 'QUARTER' | 'YEAR';
export type TrendGroupBy =
  | 'AGE_BAND'
  | 'DISCHARGE_STATUS'
  | 'GENDER'
  | 'NONE'
  | 'PATIENT_TYPE'
  | 'WARD';
export type TrendTimeDimension = 'ADMISSION' | 'DISCHARGE' | 'SURGERY';

/** 趋势图请求参数（全可空：后端回落默认值；from/to 为 YYYY-MM-DD 闭区间）。 */
export interface TrendCardParams {
  from?: string;
  granularity?: TrendGranularity;
  groupBy?: TrendGroupBy;
  timeDimension?: TrendTimeDimension;
  to?: string;
  wardId?: number;
}

/** 趋势图筛选选项（pages.md §2 首页趋势图可选项，A 规格 §9）。 */
export const TREND_TIME_DIMENSION_OPTIONS: Array<{
  label: string;
  value: TrendTimeDimension;
}> = [
  { label: '手术日期', value: 'SURGERY' },
  { label: '出院日期', value: 'DISCHARGE' },
  { label: '入院日期', value: 'ADMISSION' },
];

export const TREND_GRANULARITY_OPTIONS: Array<{
  label: string;
  value: TrendGranularity;
}> = [
  { label: '月', value: 'MONTH' },
  { label: '季度', value: 'QUARTER' },
  { label: '年', value: 'YEAR' },
];

export const TREND_GROUP_BY_OPTIONS: Array<{
  label: string;
  value: TrendGroupBy;
}> = [
  { label: '不分组', value: 'NONE' },
  { label: '病区', value: 'WARD' },
  { label: '性别', value: 'GENDER' },
  { label: '出院状态', value: 'DISCHARGE_STATUS' },
  { label: '患者类型', value: 'PATIENT_TYPE' },
  { label: '年龄段', value: 'AGE_BAND' },
];

/**
 * 指标 → 列表页路由（A-DASH-001「点击进入对应列表」）：
 * 患者列表带 status（列表页按随访状态筛选）、任务列表带 tab=all 与 status /
 * dueFrom / dueTo（口径由后端下发，前端只透传）；无钻取目标返回 null。
 */
export function kpiMetricRoute(metric: KpiMetric): null | RouteLocationRaw {
  const drill = metric.drill;
  if (!drill) return null;
  if (drill.target === 'PATIENT_LIST') {
    return { path: '/followup/patient', query: drill.query ?? {} };
  }
  return { path: '/followup/task', query: drill.query ?? {} };
}

/** 数据源已落地的指标（C 的 ERI / 扫描件随 E21，available=false 先不展示）。 */
export function visibleKpiMetrics(metrics: KpiMetric[]): KpiMetric[] {
  return metrics.filter((metric) => metric.available);
}

// ---------------------------------------------------------------- 任务看板（E19-T03 / A-DASH-003、A-DASH-004、C-DASH-003）

/** 看板筛选（A-DASH-004：我的 / 本病区 / 全部〔可见范围内〕；缺省我的，OQ-108）。 */
export type BoardScope = 'ALL' | 'MINE' | 'WARD';

/** 看板筛选选项（与任务列表页签同语义：mine / ward / all）。 */
export const BOARD_SCOPE_OPTIONS: Array<{ label: string; value: BoardScope }> =
  [
    { label: '我的', value: 'MINE' },
    { label: '本病区', value: 'WARD' },
    { label: '全部', value: 'ALL' },
  ];

/** 看板一列（A-DASH-003 按状态分列；tasks 为前 20 张卡片，行同任务列表）。 */
export interface TaskBoardColumn {
  drill?: KpiDrill | null;
  status: string;
  tasks: TaskRow[];
  title: string;
  total: number;
}

/** 看板数据（doneColumn / doneFrom / doneTo = 已完成列口径：A 本月已完成 / C 本周完成）。 */
export interface TaskBoardView {
  columns: TaskBoardColumn[];
  doneColumn: string;
  doneFrom: string;
  doneTo: string;
  scope: BoardScope;
}

/** 看板请求参数（scope / assigneeId 为看板筛选；wardId 为页面级病区筛选）。 */
export interface TaskBoardParams {
  assigneeId?: number | string;
  scope?: BoardScope;
  wardId?: number;
}

// ---------------------------------------------------------------- 首页任务表（E19-T03 / A-DASH-008）

/** 任务表页签编码（A-DASH-008：今日任务 / 超时任务 / 我负责的任务）。 */
export type TaskTableTab = 'MINE' | 'OVERDUE' | 'TODAY';

/** 任务表一个页签（页签上显示数量 total；drill 为「查看全部」的列表筛选）。 */
export interface TaskTableTabView {
  drill?: KpiDrill | null;
  tab: TaskTableTab;
  title: string;
  total: number;
}

/** 任务表数据（当前页签前 10 行，行同任务列表——可直接进入随访工作台）。 */
export interface TaskTableView {
  items: TaskRow[];
  tab: TaskTableTab;
  tabs: TaskTableTabView[];
  today: string;
  total: number;
}

/** 任务表请求参数（tab 缺省 TODAY；wardId 为页面级病区筛选）。 */
export interface TaskTableParams {
  tab?: TaskTableTab;
  wardId?: number;
}

// ---------------------------------------------------------------- 病区筛选（A-DASH-005 / C-DASH-008）

/** 病区筛选候选（默认本人全部可见病区：快照病区清单交集，无清单时全部启用病区）。 */
export interface DashboardWardOption {
  id: string;
  name: string;
}

/** 病区候选响应。 */
export interface DashboardWardsView {
  wards: DashboardWardOption[];
}

// ---------------------------------------------------------------- 随访率卡片（E19-T04 / A-DASH-006、A-DASH-012、C-DASH-004）

/**
 * 一行随访率（每年 / 节点两卡共用，口径 Q38）：followRatePct 随访率为唯一主指标
 * （已随访含窗口内完成、逾期完成、补录及窗口外日期的记录），inWindowRatePct 仅作参考。
 */
export interface RateRow {
  deathCount: number;
  dueCount: number;
  followedCount: number;
  followRatePct: number;
  inWindowCount: number;
  inWindowRatePct: number;
  label: string;
  lostRatePct: number;
  nodeName?: null | string;
  occurrence: number;
  year?: null | number;
}

/** 每年随访率（A-DASH-006）：rows 一行一个随访年度（第 N 年，只列有应随访患者的年度）。 */
export interface YearlyRateCardView {
  asOf?: null | string;
  cohortYear?: null | number;
  cohortYears: number[];
  rows: RateRow[];
}

/** 随访计划候选（A-DASH-012「先选随访计划（默认本租户的预置计划）」）。 */
export interface NodeRatePlanOption {
  id: number;
  name: string;
}

/** 节点随访率（A-DASH-012 / C-DASH-004）：rows 一行一个节点 × 第 N 次，按计划内顺序。 */
export interface NodeRateCardView {
  asOf?: null | string;
  cohortYear?: null | number;
  cohortYears: number[];
  planId: number;
  planName?: null | string;
  plans: NodeRatePlanOption[];
  rows: RateRow[];
}

/** 每年随访率请求参数（cohortYear = 出院年份队列；wardId 为页面级病区筛选）。 */
export interface YearlyRateParams {
  cohortYear?: number;
  wardId?: number;
}

/** 节点随访率请求参数（planId 缺省 = 预置计划；受 node_follow_rate 开关控制）。 */
export interface NodeRateParams {
  cohortYear?: number;
  planId?: number;
  wardId?: number;
}

/** 队列年份下拉候选（值 = 年份，label 带「年」后缀，倒序 = 最近年份在前）。 */
export function cohortYearOptions(years: number[]): Array<{
  label: string;
  value: number;
}> {
  return years.map((year) => ({ label: `${year} 年`, value: year }));
}

/** 比率展示：百分数一位小数（如 75 → 「75.0%」）。 */
export function formatRatePct(pct: number): string {
  return `${pct.toFixed(1)}%`;
}

/** 带钻取目标的最小载体（列 / 页签共用；便于以最小对象调用路由函数）。 */
export interface DrillCarrier {
  drill?: KpiDrill | null;
}

/**
 * 看板列「查看全部」→ 任务列表路由（A 规格 §9：每列前 20 张卡片，其余进任务列表）。
 * 筛选参数由后端随列下发（tab / status / 完成时间窗口 / doctorId），前端只透传。
 */
export function boardColumnRoute(
  column: DrillCarrier,
): null | RouteLocationRaw {
  if (!column.drill) return null;
  return { path: '/followup/task', query: column.drill.query ?? {} };
}

/** 任务表页签「查看全部」→ 任务列表路由（今日任务带窗口当日、超时带状态、我负责进 MINE 页签）。 */
export function taskTableTabRoute(tab: DrillCarrier): null | RouteLocationRaw {
  if (!tab.drill) return null;
  return { path: '/followup/task', query: tab.drill.query ?? {} };
}

/** 任务表页签定义（展示顺序 = A-DASH-008：今日任务 / 超时任务 / 我负责的任务）。 */
export const TASK_TABLE_TABS: Array<{ label: string; value: TaskTableTab }> = [
  { label: '今日任务', value: 'TODAY' },
  { label: '超时任务', value: 'OVERDUE' },
  { label: '我负责的任务', value: 'MINE' },
];
