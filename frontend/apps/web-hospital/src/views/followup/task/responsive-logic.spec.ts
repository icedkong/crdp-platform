/**
 * E24-T05 / NFR-RESP-002 · 我的任务页响应式纯逻辑（pages.md §7：
 * 手机任务卡片（患者、节点、窗口、状态、逾期标记）、状态筛选用顶部分段切换、
 * 无批量改派；平板保留主要列、点行进表单。数据全部虚构）。
 */
import type { TaskRow, TaskStatus } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  drawerFilterCount,
  isTaskOverdue,
  MOBILE_STATUS_SEGMENTS,
  segmentOfStatus,
  statusOfSegment,
  taskCardActions,
} from './responsive-logic';

const row = (over: Partial<TaskRow>): TaskRow => ({
  assigneeName: '王医生（示例）',
  id: '9001',
  mrn: 'MRN*001（示例）',
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

describe('手机状态分段 MOBILE_STATUS_SEGMENTS（pages.md §7「状态筛选用顶部分段切换」）', () => {
  it('全部 + 看板四态（待随访 / 进行中 / 逾期 / 已完成），与 BOARD_COLUMNS 同口径', () => {
    expect(MOBILE_STATUS_SEGMENTS).toEqual([
      { label: '全部', value: 'ALL' },
      { label: '待随访', value: 'PENDING' },
      { label: '进行中', value: 'IN_PROGRESS' },
      { label: '逾期', value: 'OVERDUE' },
      { label: '已完成', value: 'COMPLETED' },
    ]);
  });
});

describe('statusOfSegment / segmentOfStatus（分段值 ↔ 状态筛选互转）', () => {
  it('『全部』→ 不筛状态（null，MINE 页签回落后端未完成五态口径）', () => {
    expect(statusOfSegment('ALL')).toBeNull();
    expect(segmentOfStatus(null)).toBe('ALL');
    expect(segmentOfStatus([])).toBe('ALL');
  });

  it('四态单选往返一致', () => {
    for (const value of ['PENDING', 'IN_PROGRESS', 'OVERDUE', 'COMPLETED']) {
      expect(statusOfSegment(value as never)).toEqual([value]);
      expect(segmentOfStatus([value as TaskStatus])).toBe(value);
    }
  });

  it('分段放不下的状态（失访候选等多选 / 其余单选）→ null，页面用条件 chip 展示与清除', () => {
    expect(segmentOfStatus(['LOST_CANDIDATE'])).toBeNull();
    expect(segmentOfStatus(['PENDING', 'OVERDUE'])).toBeNull();
    expect(
      segmentOfStatus(['LOST', 'TERMINATED', 'SKIPPED_SUSPENDED']),
    ).toBeNull();
  });
});

describe('isTaskOverdue（手机卡片「逾期标记」，pages.md §7 五要素之一）', () => {
  it('状态 OVERDUE 即逾期（窗口判定由后端状态机承担）', () => {
    expect(isTaskOverdue(row({ status: 'OVERDUE' }))).toBe(true);
    expect(isTaskOverdue(row({ status: 'PENDING' }))).toBe(false);
    expect(
      isTaskOverdue(row({ status: 'COMPLETED', completeKind: 'OVERDUE' })),
    ).toBe(false);
  });
});

describe('taskCardActions（手机卡片操作：点卡片主操作 + 次操作按钮）', () => {
  const perms = { canAssign: true, canEditRecord: true };

  it('未完成 FORM 任务：点卡片进表单（A-FU-005「从任务进入」）', () => {
    expect(taskCardActions(row({}), perms)).toEqual({
      primary: 'enter',
      secondary: ['assign'],
    });
  });

  it('已有记录但不可录入（无权限 / 终态）：点卡片只读查看', () => {
    expect(
      taskCardActions(row({ status: 'COMPLETED', recordId: 'R-1' }), {
        canAssign: false,
        canEditRecord: false,
      }),
    ).toEqual({ primary: 'view', secondary: [] });
    expect(
      taskCardActions(row({ status: 'COMPLETED', recordId: 'R-1' }), perms),
    ).toEqual({ primary: 'view', secondary: [] });
  });

  it('不可录入且无记录（如未开始的提醒任务）：卡片无主操作，仅次操作', () => {
    expect(
      taskCardActions(row({ taskType: 'REMINDER' }), perms).primary,
    ).toBeNull();
  });

  it('次操作：完成提醒（REMINDER 未完成）/ 认领（负责人为空）/ 改派（未完成且有权限）', () => {
    expect(
      taskCardActions(row({ taskType: 'REMINDER' }), perms).secondary,
    ).toEqual(['acknowledge', 'assign']);
    expect(
      taskCardActions(row({ assigneeName: null }), {
        ...perms,
        canEditRecord: false,
      }).secondary,
    ).toEqual(['claim', 'assign']);
    // 终态任务不可改派（随访已执行完，无可再派的人）
    expect(taskCardActions(row({ status: 'LOST' }), perms).secondary).toEqual(
      [],
    );
    // 无改派权限（pages.md §7 手机仅去掉「批量」改派，单条仍按权限）
    expect(
      taskCardActions(row({}), { canAssign: false, canEditRecord: true })
        .secondary,
    ).toEqual([]);
  });
});

describe('drawerFilterCount（手机「筛选」按钮角标：抽屉内生效条件数）', () => {
  it('无筛选为 0；病区 / 计划日期 / 待认领各计 1（状态分段常驻顶栏不计）', () => {
    expect(
      drawerFilterCount({ assignee: 'ALL', dueRange: null, wardIds: [] }),
    ).toBe(0);
    expect(
      drawerFilterCount({
        assignee: 'UNASSIGNED',
        dueRange: ['2026-11-01', '2026-11-30'],
        wardIds: [3, 4],
      }),
    ).toBe(3);
  });

  it('计划日期只选一半不计（与检索请求体「未选全不发送」同口径）', () => {
    expect(
      drawerFilterCount({
        assignee: 'ALL',
        dueRange: ['2026-11-01', null] as unknown as [string, string],
        wardIds: [],
      }),
    ).toBe(0);
  });
});
