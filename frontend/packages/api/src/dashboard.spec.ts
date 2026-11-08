/**
 * E19-T02 / A-DASH-001、C-DASH-001；E19-T03 / A-DASH-003、004、008、C-DASH-003；
 * E19-T05 / A-DASH-010、A-DASH-011：完整度最常缺失字段钻取路由（患者列表
 * completenessDrill 与卡片同一计算，缺失人数 = 列表 total）。
 * 首页指标卡、任务看板与任务表的类型与钻取路由（数字与列表钻取一致——
 * 钻取条件由后端随指标 / 列 / 页签下发，前端只透传 query，不自行拼业务条件）。
 */
import { describe, expect, it } from 'vitest';

import {
  BOARD_SCOPE_OPTIONS,
  boardColumnRoute,
  cohortYearOptions,
  followupYearOptions,
  formatRatePct,
  kpiMetricRoute,
  missingFieldRoute,
  TASK_TABLE_TABS,
  taskTableTabRoute,
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

describe('e19-T03 任务看板与任务表（A-DASH-003、004、008、C-DASH-003）', () => {
  it('看板筛选选项 = 我的 / 本病区 / 全部（A-DASH-004）', () => {
    expect(BOARD_SCOPE_OPTIONS).toEqual([
      { label: '我的', value: 'MINE' },
      { label: '本病区', value: 'WARD' },
      { label: '全部', value: 'ALL' },
    ]);
  });

  it('看板列「查看全部」→ 任务列表（筛选由后端随列下发，只透传）', () => {
    const route = boardColumnRoute({
      drill: { query: { status: 'PENDING', tab: 'all' }, target: 'TASK_LIST' },
      status: 'PENDING',
      tasks: [],
      title: '待随访',
      total: 25,
    });
    expect(route).toEqual({
      path: '/followup/task',
      query: { status: 'PENDING', tab: 'all' },
    });
  });

  it('看板已完成列「查看全部」带完成时间窗口（与列表 completedFrom/To 同参数）', () => {
    const route = boardColumnRoute({
      drill: {
        query: {
          completedFrom: '2026-11-01',
          completedTo: '2026-11-30',
          status: 'COMPLETED',
          tab: 'all',
        },
        target: 'TASK_LIST',
      },
      status: 'COMPLETED',
      tasks: [],
      title: '本月已完成',
      total: 8,
    });
    expect(route).toEqual({
      path: '/followup/task',
      query: {
        completedFrom: '2026-11-01',
        completedTo: '2026-11-30',
        status: 'COMPLETED',
        tab: 'all',
      },
    });
  });

  it('看板列无钻取目标返回 null', () => {
    expect(
      boardColumnRoute({
        status: 'PENDING',
        tasks: [],
        title: '待随访',
        total: 0,
      }),
    ).toBeNull();
  });

  it('任务表页签「查看全部」→ 任务列表（今日带窗口当日与未完成五态）', () => {
    const route = taskTableTabRoute({
      drill: {
        query: {
          status:
            'PENDING,IN_PROGRESS,OVERDUE,LOST_CANDIDATE,SKIPPED_SUSPENDED',
          tab: 'all',
          windowFrom: '2026-11-08',
          windowTo: '2026-11-08',
        },
        target: 'TASK_LIST',
      },
      tab: 'TODAY',
      title: '今日任务',
      total: 4,
    });
    expect(route).toEqual({
      path: '/followup/task',
      query: {
        status: 'PENDING,IN_PROGRESS,OVERDUE,LOST_CANDIDATE,SKIPPED_SUSPENDED',
        tab: 'all',
        windowFrom: '2026-11-08',
        windowTo: '2026-11-08',
      },
    });
  });

  it('任务表页签定义与顺序 = 今日任务 / 超时任务 / 我负责的任务（A-DASH-008）', () => {
    expect(TASK_TABLE_TABS).toEqual([
      { label: '今日任务', value: 'TODAY' },
      { label: '超时任务', value: 'OVERDUE' },
      { label: '我负责的任务', value: 'MINE' },
    ]);
  });
});

describe('e19-T04 随访率卡片纯逻辑（A-DASH-006 / A-DASH-012，口径 Q38）', () => {
  it('cohortYearOptions：队列年份下拉候选（label 带年，倒序由后端给序）', () => {
    expect(cohortYearOptions([2025, 2024])).toEqual([
      { label: '2025 年', value: 2025 },
      { label: '2024 年', value: 2024 },
    ]);
    expect(cohortYearOptions([])).toEqual([]);
  });

  it('formatRatePct：百分数一位小数（主指标与参考列共用）', () => {
    expect(formatRatePct(75)).toBe('75.0%');
    expect(formatRatePct(66.7)).toBe('66.7%');
    expect(formatRatePct(100)).toBe('100.0%');
    expect(formatRatePct(0)).toBe('0.0%');
  });
});

describe('e19-t05 数据完整度卡片纯逻辑（A-DASH-010 / A-DASH-011，口径 Q39 / Q40）', () => {
  it('missingFieldRoute：缺失字段 → 患者列表路由（query 带钻取条件与卡片筛选）', () => {
    const field = {
      categoryKey: 'fu_contact',
      fieldKey: 'f214',
      label: '随访日期（示例）',
      missingCount: 3,
      missingRatePct: 37.5,
    };
    expect(
      missingFieldRoute('FOLLOWUP', field, {
        from: '2026-01-01',
        patientType: 'ELECTIVE',
        timeField: 'VISIT',
        to: '2026-12-31',
        wardId: 3,
        year: 1,
      }),
    ).toEqual({
      path: '/followup/patient',
      query: {
        from: '2026-01-01',
        missingField: 'f214',
        missingFieldLabel: '随访日期（示例）',
        missingKind: 'FOLLOWUP',
        patientType: 'ELECTIVE',
        timeField: 'VISIT',
        to: '2026-12-31',
        wardId: 3,
        year: 1,
      },
    });
    // 基线钻取不带 year；未选筛选不进 query
    expect(missingFieldRoute('BASELINE', field, {})).toEqual({
      path: '/followup/patient',
      query: {
        missingField: 'f214',
        missingFieldLabel: '随访日期（示例）',
        missingKind: 'BASELINE',
      },
    });
  });

  it('followupYearOptions：随访年度下拉候选（label 带第 N 年）', () => {
    expect(followupYearOptions([{ label: '第1年', year: 1 }])).toEqual([
      { label: '第1年', value: 1 },
    ]);
    expect(followupYearOptions([])).toEqual([]);
  });
});
