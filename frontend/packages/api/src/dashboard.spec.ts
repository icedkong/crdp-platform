/**
 * E19-T02 / A-DASH-001、C-DASH-001：首页指标卡类型与钻取路由（数字与列表钻取一致——
 * 钻取条件由后端随指标下发，前端只透传 query，不自行拼业务条件）。
 */
import { describe, expect, it } from 'vitest';

import {
  kpiMetricRoute,
  TREND_GRANULARITY_OPTIONS,
  TREND_GROUP_BY_OPTIONS,
  TREND_TIME_DIMENSION_OPTIONS,
  visibleKpiMetrics,
} from './dashboard';

describe('e19-T02 指标卡钻取路由（A-DASH-001 点击进入对应列表）', () => {
  it('患者状态指标 → 患者列表带 status 筛选', () => {
    const route = kpiMetricRoute({
      available: true,
      code: 'active',
      drill: { query: { status: 'ACTIVE' }, target: 'PATIENT_LIST' },
      title: '在随访',
      value: 12,
    });
    expect(route).toEqual({
      path: '/followup/patient',
      query: { status: 'ACTIVE' },
    });
  });

  it('患者总数 → 患者列表无筛选', () => {
    const route = kpiMetricRoute({
      available: true,
      code: 'total',
      drill: { target: 'PATIENT_LIST' },
      title: '患者总数',
      value: 16,
    });
    expect(route).toEqual({ path: '/followup/patient', query: {} });
  });

  it('任务指标 → 任务列表带 tab=all 与筛选（当前逾期 / 本月应随访）', () => {
    const overdue = kpiMetricRoute({
      available: true,
      code: 'overdue',
      drill: {
        query: { status: 'OVERDUE', tab: 'all' },
        target: 'TASK_LIST',
      },
      title: '当前逾期',
      value: 5,
    });
    expect(overdue).toEqual({
      path: '/followup/task',
      query: { status: 'OVERDUE', tab: 'all' },
    });
    const monthDue = kpiMetricRoute({
      available: true,
      code: 'monthDue',
      drill: {
        query: { dueFrom: '2026-11-01', dueTo: '2026-11-30', tab: 'all' },
        target: 'TASK_LIST',
      },
      title: '本月应随访',
      value: 8,
    });
    expect(monthDue).toEqual({
      path: '/followup/task',
      query: { dueFrom: '2026-11-01', dueTo: '2026-11-30', tab: 'all' },
    });
  });

  it('无钻取目标（C 的标红记录列表随 E21）返回 null', () => {
    expect(
      kpiMetricRoute({
        available: true,
        code: 'highlightRecent',
        drill: null,
        title: '近 30 天含标红参数的记录数',
        value: 3,
      }),
    ).toBeNull();
  });

  it('数据源随 E21 的占位指标（available=false）不展示', () => {
    const visible = visibleKpiMetrics([
      {
        available: true,
        code: 'active',
        drill: { query: { status: 'ACTIVE' }, target: 'PATIENT_LIST' },
        title: '在随访患者数',
        value: 9,
      },
      {
        available: false,
        code: 'eriNear',
        drill: null,
        title: '接近 ERI 器械数',
      },
      {
        available: false,
        code: 'scanPending',
        drill: null,
        title: '待录入扫描件数',
      },
    ]);
    expect(visible.map((metric) => metric.code)).toEqual(['active']);
  });
});

describe('e19-T02 趋势图可选项（A-DASH-002）', () => {
  it('时间维度 / 粒度 / 分组选项与 A 规格 §9 一致（默认项排首位）', () => {
    expect(TREND_TIME_DIMENSION_OPTIONS.map((item) => item.label)).toEqual([
      '手术日期',
      '出院日期',
      '入院日期',
    ]);
    expect(TREND_GRANULARITY_OPTIONS.map((item) => item.label)).toEqual([
      '月',
      '季度',
      '年',
    ]);
    expect(TREND_GROUP_BY_OPTIONS[0]).toEqual({
      label: '不分组',
      value: 'NONE',
    });
    expect(TREND_GROUP_BY_OPTIONS.map((item) => item.value)).toContain('WARD');
  });
});
