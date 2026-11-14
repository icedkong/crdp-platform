import type { ImplantRecordView, ParamTrendRow } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  paramTrendChartOption,
  separatorMarkLineIndex,
  trendCardLines,
  trendSeparators,
} from './param-trend-logic';

/**
 * E21-T13 / C-FU-012：参数趋势图的器械更换分隔线与 ECharts 装配纯逻辑。
 * 锁定：更换 / 升级植入记录生成分隔线（首次植入与电极修正不算——RV 电极「沿用」
 * 时阻抗曲线连续，hospital-c-spec §5）；分隔线落在更换后第一次随访的刻度上、
 * 更换早于全部随访或晚于最后一次随访时不画；标红点（abnormal）用错误色 #FF4D4F；
 * 缺测日期为 null（断点，不跨段连线）。
 *
 * E24-T10 / pages.md §4.7「手机：图占满宽，表格改卡片」：历次参数表行 → 卡片
 * 字段行（参数名带单位、值缺测为 —、abnormal 透传给模板标红）。
 */

function implant(
  implantType: string,
  implantDate: string,
  id = '1',
): ImplantRecordView {
  return {
    current: false,
    deviceType: 'PACEMAKER',
    deviceTypeLabel: '起搏器',
    endDate: null,
    endReason: null,
    id,
    implantDate,
    implantNo: 1,
    implantType,
    implantTypeLabel: null,
    leads: [],
    patientId: '301',
    status: 'ACTIVE',
    statusLabel: null,
    version: 1,
  };
}

describe('trendSeparators：器械更换 / 升级 → 分隔线', () => {
  it('更换与升级生成带标签的分隔线（按日期升序）', () => {
    const separators = trendSeparators([
      implant('FIRST', '2024-01-10', '11'),
      implant('REPLACE', '2026-03-01', '12'),
      implant('UPGRADE', '2025-06-01', '13'),
    ]);
    expect(separators).toEqual([
      { date: '2025-06-01', label: '器械升级' },
      { date: '2026-03-01', label: '器械更换' },
    ]);
  });

  it('首次植入 / 电极修正 / 其他不画分隔线（阻抗曲线连续，C 规格更换规则）', () => {
    expect(
      trendSeparators([
        implant('FIRST', '2024-01-10', '11'),
        implant('LEAD_REVISION', '2026-01-15', '14'),
        implant('OTHER', '2026-05-01', '15'),
      ]),
    ).toEqual([]);
  });

  it('空清单返回空数组', () => {
    expect(trendSeparators([])).toEqual([]);
  });
});

describe('separatorMarkLineIndex：分隔线落在更换后第一次随访刻度', () => {
  const dates = ['2024-02-01', '2024-08-01', '2025-01-01', '2025-09-01'];

  it('更换在两次随访之间 → 后一次随访的下标', () => {
    expect(separatorMarkLineIndex(dates, '2025-02-01')).toBe(3); // 2025-09-01
  });

  it('更换当天恰有随访 → 严格晚于更换日的第一次随访', () => {
    expect(separatorMarkLineIndex(dates, '2025-01-01')).toBe(3); // 2025-09-01
  });

  it('更换早于全部随访（整段只有新器械）→ 不画', () => {
    expect(separatorMarkLineIndex(dates, '2023-12-31')).toBeNull();
  });

  it('更换晚于最后一次随访（尚无更换后数据）→ 不画', () => {
    expect(separatorMarkLineIndex(dates, '2026-01-01')).toBeNull();
  });
});

describe('trendCardLines：手机卡片字段行（§4.7 表格改卡片）', () => {
  const series = [
    { label: '心房起搏阈值', param: 'ra_threshold', points: [], unit: 'V' },
    { label: '心室起搏阈值', param: 'rv_threshold', points: [], unit: 'V' },
    { label: '心房起搏比例', param: 'ra_percent', points: [], unit: '%' },
  ];
  const row: ParamTrendRow = {
    cells: {
      ra_threshold: { abnormal: true, value: 1.1 },
      rv_threshold: { abnormal: false, value: 0.8 },
    },
    date: '2026-07-15',
    recordId: '902',
  };

  it('参数逐行：label 带单位、值与标红位透传、缺测为 —（与表格同口径）', () => {
    expect(trendCardLines(row, series)).toEqual([
      { abnormal: true, label: '心房起搏阈值(V)', value: '1.1' },
      { abnormal: false, label: '心室起搏阈值(V)', value: '0.8' },
      { abnormal: false, label: '心房起搏比例(%)', value: '—' },
    ]);
  });

  it('无单位参数 label 不带括号；空系列返回空数组', () => {
    expect(
      trendCardLines({ cells: {}, date: '2026-07-15', recordId: '902' }, [
        { label: 'AT/AF 负荷', param: 'at_af', points: [], unit: '' },
      ]),
    ).toEqual([{ abnormal: false, label: 'AT/AF 负荷', value: '—' }]);
    expect(trendCardLines(row, [])).toEqual([]);
  });
});

describe('paramTrendChartOption：折线图装配', () => {
  const selected = [
    {
      label: '心房起搏阈值',
      param: 'ra_threshold',
      points: [
        { abnormal: false, date: '2026-01-12', recordId: '901', value: 0.6 },
        { abnormal: true, date: '2026-07-15', recordId: '902', value: 1.1 },
      ],
      unit: 'V',
    },
    {
      label: '心室起搏阈值',
      param: 'rv_threshold',
      points: [
        { abnormal: false, date: '2026-01-12', recordId: '901', value: 0.8 },
        { abnormal: false, date: '2026-04-20', recordId: '903', value: 0.75 },
      ],
      unit: 'V',
    },
  ];

  it('x 轴 = 随访日期并集升序；系列名 = 参数展示名；y 轴带单位', () => {
    const option = paramTrendChartOption(selected, []);
    expect(option.xAxis?.data).toEqual([
      '2026-01-12',
      '2026-04-20',
      '2026-07-15',
    ]);
    const series = option.series as Array<{ name: string; type: string }>;
    expect(series.map((item) => item.name)).toEqual([
      '心房起搏阈值',
      '心室起搏阈值',
    ]);
    expect(series.every((item) => item.type === 'line')).toBe(true);
    expect(option.yAxis?.name).toBe('V');
  });

  it('标红点：abnormal 数据点带错误色 itemStyle（#FF4D4F）', () => {
    const option = paramTrendChartOption(selected, []);
    const series = option.series as Array<{
      data: Array<
        null | number | { itemStyle: { color: string }; value: number }
      >;
    }>;
    // 心房阈值：01-12 有值、04-20 缺测（null）、07-15 标红
    expect(series[0]?.data).toEqual([
      0.6,
      null,
      { itemStyle: { color: '#FF4D4F' }, value: 1.1 },
    ]);
    // 心室阈值三站齐
    expect(series[1]?.data).toEqual([0.8, 0.75, null]);
  });

  it('器械更换处画分隔线：markLine 指向更换后第一次随访刻度并带标签', () => {
    const option = paramTrendChartOption(selected, [
      { date: '2026-06-01', label: '器械更换' },
      { date: '2027-01-01', label: '器械更换' }, // 晚于全部随访 → 不画
    ]);
    const first = option.series?.[0] as {
      markLine?: {
        data: Array<{
          lineStyle?: { type?: string };
          name?: string;
          xAxis?: number;
        }>;
        symbol?: unknown;
      };
    };
    expect(first.markLine?.symbol).toEqual(['none', 'none']);
    expect(first.markLine?.data).toHaveLength(1);
    expect(first.markLine?.data[0]?.xAxis).toBe(2); // 2026-07-15
    expect(first.markLine?.data[0]?.name).toBe('器械更换 2026-06-01');
    expect(first.markLine?.data[0]?.lineStyle?.type).toBe('dashed');
  });
});
