/**
 * E19-T02 / A-DASH-001、A-DASH-002：首页趋势图装配与指标图标（pages.md §2）。
 */
import type { TrendCardView } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import { defaultTrendRange, metricIcon, trendChartOption } from './logic';

const view: TrendCardView = {
  buckets: [
    {
      bucket: '2026-Q1',
      groups: [
        { count: 3, key: '心外一病区（示例）', label: '心外一病区（示例）' },
        { count: 2, key: '心外二病区（示例）', label: '心外二病区（示例）' },
      ],
      total: 5,
    },
    {
      bucket: '2026-Q2',
      groups: [
        { count: 0, key: '心外一病区（示例）', label: '心外一病区（示例）' },
        { count: 1, key: '心外二病区（示例）', label: '心外二病区（示例）' },
      ],
      total: 1,
    },
  ],
  from: '2026-01-01',
  granularity: 'QUARTER',
  groupBy: 'WARD',
  legend: [
    { key: '心外一病区（示例）', label: '心外一病区（示例）' },
    { key: '心外二病区（示例）', label: '心外二病区（示例）' },
  ],
  timeDimension: 'DISCHARGE',
  to: '2026-12-31',
};

describe('e19-t02 趋势图 ECharts 装配（A-DASH-002）', () => {
  it('x 轴 = 周期键，系列 = 图例分组，空周期补 0', () => {
    const option = trendChartOption(view);
    expect(option.xAxis).toMatchObject({
      data: ['2026-Q1', '2026-Q2'],
      type: 'category',
    });
    const series = option.series as Array<{
      data: number[];
      name: string;
      type: string;
    }>;
    expect(series).toHaveLength(2);
    expect(series[0]).toMatchObject({
      name: '心外一病区（示例）',
      data: [3, 0],
    });
    expect(series[1]).toMatchObject({
      name: '心外二病区（示例）',
      data: [2, 1],
    });
    expect(option.yAxis).toMatchObject({ minInterval: 1, type: 'value' });
  });

  it('不分组时单系列「患者数」按桶 total', () => {
    const option = trendChartOption({
      ...view,
      buckets: [
        { bucket: '2026-01', groups: [], total: 7 },
        { bucket: '2026-02', groups: [], total: 0 },
      ],
      groupBy: 'NONE',
      legend: [],
    });
    const series = option.series as Array<{ data: number[]; name: string }>;
    expect(series).toHaveLength(1);
    expect(series[0]).toMatchObject({ name: '患者数', data: [7, 0] });
  });
});

describe('e19-t02 指标图标与默认时间范围', () => {
  it('指标编码 → 线性图标（未知编码无图标）', () => {
    expect(metricIcon('total')).toBe('ant-design:team-outlined');
    expect(metricIcon('overdue')).toBe('ant-design:clock-circle-outlined');
    expect(metricIcon('nonsense')).toBeNull();
  });

  it('默认时间范围 = 最近 24 个月（首月 1 日 ~ 当月月末）', () => {
    // 2026-11 往前 24 个整月：2024-12-01 ~ 2026-11-30（与后端默认窗口一致）
    expect(defaultTrendRange(new Date('2026-11-08T10:00:00+08:00'))).toEqual({
      from: '2024-12-01',
      to: '2026-11-30',
    });
  });
});
