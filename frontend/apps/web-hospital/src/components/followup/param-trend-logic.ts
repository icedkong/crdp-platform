/**
 * E21-T13 / C-FU-012：参数趋势图的 ECharts 装配与器械更换分隔线纯逻辑
 * （组件 param-trend.vue；折线图数据契约见 @crdp/api patient-trend）。
 * E24-T10 / pages.md §4.7「手机：图占满宽，表格改卡片」：trendCardLines 把
 * 历次参数表行组装成手机卡片字段行（与表格列头 / 标红口径一致）。
 *
 * - 分隔线：植入记录里「更换 / 升级」产生新器械（E21-T02 每次变更新增一条记录），
 *   首次植入与电极修正不算——电极修正后沿用电极的阻抗曲线连续
 *   （hospital-c-spec §5「更换时 RV 电极选择沿用……RV 阻抗曲线连续」）；
 * - 分隔线画在更换日期后第一次随访的刻度上（x 轴是随访日期的类目轴），
 *   更换早于全部随访 / 晚于最后一次随访时无分隔意义，不画；
 * - 标红点：abnormal 数据点用错误色 #FF4D4F（tokens colors.error，C-FU-010
 *   与上次对比超出阈值）；缺测日期为 null（断点，不跨段连线）。
 */
import type {
  ImplantRecordView,
  ParamTrendRow,
  SelectedTrendSeries,
} from '@crdp/api';
import type { EChartsOption } from 'echarts';

import { colors } from '@crdp/tokens';

/** 器械更换 / 升级分隔线（label 含操作类型，图表 markLine 名 = 标签 + 日期）。 */
export interface TrendSeparator {
  date: string;
  label: string;
}

const SEPARATOR_TYPES: Readonly<Record<string, string>> = {
  REPLACE: '器械更换',
  UPGRADE: '器械升级',
};

/** 更换 / 升级植入记录 → 分隔线（按植入日期升序；其余类型不算器械更换）。 */
export function trendSeparators(
  records: ImplantRecordView[],
): TrendSeparator[] {
  return records
    .filter((record) => SEPARATOR_TYPES[record.implantType])
    .map((record) => ({
      date: record.implantDate,
      label: SEPARATOR_TYPES[record.implantType] as string,
    }))
    .toSorted((a, b) => a.date.localeCompare(b.date));
}

/**
 * 分隔线落点：更换日期后第一次随访的下标（严格晚于更换日）；更换早于全部随访
 * （整段只有新器械）或晚于最后一次随访（尚无更换后数据）返回 null。
 */
export function separatorMarkLineIndex(
  visitDates: string[],
  separatorDate: string,
): null | number {
  const index = visitDates.findIndex((date) => date > separatorDate);
  return index > 0 && index < visitDates.length ? index : null;
}

/** E24-T10 / pages.md §4.7 手机卡片字段行（历次参数表转卡片，随 C 在 M5 做）。 */
export interface TrendCardLine {
  /** 与上次对比超出阈值（C-FU-010）：模板加错误色，与表格 abnormal 单元格同口径。 */
  abnormal: boolean;
  /** 参数名（带单位，与表格列头一致，如「心房起搏阈值(V)」）。 */
  label: string;
  /** 参数值；该次随访缺测为 —。 */
  value: string;
}

/** 历次参数表行 → 手机卡片字段行：当前视图的参数逐行（trendTableRows 的行口径）。 */
export function trendCardLines(
  row: ParamTrendRow,
  series: SelectedTrendSeries[],
): TrendCardLine[] {
  return series.map((item) => {
    const cell = row.cells[item.param];
    return {
      abnormal: cell?.abnormal ?? false,
      label: item.unit ? `${item.label}(${item.unit})` : item.label,
      value: cell ? String(cell.value) : '—',
    };
  });
}

/** 折线图装配：x 轴 = 随访日期，系列 = 选中参数（abnormal 点错误色、缺测断点）。 */
export function paramTrendChartOption(
  selected: SelectedTrendSeries[],
  separators: TrendSeparator[],
): EChartsOption {
  const dates = [
    ...new Set(
      selected.flatMap((item) => item.points.map((point) => point.date)),
    ),
  ].toSorted();
  const markLines = separators
    .map((separator) => ({
      index: separatorMarkLineIndex(dates, separator.date),
      separator,
    }))
    .filter(
      (item): item is { index: number; separator: TrendSeparator } =>
        item.index !== null,
    )
    .map(({ index, separator }) => ({
      lineStyle: { color: '#8c8c8c', type: 'dashed' as const },
      name: `${separator.label} ${separator.date}`,
      xAxis: index,
    }));
  return {
    grid: { bottom: 48, containLabel: true, left: 8, right: 16, top: 32 },
    legend: { top: 0 },
    series: selected.map((item, seriesIndex) => {
      const byDate = new Map(item.points.map((point) => [point.date, point]));
      return {
        data: dates.map((date) => {
          const point = byDate.get(date);
          if (!point) return null;
          return point.abnormal
            ? {
                itemStyle: { color: colors.error },
                value: point.value,
              }
            : point.value;
        }),
        lineStyle: { width: 2 },
        name: item.label,
        symbolSize: 8,
        type: 'line' as const,
        ...(seriesIndex === 0 && markLines.length > 0
          ? {
              markLine: {
                data: markLines,
                label: { formatter: '{b}' },
                lineStyle: { color: '#8c8c8c', type: 'dashed' as const },
                symbol: ['none', 'none'],
              },
            }
          : {}),
      };
    }),
    tooltip: { trigger: 'axis' as const },
    xAxis: {
      axisLabel: { hideOverlap: true },
      data: dates,
      type: 'category' as const,
    },
    yAxis: {
      name: selected[0]?.unit ?? '',
      type: 'value' as const,
    },
  };
}
