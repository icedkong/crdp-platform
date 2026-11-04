/**
 * E15-T02 / A-FU-004、C-FU-020：我的任务页纯逻辑（pages.md §7）。
 *
 * 锁定：路由 query 与页签互转（默认我的待办）；MINE 未显式选状态时不传 status
 * （后端按未完成四态取）；看板按状态分四列；录入入口只对未完成 FORM 任务开放
 * （已有关联草稿直接用 recordId，否则先 start）；录入链接携带当前筛选供
 * 「上一条 / 下一条」还原队列。
 */
import type { TaskRow } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  boardGroups,
  buildSearchPayload,
  queryOfScope,
  queueFilterOfQuery,
  recordFormPath,
  recordRouteTarget,
  scopeOfQuery,
  statusOfQuery,
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
      scope: 'MINE',
      status: null,
      wardIds: [],
    });
    expect(payload.status).toBeUndefined();
    expect(payload.scope).toBe('MINE');
  });

  it('显式筛选全部装配（状态 / 病区 / 计划日期窗口）', () => {
    const payload = buildSearchPayload({
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
      wardIds: [3, 7],
    });
  });

  it('无筛选时回落我的待办', () => {
    expect(queueFilterOfQuery({})).toEqual({ scope: 'MINE' });
  });
});
