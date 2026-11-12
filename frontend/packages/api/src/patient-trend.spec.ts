import type { ParamTrendSeries, ParamTrendView } from './patient-trend';

import { describe, expect, it } from 'vitest';

import {
  TREND_BATTERY_PARAMS,
  TREND_CHAMBER_OPTIONS,
  TREND_KIND_OPTIONS,
  TREND_PARAM_CATALOG,
  trendKindParamKeys,
  trendSeriesFor,
  trendTableRows,
  trendVisitDates,
} from './patient-trend';

/**
 * E21-T13 / C-FU-012：参数趋势页签（pages.md §4.1、hospital-c-spec §6「参数趋势图」）
 * 的契约类型与纯逻辑。锁定：类别 / 腔室 / 电池参数目录文案；kind+腔室 → 参数键
 * （ra_/rv_ 前缀）；series 挑选（缺失跳过、label 优先后端）、随访日期并集升序、
 * 历次参数表按记录合并（同一 recordId 的各参数合并成一行，按随访日期升序）。
 */

function seriesOf(
  param: string,
  points: Array<
    [date: string, value: number, recordId: string, abnormal?: boolean]
  >,
  unit: null | string = null,
  label?: null | string,
): ParamTrendSeries {
  return {
    label: label ?? null,
    param,
    points: points.map(([date, value, recordId, abnormal]) => ({
      abnormal: abnormal ?? false,
      date,
      recordId,
      value,
    })),
    unit,
  };
}

/** 心房阈值两次（第 2 次标红）+ 心室阈值两次 + 电池两参数一次。 */
function viewFixture(): ParamTrendView {
  return {
    series: [
      seriesOf(
        'ra_threshold',
        [
          ['2026-01-12', 0.6, '901'],
          ['2026-07-15', 1.1, '902', true],
        ],
        'V',
        '心房起搏阈值',
      ),
      seriesOf(
        'rv_threshold',
        [
          ['2026-01-12', 0.8, '901'],
          ['2026-07-15', 0.7, '902'],
        ],
        'V',
      ),
      seriesOf('battery_voltage', [['2026-01-12', 3.1, '901']], 'V'),
      seriesOf('ataf_load', [['2026-01-12', 12, '901']], '%'),
    ],
  };
}

describe('参数趋势目录（C-FU-012 / pages.md §4.1）', () => {
  it('类别选项：阈值 / 感知 / 阻抗 / 起搏比例 / 电池', () => {
    expect(TREND_KIND_OPTIONS).toEqual([
      { kind: 'threshold', label: '阈值' },
      { kind: 'sensing', label: '感知' },
      { kind: 'impedance', label: '阻抗' },
      { kind: 'ratio', label: '起搏比例' },
      { kind: 'battery', label: '电池' },
    ]);
  });

  it('腔室选项：全部 / 心房 / 心室（电池视图不走腔室）', () => {
    expect(TREND_CHAMBER_OPTIONS).toEqual([
      { chamber: 'all', label: '全部' },
      { chamber: 'atrium', label: '心房' },
      { chamber: 'ventricle', label: '心室' },
    ]);
  });

  it('电池视图参数：电池电压 / AT/AF负荷', () => {
    expect(TREND_BATTERY_PARAMS).toEqual([
      { param: 'battery_voltage', label: '电池电压' },
      { param: 'ataf_load', label: 'AT/AF负荷' },
    ]);
  });

  it('参数目录带展示名与单位（与 C 程控字段字典 followup V90 同口径）', () => {
    expect(TREND_PARAM_CATALOG.ra_threshold).toEqual({
      label: '心房起搏阈值',
      unit: 'V',
    });
    expect(TREND_PARAM_CATALOG.rv_sensing).toEqual({
      label: '心室感知R波',
      unit: 'mV',
    });
    expect(TREND_PARAM_CATALOG.ra_impedance).toEqual({
      label: '心房阻抗',
      unit: 'Ω',
    });
    expect(TREND_PARAM_CATALOG.rv_pacing_ratio).toEqual({
      label: '心室起搏比例',
      unit: '%',
    });
    expect(TREND_PARAM_CATALOG.battery_voltage).toEqual({
      label: '电池电压',
      unit: 'V',
    });
    expect(TREND_PARAM_CATALOG.ataf_load).toEqual({
      label: 'AT/AF负荷',
      unit: '%',
    });
    expect(Object.keys(TREND_PARAM_CATALOG)).toHaveLength(10);
  });
});

describe('trendKindParamKeys：kind + 腔室 → 参数键', () => {
  it('阈值 + 全部 → 心房、心室两条（规格示意图：同图两条阈值曲线）', () => {
    expect(trendKindParamKeys('threshold', 'all')).toEqual([
      'ra_threshold',
      'rv_threshold',
    ]);
  });

  it('感知 / 阻抗 / 比例同构（ra_/rv_ + kind 后缀）', () => {
    expect(trendKindParamKeys('sensing', 'atrium')).toEqual(['ra_sensing']);
    expect(trendKindParamKeys('impedance', 'ventricle')).toEqual([
      'rv_impedance',
    ]);
    expect(trendKindParamKeys('ratio', 'all')).toEqual([
      'ra_pacing_ratio',
      'rv_pacing_ratio',
    ]);
  });

  it('电池类别不产生腔室参数键（由电池参数目录单独选择）', () => {
    expect(trendKindParamKeys('battery', 'all')).toEqual([]);
  });
});

describe('trendSeriesFor：按参数键挑选 series', () => {
  it('缺失的参数跳过（如无 RA 电极时后端不下发心房参数）', () => {
    const selected = trendSeriesFor(viewFixture(), [
      'ra_threshold',
      'rv_threshold',
    ]);
    expect(selected.map((item) => item.param)).toEqual([
      'ra_threshold',
      'rv_threshold',
    ]);
  });

  it('展示名优先后端 label，缺失回落目录；单位同理', () => {
    const [ra, rv] = trendSeriesFor(viewFixture(), [
      'ra_threshold',
      'rv_threshold',
    ]);
    expect(ra?.label).toBe('心房起搏阈值'); // 后端下发
    expect(rv?.label).toBe('心室起搏阈值'); // 回落目录
    expect(rv?.unit).toBe('V');
  });

  it('完全缺失时返回空数组', () => {
    expect(trendSeriesFor({ series: [] }, ['ra_threshold'])).toEqual([]);
  });
});

describe('trendVisitDates：随访日期并集（升序去重）', () => {
  it('多系列日期并集、乱序输入也按日期升序', () => {
    const view: ParamTrendView = {
      series: [
        seriesOf('ra_threshold', [
          ['2026-07-15', 1.1, '902'],
          ['2026-01-12', 0.6, '901'],
        ]),
        seriesOf('rv_threshold', [
          ['2026-01-12', 0.8, '901'],
          ['2026-04-20', 0.75, '903'],
        ]),
      ],
    };
    expect(
      trendVisitDates(trendSeriesFor(view, ['ra_threshold', 'rv_threshold'])),
    ).toEqual(['2026-01-12', '2026-04-20', '2026-07-15']);
  });
});

describe('trendTableRows：历次参数表行（按记录合并）', () => {
  it('同一 recordId 的各参数合并成一行，按随访日期升序', () => {
    const rows = trendTableRows(
      trendSeriesFor(viewFixture(), ['ra_threshold', 'rv_threshold']),
    );
    expect(rows).toHaveLength(2);
    expect(rows[0]).toMatchObject({
      cells: {
        ra_threshold: { abnormal: false, value: 0.6 },
        rv_threshold: { abnormal: false, value: 0.8 },
      },
      date: '2026-01-12',
      recordId: '901',
    });
    expect(rows[1]?.date).toBe('2026-07-15');
    expect(rows[1]?.cells.ra_threshold).toEqual({
      abnormal: true,
      value: 1.1,
    });
  });

  it('该记录缺失的参数不出现在 cells（表格单元格显示 —）', () => {
    const rows = trendTableRows(
      trendSeriesFor(viewFixture(), ['ra_threshold', 'battery_voltage']),
    );
    expect(rows[0]?.cells.ra_threshold).toEqual({
      abnormal: false,
      value: 0.6,
    });
    expect(rows[0]?.cells.battery_voltage).toEqual({
      abnormal: false,
      value: 3.1,
    });
    expect(rows[1]?.cells.battery_voltage).toBeUndefined();
  });

  it('同日两条记录（重复保存场景）各自成行，按日期后再按 recordId 排序', () => {
    const view: ParamTrendView = {
      series: [
        seriesOf('rv_threshold', [
          ['2026-07-15', 0.7, '902'],
          ['2026-07-15', 0.9, '905'],
        ]),
      ],
    };
    const rows = trendTableRows(trendSeriesFor(view, ['rv_threshold']));
    expect(rows.map((row) => row.recordId)).toEqual(['902', '905']);
  });
});
