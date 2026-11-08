/**
 * E19-T02 / A-DASH-001、A-DASH-002、C-DASH-001：首页指标卡与趋势图的类型与纯逻辑
 * （接口契约 docs/api/stats.md §2、§3，后端 `com.crdp.followup.dashboard.api.DashboardCardController`；
 * 页面 pages.md §2 首页）。
 *
 * 「数字与列表钻取一致」：后端卡片与列表接口共用同一条件树，响应内联每项指标的
 * 钻取目标（target + query）；前端用 `kpiMetricRoute` 把它换成列表页路由
 * （患者列表 /followup/patient?status=…、任务列表 /followup/task?tab=all&status=…），
 * 不自行拼业务条件。数据源未落地的指标（C 的 ERI / 扫描件随 E21）available=false，前端隐藏。
 */
import type { RouteLocationRaw } from 'vue-router';

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
