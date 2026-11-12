import type { DashboardRoleCard, DoctorWorkloadRow } from './dashboard';

/**
 * E19-T02 / A-DASH-001、C-DASH-001；E19-T03 / A-DASH-003、004、008、C-DASH-003；
 * E19-T05 / A-DASH-010、A-DASH-011：完整度最常缺失字段钻取路由（患者列表
 * completenessDrill 与卡片同一计算，缺失人数 = 列表 total）。
 * E19-T07 / A-DASH-009、C-DASH-010：医护负责患者数的行级钻取路由（患者 / 任务列表
 * doctorId 筛选）与 doctorDrillOfQuery（列表页按路由 query 还原责任医生钻取）。
 * 首页指标卡、任务看板与任务表的类型与钻取路由（数字与列表钻取一致——
 * 钻取条件由后端随指标 / 列 / 页签下发，前端只透传 query，不自行拼业务条件）。
 */
import { describe, expect, it } from 'vitest';

import {
  BOARD_SCOPE_OPTIONS,
  boardColumnRoute,
  cohortYearOptions,
  DASHBOARD_DEFAULT_LAYOUT,
  doctorDrillOfQuery,
  doctorWorkloadRoute,
  followupYearOptions,
  formatRatePct,
  kpiMetricRoute,
  layoutAreas,
  layoutCardVisible,
  missingFieldRoute,
  moveRoleCard,
  roleLayoutSaveOf,
  TASK_TABLE_TABS,
  taskTableTabRoute,
  toggleRoleCard,
  TREND_GRANULARITY_OPTIONS,
  TREND_GROUP_BY_OPTIONS,
  TREND_TIME_DIMENSION_OPTIONS,
  userHiddenCodes,
  visibleKpiMetrics,
  visibleLayoutCards,
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

describe('e19-T06 卡片目录与布局（A-DASH-013 / A-DASH-014）', () => {
  const card = (code: string, order: number, extra = {}) => ({
    allowUserHide: true,
    code,
    feature: null,
    order,
    permission: 'followup:dashboard:view',
    title: code,
    userHidden: false,
    visible: true,
    ...extra,
  });

  it('visibleLayoutCards：按 order 排序并过滤用户隐藏行', () => {
    const view = {
      cards: [
        card('kpi', 1),
        card('trend', 2, { visible: false, userHidden: true }),
        card('task_board', 3),
      ],
      source: 'ROLE_CONFIG' as const,
    };
    expect(visibleLayoutCards(view).map((item) => item.code)).toEqual([
      'kpi',
      'task_board',
    ]);
    expect(userHiddenCodes(view)).toEqual(['trend']);
    expect(layoutCardVisible(view, 'trend')).toBe(false);
    expect(layoutCardVisible(view, 'kpi')).toBe(true);
    expect(layoutCardVisible(view, 'node_rate')).toBe(false);
  });

  it('layoutAreas：同区卡片归并到首次出现位置，未知编码跳过', () => {
    // 验收场景：任务区排第一、指标卡第二（顺序按角色配置下发）
    const view = {
      cards: [
        card('task_board', 1),
        card('kpi', 2),
        card('task_table', 3),
        card('device_dist', 4), // E21 卡片尚未接入：跳过不渲染
        card('yearly_rate', 5),
        card('baseline_completeness', 6),
        card('followup_completeness', 7),
        card('node_rate', 8),
      ],
      source: 'ROLE_CONFIG' as const,
    };
    expect(layoutAreas(view)).toEqual([
      { codes: ['task_board', 'task_table'], kind: 'task' },
      { codes: ['kpi'], kind: 'kpi' },
      { codes: ['yearly_rate', 'node_rate'], kind: 'rate' },
      {
        codes: ['baseline_completeness', 'followup_completeness'],
        kind: 'completeness',
      },
    ]);
  });

  it('dASHBOARD_DEFAULT_LAYOUT：A 默认布局不含节点随访率（兜底渲染不白屏）', () => {
    const codes = visibleLayoutCards(DASHBOARD_DEFAULT_LAYOUT).map(
      (item) => item.code,
    );
    expect(codes).toEqual([
      'kpi',
      'trend',
      'task_board',
      'task_table',
      'yearly_rate',
      'baseline_completeness',
      'followup_completeness',
    ]);
    expect(DASHBOARD_DEFAULT_LAYOUT.source).toBe('DEFAULT');
  });

  it('toggleRoleCard / moveRoleCard / roleLayoutSaveOf：配置页勾选、换位与保存载荷', () => {
    const cards = [
      { ...card('kpi', 1), visible: true },
      { ...card('trend', 2), visible: true },
      { ...card('task_board', 3), visible: false },
    ] as DashboardRoleCard[];

    // 取消勾选：仍保留在目录、保存时排在末尾（不丢 allowUserHide）
    const toggled = toggleRoleCard(cards, 'trend', false);
    expect(toggled[1]?.visible).toBe(false);
    // 已选列表只剩一张时移动越界：返回原数组（不换位、不报错）
    expect(moveRoleCard(toggled, 0, 1)).toEqual(toggled);
    // 已选内上移 / 下移换位
    const moved = moveRoleCard(cards, 1, -1);
    expect(moved.map((item) => item.code)).toEqual([
      'trend',
      'kpi',
      'task_board',
    ]);
    // 保存载荷：已选按列表顺序 1 起，未选排在末尾
    expect(roleLayoutSaveOf(moved)).toEqual([
      { allowUserHide: true, code: 'trend', order: 1, visible: true },
      { allowUserHide: true, code: 'kpi', order: 2, visible: true },
      { allowUserHide: true, code: 'task_board', order: 3, visible: false },
    ]);
  });
});

describe('e19-T07 医护负责患者数钻取路由（A-DASH-009 / C-DASH-010）', () => {
  const row = {
    activeDrill: {
      query: { doctorId: '11', status: 'ACTIVE' },
      target: 'PATIENT_LIST',
    },
    activePatients: 3,
    doctorId: '11',
    doctorName: '张医生（示例）',
    followRatePct: 75,
    monthDue: 2,
    monthDueDrill: {
      query: {
        doctorId: '11',
        dueFrom: '2026-11-01',
        dueTo: '2026-11-30',
        tab: 'all',
      },
      target: 'TASK_LIST',
    },
    overdue: 1,
    overdueDrill: {
      query: { doctorId: '11', status: 'OVERDUE', tab: 'all' },
      target: 'TASK_LIST',
    },
    rateDueCount: 4,
    rateFollowedCount: 3,
    totalDrill: {
      query: { doctorId: '11', doctorName: '张医生（示例）' },
      target: 'PATIENT_LIST',
    },
    totalPatients: 4,
  } satisfies DoctorWorkloadRow;

  it('每列数字 → 对应列表路由（患者列表 doctorId〔/ status〕、任务列表 tab=all + 窗口 / 状态）', () => {
    expect(doctorWorkloadRoute(row, 'total')).toEqual({
      path: '/followup/patient',
      query: { doctorId: '11', doctorName: '张医生（示例）' },
    });
    expect(doctorWorkloadRoute(row, 'active')).toEqual({
      path: '/followup/patient',
      query: { doctorId: '11', status: 'ACTIVE' },
    });
    expect(doctorWorkloadRoute(row, 'monthDue')).toEqual({
      path: '/followup/task',
      query: {
        doctorId: '11',
        dueFrom: '2026-11-01',
        dueTo: '2026-11-30',
        tab: 'all',
      },
    });
    expect(doctorWorkloadRoute(row, 'overdue')).toEqual({
      path: '/followup/task',
      query: { doctorId: '11', status: 'OVERDUE', tab: 'all' },
    });
  });

  it('无钻取目标的列返回 null（不跳转）', () => {
    expect(
      doctorWorkloadRoute({ ...row, totalDrill: null }, 'total'),
    ).toBeNull();
  });

  it('doctorDrillOfQuery：doctorId / doctorName → 列表页钻取（缺 / 非法返回 null）', () => {
    expect(
      doctorDrillOfQuery({ doctorId: '11', doctorName: '张医生（示例）' }),
    ).toEqual({ doctorId: 11, label: '张医生（示例）' });
    // doctorName 缺省回落显示 id；数组形态取首个（vue-router query 规范化）
    expect(doctorDrillOfQuery({ doctorId: ['12'] })).toEqual({
      doctorId: 12,
      label: '12',
    });
    expect(doctorDrillOfQuery({ status: 'ACTIVE' })).toBeNull();
    expect(doctorDrillOfQuery({ doctorId: 'abc' })).toBeNull();
    expect(doctorDrillOfQuery({ doctorId: '0' })).toBeNull();
  });
});

// ---------------------------------------------------------------- E21-T15 补录进度（C-DASH-007 / C-SCAN-011）

describe('e21-T15 待录入扫描件数钻取与补录进度卡片区（C-DASH-007）', () => {
  const card = (code: string, order: number, extra = {}) => ({
    allowUserHide: true,
    code,
    feature: null,
    order,
    permission: 'followup:dashboard:view',
    title: code,
    userHidden: false,
    visible: true,
    ...extra,
  });

  it('待录入扫描件数 → 全院报告单列表带 digitizeStatus=PENDING 筛选', () => {
    const route = kpiMetricRoute({
      available: true,
      code: 'scanPending',
      drill: {
        query: { digitizeStatus: 'PENDING' },
        target: 'REPORT_LIST',
      },
      title: '待录入扫描件数',
      value: 46,
    });
    expect(route).toEqual({
      path: '/followup/report',
      query: { digitizeStatus: 'PENDING' },
    });
  });

  it('layoutAreas：scan_progress 独立成区（开关关闭时布局不含该卡、不渲染）', () => {
    const cardOn = {
      cards: [
        card('kpi', 1),
        card('scan_progress', 2),
        card('device_dist', 3), // E21 其余 C 卡片尚未接入：跳过
      ],
      source: 'ROLE_CONFIG' as const,
    };
    expect(layoutAreas(cardOn)).toEqual([
      { codes: ['kpi'], kind: 'kpi' },
      { codes: ['scan_progress'], kind: 'scan_progress' },
    ]);
    const cardOff = {
      cards: [card('kpi', 1)],
      source: 'ROLE_CONFIG' as const,
    };
    expect(layoutAreas(cardOff)).toEqual([{ codes: ['kpi'], kind: 'kpi' }]);
    expect(layoutCardVisible(cardOff, 'scan_progress')).toBe(false);
  });
});
