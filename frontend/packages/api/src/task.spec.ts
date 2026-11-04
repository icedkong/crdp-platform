/**
 * E15-T02 / A-FU-004、A-FU-005、C-FU-020：任务展示派生的纯逻辑锁定。
 *
 * 后端枚举（fu_task.status + completeKind + createType，E15-T01 / followup V53）→
 * components.md §2 task 域文案：已完成按完成方式细分，补录任务派生「已完成（补录）」；
 * 窗口列同月省略月份（pages.md §7 列样例「10-29 ~ 11-12」）；未完成四态可进入录入
 * （站内提醒类任务无需填表，A-FU-026）。
 */
import { describe, expect, it } from 'vitest';

import {
  isTaskEnterable,
  TASK_OPEN_STATUSES,
  TASK_TABS,
  taskDisplayStatus,
  taskStatusTooltip,
  taskWindowText,
} from './task';

describe('taskDisplayStatus（components.md §2 task 域派生）', () => {
  it('基础状态直接映射中文', () => {
    expect(taskDisplayStatus({ status: 'PENDING' })).toBe('待随访');
    expect(taskDisplayStatus({ status: 'IN_PROGRESS' })).toBe('进行中');
    expect(taskDisplayStatus({ status: 'OVERDUE' })).toBe('逾期');
    expect(taskDisplayStatus({ status: 'LOST' })).toBe('失访');
    expect(taskDisplayStatus({ status: 'TERMINATED' })).toBe('停止');
    expect(taskDisplayStatus({ status: 'SKIPPED_SUSPENDED' })).toBe(
      '期间未执行',
    );
  });

  it('已完成按完成方式细分（A-FU-010「逾期完成单独标记」）', () => {
    expect(taskDisplayStatus({ status: 'COMPLETED' })).toBe('已完成');
    expect(
      taskDisplayStatus({ status: 'COMPLETED', completeKind: 'ON_TIME' }),
    ).toBe('已完成');
    expect(
      taskDisplayStatus({ status: 'COMPLETED', completeKind: 'BEFORE_WINDOW' }),
    ).toBe('已完成（窗口前完成）');
    expect(
      taskDisplayStatus({ status: 'COMPLETED', completeKind: 'OVERDUE' }),
    ).toBe('已完成（逾期完成）');
  });

  it('补录任务完成优先派生「已完成（补录）」（create_type = BACKFILL，E14-T05）', () => {
    expect(
      taskDisplayStatus({
        status: 'COMPLETED',
        completeKind: 'OVERDUE',
        createType: 'BACKFILL',
      }),
    ).toBe('已完成（补录）');
  });
});

describe('taskStatusTooltip（StatusTag 提示）', () => {
  it('逾期完成 / 窗口前完成给出口径提示，其余为空', () => {
    expect(taskStatusTooltip('已完成（逾期完成）')).toBe(
      '窗口结束后完成，记逾期完成',
    );
    expect(taskStatusTooltip('已完成（窗口前完成）')).toContain('窗口开启前');
    expect(taskStatusTooltip('待随访')).toBe('');
  });
});

describe('isTaskEnterable（「工作台可直接录入随访表单」入口判定）', () => {
  const base = {
    id: '9001',
    mrn: 'MRN*',
    occurrence: 4,
    patientId: '301',
    patientName: '周*（示例）',
    planDate: '2026-11-05',
    status: 'PENDING' as const,
    taskType: 'FORM',
    windowEnd: '2026-11-12',
    windowStart: '2026-10-29',
  };

  it('未完成四态的 FORM 任务可进入', () => {
    expect(isTaskEnterable(base)).toBe(true);
    expect(isTaskEnterable({ ...base, status: 'IN_PROGRESS' })).toBe(true);
    expect(isTaskEnterable({ ...base, status: 'OVERDUE' })).toBe(true);
  });

  it('已完成 / 停止 / 失访不能进入录入', () => {
    expect(isTaskEnterable({ ...base, status: 'COMPLETED' })).toBe(false);
    expect(isTaskEnterable({ ...base, status: 'TERMINATED' })).toBe(false);
    expect(isTaskEnterable({ ...base, status: 'LOST' })).toBe(false);
  });

  it('站内提醒类任务（REMINDER）无需填表（A-FU-026）', () => {
    expect(isTaskEnterable({ ...base, taskType: 'REMINDER' })).toBe(false);
  });

  it('未完成四态口径（「我的待办」默认筛选）', () => {
    expect(TASK_OPEN_STATUSES).toEqual([
      'PENDING',
      'IN_PROGRESS',
      'OVERDUE',
      'SKIPPED_SUSPENDED',
    ]);
  });
});

describe('taskWindowText（pages.md §7 窗口列）', () => {
  it('同月省略年份与月份：10-29 ~ 11-12', () => {
    expect(taskWindowText('2026-10-29', '2026-11-12')).toBe('10-29 ~ 11-12');
  });

  it('跨月保留月份：03-01 ~ 04-05', () => {
    expect(taskWindowText('2026-03-01', '2026-04-05')).toBe('03-01 ~ 04-05');
  });

  it('空窗口显示占位', () => {
    expect(taskWindowText('', '2026-11-12')).toBe('—');
  });
});

describe('tASK_TABS（pages.md §7 页签：我的待办 / 本病区 / 全部）', () => {
  it('三个页签按序，默认我的待办在前', () => {
    expect(TASK_TABS.map((tab) => tab.scope)).toEqual(['MINE', 'WARD', 'ALL']);
    expect(TASK_TABS[0]?.label).toBe('我的待办');
  });
});
