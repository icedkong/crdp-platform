/**
 * E21-T13 / C-FU-012：患者档案「参数趋势」页签的契约类型与纯逻辑
 * （接口契约 docs/api/patient.md §7 `GET /patients/{id}/param-trend`，
 * 前端先行实现、后端接口随 C 后续任务落地——按 AGENTS §10 按文档建议实现）。
 *
 * 趋势参数目录与医院 C 程控表单字段字典（租户库 followup V90 `C_PROGRAM_FU`）
 * 同口径：ra_/rv_ 前缀区分腔室，参数键即表单字段键（对比规则 V82 同引用）；
 * 展示名与单位后端随 series 下发（label / unit），缺失时回落本目录。
 */

/** 趋势点：一次随访（recordId）在该参数上的测量值；abnormal = 与上次对比标红（C-FU-010）。 */
export interface ParamTrendPoint {
  abnormal: boolean;
  date: string;
  recordId: string;
  value: number;
}

/** 一条参数曲线（param = C 程控表单字段键，如 ra_threshold）。 */
export interface ParamTrendSeries {
  label?: null | string;
  param: string;
  points: ParamTrendPoint[];
  unit?: null | string;
}

/** GET /patients/{id}/param-trend 响应。 */
export interface ParamTrendView {
  series: ParamTrendSeries[];
}

/** 腔室（电池类参数不分腔室，C-FU-012「可按腔室切换」）。 */
export type ParamTrendChamber = 'all' | 'atrium' | 'ventricle';

/** 参数类别（pages.md §4.1「可切换心房 / 心室、阈值 / 感知 / 阻抗 / 比例、电池」）。 */
export type ParamTrendKind =
  | 'battery'
  | 'impedance'
  | 'ratio'
  | 'sensing'
  | 'threshold';

/** 类别切换选项（顺序 = 页面展示顺序）。 */
export const TREND_KIND_OPTIONS: ReadonlyArray<{
  kind: ParamTrendKind;
  label: string;
}> = [
  { kind: 'threshold', label: '阈值' },
  { kind: 'sensing', label: '感知' },
  { kind: 'impedance', label: '阻抗' },
  { kind: 'ratio', label: '起搏比例' },
  { kind: 'battery', label: '电池' },
];

/** 腔室切换选项（全部 = 心房 / 心室同图，规格 §6 阈值示意图两条曲线）。 */
export const TREND_CHAMBER_OPTIONS: ReadonlyArray<{
  chamber: ParamTrendChamber;
  label: string;
}> = [
  { chamber: 'all', label: '全部' },
  { chamber: 'atrium', label: '心房' },
  { chamber: 'ventricle', label: '心室' },
];

/** 电池视图的参数选项（电池电压与 AT/AF 负荷单位不同，折线图单选其一）。 */
export const TREND_BATTERY_PARAMS: ReadonlyArray<{
  label: string;
  param: string;
}> = [
  { label: '电池电压', param: 'battery_voltage' },
  { label: 'AT/AF负荷', param: 'ataf_load' },
];

/**
 * 趋势参数目录（展示名 / 单位回落值；与 followup V90 `C_PROGRAM_FU` 字段
 * label / unit 一致）。参数是固定生理目录（C-FU-012 列举的六类），不走表单模板。
 */
export const TREND_PARAM_CATALOG: Readonly<
  Record<string, { label: string; unit: string }>
> = {
  ataf_load: { label: 'AT/AF负荷', unit: '%' },
  battery_voltage: { label: '电池电压', unit: 'V' },
  ra_impedance: { label: '心房阻抗', unit: 'Ω' },
  ra_pacing_ratio: { label: '心房起搏比例', unit: '%' },
  ra_sensing: { label: '心房感知P波', unit: 'mV' },
  ra_threshold: { label: '心房起搏阈值', unit: 'V' },
  rv_impedance: { label: '心室阻抗', unit: 'Ω' },
  rv_pacing_ratio: { label: '心室起搏比例', unit: '%' },
  rv_sensing: { label: '心室感知R波', unit: 'mV' },
  rv_threshold: { label: '心室起搏阈值', unit: 'V' },
};

/** 类别 → 字段键后缀（ra_/rv_ + 后缀 = C 程控表单字段键）。 */
const KIND_SUFFIX: Readonly<Record<string, string>> = {
  impedance: 'impedance',
  ratio: 'pacing_ratio',
  sensing: 'sensing',
  threshold: 'threshold',
};

/**
 * 类别 + 腔室 → 折线参数键序列（心房在前、心室在后）：阈值 + 全部 →
 * [ra_threshold, rv_threshold]（两条同单位曲线同图，规格示意图）；
 * 电池类别不产生腔室参数键（由 TREND_BATTERY_PARAMS 单独选择）。
 */
export function trendKindParamKeys(
  kind: ParamTrendKind,
  chamber: ParamTrendChamber,
): string[] {
  const suffix = KIND_SUFFIX[kind];
  if (!suffix) return [];
  if (chamber === 'atrium') return [`ra_${suffix}`];
  if (chamber === 'ventricle') return [`rv_${suffix}`];
  return [`ra_${suffix}`, `rv_${suffix}`];
}

/** 挑选后的趋势系列（label / unit 已回落到目录，缺失的参数跳过）。 */
export interface SelectedTrendSeries {
  label: string;
  param: string;
  points: ParamTrendPoint[];
  unit: string;
}

/** 按参数键挑选 series；后端未下发（如无 RA 电极时的心房参数）的键跳过。 */
export function trendSeriesFor(
  view: ParamTrendView,
  params: string[],
): SelectedTrendSeries[] {
  return params
    .map((param) => view.series.find((series) => series.param === param))
    .filter(Boolean)
    .map((series) => ({
      label:
        series.label ||
        TREND_PARAM_CATALOG[series.param]?.label ||
        series.param,
      param: series.param,
      points: series.points,
      unit: series.unit || TREND_PARAM_CATALOG[series.param]?.unit || '',
    }));
}

/** 随访日期并集（升序去重；字符串 ISO 日期字典序即时间序）。 */
export function trendVisitDates(selected: SelectedTrendSeries[]): string[] {
  const dates = new Set<string>();
  for (const series of selected) {
    for (const point of series.points) dates.add(point.date);
  }
  return [...dates].toSorted();
}

/** 历次参数表行：同一记录（recordId）的各参数合并成一行，按随访日期升序、同日按记录号。 */
export interface ParamTrendRow {
  cells: Record<string, { abnormal: boolean; value: number }>;
  date: string;
  recordId: string;
}

export function trendTableRows(
  selected: SelectedTrendSeries[],
): ParamTrendRow[] {
  const rows = new Map<string, ParamTrendRow>();
  for (const series of selected) {
    for (const point of series.points) {
      const row = rows.get(point.recordId) ?? {
        cells: {},
        date: point.date,
        recordId: point.recordId,
      };
      row.cells[series.param] = {
        abnormal: point.abnormal,
        value: point.value,
      };
      rows.set(point.recordId, row);
    }
  }
  return [...rows.values()].toSorted((a, b) => {
    if (a.date !== b.date) return a.date < b.date ? -1 : 1;
    const left = Number(a.recordId);
    const right = Number(b.recordId);
    if (Number.isFinite(left) && Number.isFinite(right) && left !== right) {
      return left - right;
    }
    return a.recordId.localeCompare(b.recordId);
  });
}
