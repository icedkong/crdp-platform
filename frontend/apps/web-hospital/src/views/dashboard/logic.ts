import type { RateRow, TrendCardView } from '@crdp/api';
/**
 * E19-T02 / A-DASH-001、A-DASH-002：首页（pages.md §2）的纯逻辑。
 *
 * - 指标卡的图标映射（线性图标，components.md §13；A / C 指标集共用编码 → 图标）；
 * - 趋势图 ECharts 装配：x 轴 = 周期键（YYYY-MM / YYYY-Qn / YYYY），系列 = 图例分组
 *   （不分组单系列「患者数」，按病区 / 性别等分组多系列柱图；空周期后端已补 0）；
 * - 默认时间范围（最近 24 个月，止于当月月末；A 规格 §9）。
 * - E19-T04 / A-DASH-006、A-DASH-012：随访率柱线图装配（柱 = 应随访 / 已随访，
 *   线 = 随访率，A 规格 §9「下方柱线图」）。
 */
import type { EChartsOption } from 'echarts';

/** 指标编码 → 右上线性图标（iconify 名；未知编码不显示图标）。 */
const METRIC_ICONS: Record<string, string> = {
  active: 'ant-design:heart-outlined',
  dead: 'ant-design:rest-outlined',
  eriNear: 'ant-design:thunderbolt-outlined',
  highlightRecent: 'ant-design:alert-outlined',
  lost: 'ant-design:disconnect-outlined',
  monthDue: 'ant-design:calendar-outlined',
  overdue: 'ant-design:clock-circle-outlined',
  pending: 'ant-design:phone-outlined',
  scanPending: 'ant-design:file-text-outlined',
  terminated: 'ant-design:stop-outlined',
  total: 'ant-design:team-outlined',
};

export function metricIcon(code: string): null | string {
  return METRIC_ICONS[code] ?? null;
}

/** 趋势图 ECharts 装配（患者数柱图；分组维度多系列）。 */
export function trendChartOption(view: TrendCardView): EChartsOption {
  const buckets = view.buckets;
  return {
    grid: { bottom: 48, containLabel: true, left: 8, right: 16, top: 32 },
    legend:
      view.legend.length > 1 ? { top: 0, type: 'scroll' } : { show: false },
    series:
      view.legend.length > 0
        ? view.legend.map((group) => ({
            name: group.label,
            type: 'bar' as const,
            data: buckets.map(
              (bucket) =>
                bucket.groups.find((item) => item.key === group.key)?.count ??
                0,
            ),
          }))
        : [
            {
              name: '患者数',
              type: 'bar' as const,
              data: buckets.map((bucket) => bucket.total),
            },
          ],
    tooltip: { trigger: 'axis' as const },
    xAxis: {
      axisLabel: { hideOverlap: true },
      data: buckets.map((bucket) => bucket.bucket),
      type: 'category' as const,
    },
    yAxis: {
      minInterval: 1,
      type: 'value' as const,
    },
  };
}

/** 默认时间范围：最近 24 个月（首月 1 日 ~ 当月月末；A 规格 §9「默认最近 24 个月」）。 */
export function defaultTrendRange(now = new Date()): {
  from: string;
  to: string;
} {
  const to = new Date(now.getFullYear(), now.getMonth() + 1, 0);
  const from = new Date(now.getFullYear(), now.getMonth() - 23, 1);
  const format = (date: Date) =>
    `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(
      date.getDate(),
    ).padStart(2, '0')}`;
  return { from: format(from), to: format(to) };
}

/** 随访率柱线图（E19-T04 / A-DASH-006、A-DASH-012；A 规格 §9）：柱 = 应随访 / 已随访（左轴，人），线 = 随访率（右轴，%）。 */
export function rateChartOption(rows: RateRow[]): EChartsOption {
  return {
    grid: { bottom: 48, containLabel: true, left: 8, right: 16, top: 32 },
    legend: { top: 0 },
    series: [
      {
        data: rows.map((row) => row.dueCount),
        name: '应随访',
        type: 'bar' as const,
        yAxisIndex: 0,
      },
      {
        data: rows.map((row) => row.followedCount),
        name: '已随访',
        type: 'bar' as const,
        yAxisIndex: 0,
      },
      {
        data: rows.map((row) => row.followRatePct),
        lineStyle: { width: 2 },
        name: '随访率',
        symbolSize: 6,
        type: 'line' as const,
        yAxisIndex: 1,
      },
    ],
    tooltip: { trigger: 'axis' as const },
    xAxis: {
      axisLabel: { hideOverlap: true },
      data: rows.map((row) => row.label),
      type: 'category' as const,
    },
    yAxis: [
      { minInterval: 1, name: '人', type: 'value' as const },
      {
        axisLabel: { formatter: '{value}%' },
        max: 100,
        min: 0,
        name: '随访率',
        type: 'value' as const,
      },
    ],
  };
}
