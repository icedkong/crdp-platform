/**
 * E15-T02 / A-FU-004、C-FU-020：我的任务页纯逻辑（pages.md §7）。
 *
 * 锁定：路由 query 与页签互转（默认我的待办）；MINE 未显式选状态时不传 status
 * （后端按未完成四态取）；看板按状态分四列；录入入口只对未完成 FORM 任务开放
 * （已有关联草稿直接用 recordId，否则先 start）；录入链接携带当前筛选供
 * 「上一条 / 下一条」还原队列；E19-T03 起窗口（windowFrom/windowTo）与完成
 * 时间（completedFrom/completedTo）钻取参数同样随链接往返。
 */
import type { TaskRow } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  boardGroups,
  buildSearchPayload,
  dateRangeOfQuery,
  dueRangeOfQuery,
  queryOfScope,
  queueFilterOfQuery,
  recordFormPath,
  recordRouteTarget,
  scopeOfQuery,
  statusOfQuery,
  taskColumns,
} from './logic';

const row = (over: Partial<TaskRow>): TaskRow => ({
  id: '9001',
  mrn: 'MRN*',
  occurrence: 4,
  patientId: '301',
  patientName: '周*（示例）',
  planDate: '2026-11-05',
  status: 'PENDING',
  taskType: 'FORM',
  windowEnd: '2026-11-12',
  windowStart: '2026-10-29',
  ...over,
});

describe('scopeOfQuery（路由 query → 页签）', () => {
  it('tab=mine|ward|all 映射，未知值回落我的待办（A-FU-004 默认）', () => {
    expect(scopeOfQuery('mine')).toBe('MINE');
    expect(scopeOfQuery('ward')).toBe('WARD');
    expect(scopeOfQuery('all')).toBe('ALL');
    expect(scopeOfQuery(undefined)).toBe('MINE');
    expect(scopeOfQuery('nonsense')).toBe('MINE');
    expect(scopeOfQuery(['ward', 'all'])).toBe('WARD');
  });

  it('queryOfScope 逆映射', () => {
    expect(queryOfScope('MINE')).toBe('mine');
    expect(queryOfScope('ALL')).toBe('all');
  });
});

describe('statusOfQuery（status= 逗号分隔枚举）', () => {
  it('合法值解析、未知值丢弃', () => {
    expect(statusOfQuery('PENDING,OVERDUE')).toEqual(['PENDING', 'OVERDUE']);
    expect(statusOfQuery('pending')).toEqual(['PENDING']);
    expect(statusOfQuery('BAD,VALUE')).toBeUndefined();
    expect(statusOfQuery(undefined)).toBeUndefined();
  });
});

describe('buildSearchPayload（检索请求装配）', () => {
  it('mINE 未显式选状态时不传 status（后端按未完成四态取）', () => {
    const payload = buildSearchPayload({
      assignee: 'ALL',
      scope: 'MINE',
      status: null,
      wardIds: [],
    });
    expect(payload.status).toBeUndefined();
    expect(payload.scope).toBe('MINE');
    expect(payload.unassigned).toBeUndefined();
  });

  it('显式筛选全部装配（状态 / 病区 / 计划日期窗口）', () => {
    const payload = buildSearchPayload({
      assignee: 'ALL',
      scope: 'WARD',
      status: ['COMPLETED', 'OVERDUE'],
      wardIds: [3, 7],
      dueRange: ['2026-10-01', '2026-11-30'],
    });
    expect(payload).toEqual({
      scope: 'WARD',
      status: ['COMPLETED', 'OVERDUE'],
      wardIds: [3, 7],
      dueFrom: '2026-10-01',
      dueTo: '2026-11-30',
    });
  });

  it('负责人选「待认领」时带 unassigned（病区公共池，A-FU-009 / E15-T03）', () => {
    const payload = buildSearchPayload({
      assignee: 'UNASSIGNED',
      scope: 'ALL',
      status: null,
      wardIds: [],
    });
    expect(payload.unassigned).toBe(true);
  });

  it('e19-T07 医护负责患者数钻取：doctorId 透传（后端语义 = 任务负责人或患者责任医生）', () => {
    const payload = buildSearchPayload({
      assignee: 'ALL',
      doctorId: 11,
      scope: 'ALL',
      status: null,
      wardIds: [],
    });
    expect(payload.doctorId).toBe(11);
  });
});

describe('boardGroups（看板按状态分四列）', () => {
  it('待随访 / 进行中 / 逾期 / 已完成 各归各列，其余状态不进看板', () => {
    const groups = boardGroups([
      row({ id: '1', status: 'PENDING' }),
      row({ id: '2', status: 'IN_PROGRESS' }),
      row({ id: '3', status: 'OVERDUE' }),
      row({ id: '4', status: 'COMPLETED' }),
      row({ id: '5', status: 'TERMINATED' }),
    ]);
    expect(groups.map((group) => group.label)).toEqual([
      '待随访',
      '进行中',
      '逾期',
      '已完成',
    ]);
    expect(groups[0]?.rows.map((item) => item.id)).toEqual(['1']);
    expect(groups[3]?.rows.map((item) => item.id)).toEqual(['4']);
    expect(
      groups.flatMap((group) => group.rows).map((item) => item.id),
    ).not.toContain('5');
  });
});

describe('recordRouteTarget（录入入口）', () => {
  it('已有关联草稿直接用 recordId', () => {
    expect(recordRouteTarget(row({ recordId: '701' }), null)).toBe('701');
  });

  it('无草稿时用 start 返回的 recordId', () => {
    expect(recordRouteTarget(row({}), '702')).toBe('702');
  });

  it('终态任务与 REMINDER 不能进入（站内提醒无需填表，A-FU-026）', () => {
    expect(recordRouteTarget(row({ status: 'COMPLETED' }), '701')).toBeNull();
    expect(recordRouteTarget(row({ taskType: 'REMINDER' }), '701')).toBeNull();
    expect(recordRouteTarget(row({}), null)).toBeNull();
  });
});

describe('recordFormPath / queueFilterOfQuery（筛选随链接往返）', () => {
  it('录入链接携带页签与筛选，表单页可还原（上一条 / 下一条顺当前列表）', () => {
    const path = recordFormPath('701', {
      scope: 'WARD',
      status: ['PENDING', 'OVERDUE'],
      wardIds: [3, 7],
      dueFrom: '2026-10-01',
      dueTo: '2026-11-30',
    });
    expect(path).toContain('/followup/record/701?');
    const query: Record<string, unknown> = {};
    for (const [key, value] of new URLSearchParams(path.split('?')[1])) {
      query[key] = key === 'wardIds' ? value.split(',') : value;
    }
    expect(queueFilterOfQuery(query)).toEqual({
      scope: 'WARD',
      status: ['PENDING', 'OVERDUE'],
      unassigned: false,
      wardIds: [3, 7],
    });
  });

  it('待认领筛选随链接往返（队列顺当前列表，E15-T03）', () => {
    const path = recordFormPath('702', { scope: 'ALL', unassigned: true });
    expect(path).toContain('unassigned=1');
    const query: Record<string, unknown> = {};
    for (const [key, value] of new URLSearchParams(path.split('?')[1])) {
      query[key] = value;
    }
    expect(queueFilterOfQuery(query)).toEqual({
      scope: 'ALL',
      unassigned: true,
    });
  });

  it('无筛选时回落我的待办', () => {
    expect(queueFilterOfQuery({})).toEqual({
      scope: 'MINE',
      unassigned: false,
    });
  });
});

describe('dueRangeOfQuery（E19-T02 指标卡钻取「本月应随访」进任务列表）', () => {
  it('两端齐备且合法才作为计划日期窗口', () => {
    expect(dueRangeOfQuery('2026-11-01', '2026-11-30')).toEqual([
      '2026-11-01',
      '2026-11-30',
    ]);
    expect(dueRangeOfQuery(['2026-11-01'], '2026-11-30')).toEqual([
      '2026-11-01',
      '2026-11-30',
    ]);
  });

  it('半选、非法日期或倒挂都不筛选', () => {
    expect(dueRangeOfQuery('2026-11-01', null)).toBeNull();
    expect(dueRangeOfQuery(null, '2026-11-30')).toBeNull();
    expect(dueRangeOfQuery('2026/11/01', '2026-11-30')).toBeNull();
    expect(dueRangeOfQuery('2026-12-01', '2026-11-30')).toBeNull();
  });
});

describe('dateRangeOfQuery / 窗口与完成时间钻取（E19-T03 首页任务区「查看全部」）', () => {
  it('窗口区间解析（今日任务钻取带 windowFrom=windowTo=当日）', () => {
    expect(dateRangeOfQuery('2026-11-08', '2026-11-08')).toEqual([
      '2026-11-08',
      '2026-11-08',
    ]);
    expect(dateRangeOfQuery('2026-11-01', null)).toBeNull();
  });

  it('完成时间区间解析（看板已完成列钻取带当月闭区间）', () => {
    expect(dateRangeOfQuery('2026-11-01', '2026-11-30')).toEqual([
      '2026-11-01',
      '2026-11-30',
    ]);
    expect(dateRangeOfQuery('11-01', '2026-11-30')).toBeNull();
  });

  it('buildSearchPayload 装配窗口与完成时间（页面筛选条暂无控件，仅钻取入口）', () => {
    const payload = buildSearchPayload({
      assignee: 'ALL',
      completedRange: ['2026-11-01', '2026-11-30'],
      dueRange: null,
      scope: 'ALL',
      status: ['COMPLETED'],
      wardIds: [],
      windowRange: ['2026-11-08', '2026-11-08'],
    });
    expect(payload).toMatchObject({
      completedFrom: '2026-11-01',
      completedTo: '2026-11-30',
      scope: 'ALL',
      status: ['COMPLETED'],
      windowFrom: '2026-11-08',
      windowTo: '2026-11-08',
    });
  });

  it('录入链接随窗口 / 完成时间往返（表单页队列顺当前列表）', () => {
    const path = recordFormPath('5001', {
      completedFrom: '2026-11-01',
      completedTo: '2026-11-30',
      scope: 'ALL',
      status: ['COMPLETED'],
      windowFrom: '2026-11-08',
      windowTo: '2026-11-08',
    });
    const query: Record<string, unknown> = {};
    for (const [key, value] of new URLSearchParams(path.split('?')[1])) {
      query[key] = value;
    }
    expect(path).toContain('record/5001');
    expect(query.windowFrom).toBe('2026-11-08');
    expect(query.completedFrom).toBe('2026-11-01');
    expect(queueFilterOfQuery(query)).toMatchObject({
      completedFrom: '2026-11-01',
      completedTo: '2026-11-30',
      scope: 'ALL',
      windowFrom: '2026-11-08',
      windowTo: '2026-11-08',
    });
  });
});

/* ----------------------------------------------------------------------
 * E21-T23 / C-FU-020 · 列定义：implant 开关开启（医院 C）的桌面列表插入
 * 当前器械类型 / 随访时间点 / 上次标红三列；A / B 与平板主要列不插。
 */
describe('taskColumns（E21-T23 / C-FU-020 C 三列）', () => {
  it('桌面 + implant 开启：在节点后插入当前器械类型 / 随访时间点 / 上次标红三列', () => {
    const columns = taskColumns({ implantEnabled: true, tablet: false });
    const names = columns.map((column) => column.dataIndex);
    expect(names).toEqual([
      'index',
      'patientName',
      'wardName',
      'nodeName',
      'deviceType',
      'timepoint',
      'lastHighlightCount',
      'planDate',
      'window',
      'status',
      'assigneeName',
      'lastContact',
      'action',
    ]);
    expect(
      columns.find((column) => column.dataIndex === 'deviceType')?.title,
    ).toBe('当前器械类型');
    expect(
      columns.find((column) => column.dataIndex === 'timepoint')?.title,
    ).toBe('随访时间点');
  });

  it('桌面 + implant 关闭（A / B）：三列不出，列序与 E21 前一致', () => {
    const names = taskColumns({
      implantEnabled: false,
      tablet: false,
    }).map((column) => column.dataIndex);
    expect(names).not.toContain('deviceType');
    expect(names).not.toContain('timepoint');
    expect(names).not.toContain('lastHighlightCount');
    expect(names).toEqual([
      'index',
      'patientName',
      'wardName',
      'nodeName',
      'planDate',
      'window',
      'status',
      'assigneeName',
      'lastContact',
      'action',
    ]);
  });

  it('平板保留主要列：implant 开启也不插 C 三列', () => {
    const names = taskColumns({ implantEnabled: true, tablet: true }).map(
      (column) => column.dataIndex,
    );
    expect(names).not.toContain('deviceType');
    expect(names).not.toContain('timepoint');
    expect(names).not.toContain('lastHighlightCount');
    expect(names.at(-1)).toBe('action');
  });
});
