/**
 * E15-T02 / A-FU-004、A-FU-005、C-FU-020 · E15-T03 A-FU-009：任务展示派生的纯逻辑锁定。
 *
 * 后端枚举（fu_task.status + completeKind + createType，E15-T01 / followup V53）→
 * components.md §2 task 域文案：已完成按完成方式细分，补录任务派生「已完成（补录）」；
 * 窗口列同月省略月份（pages.md §7 列样例「10-29 ~ 11-12」）；未完成四态可进入录入
 * （站内提醒类任务无需填表，A-FU-026）；待认领行可认领、未完成可改派（E15-T03），
 * 改派结果逐条文案按原因码派生。
 */
import { describe, expect, it } from 'vitest';

import {
  isTaskAcknowledgable,
  isTaskClaimable,
  isTaskEnterable,
  isTaskLostCandidate,
  isTaskReassignable,
  TASK_OPEN_STATUSES,
  TASK_TABS,
  taskAssignItemLabel,
  taskAssignOutcomeText,
  taskAssignSummary,
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

describe('e15-T06 / A-FU-013、A-FU-026 临时任务与站内提醒', () => {
  it('临时 FORM 任务派生「待随访（临时）」（不占用年度任务）', () => {
    expect(
      taskDisplayStatus({
        createType: 'TEMP',
        status: 'PENDING',
        taskType: 'FORM',
      }),
    ).toBe('待随访（临时）');
    expect(
      taskDisplayStatus({
        createType: 'TEMP',
        status: 'OVERDUE',
        taskType: 'FORM',
      }),
    ).toBe('逾期');
  });

  it('临时 / 计划内站内提醒任务派生「待提醒」形态（无需填表）', () => {
    expect(
      taskDisplayStatus({
        createType: 'TEMP',
        status: 'PENDING',
        taskType: 'REMINDER',
      }),
    ).toBe('待提醒（临时）');
  });

  it('isTaskLostCandidate（S5 / A-FU-012）：仅失访候选状态可「确认失访」，已失访为终态不可重复确认', () => {
    expect(isTaskLostCandidate({ status: 'LOST_CANDIDATE' })).toBe(true);
    expect(isTaskLostCandidate({ status: 'PENDING' })).toBe(false);
    expect(isTaskLostCandidate({ status: 'OVERDUE' })).toBe(false);
    expect(isTaskLostCandidate({ status: 'LOST' })).toBe(false);
  });

  it('isTaskAcknowledgable：站内提醒类任务且未完成可「完成提醒」（验收「站内提醒类任务无需填表」）', () => {
    expect(
      isTaskAcknowledgable({ status: 'PENDING', taskType: 'REMINDER' }),
    ).toBe(true);
    expect(
      isTaskAcknowledgable({ status: 'OVERDUE', taskType: 'REMINDER' }),
    ).toBe(true);
    expect(isTaskAcknowledgable({ status: 'PENDING', taskType: 'FORM' })).toBe(
      false,
    );
    expect(
      isTaskAcknowledgable({ status: 'COMPLETED', taskType: 'REMINDER' }),
    ).toBe(false);
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

  it('未完成五态口径（「我的待办」默认筛选；失访候选仍需医生确认，E15-T05）', () => {
    expect(TASK_OPEN_STATUSES).toEqual([
      'PENDING',
      'IN_PROGRESS',
      'OVERDUE',
      'LOST_CANDIDATE',
      'SKIPPED_SUSPENDED',
    ]);
    expect(isTaskEnterable({ ...base, status: 'LOST_CANDIDATE' })).toBe(true);
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

describe('isTaskClaimable / isTaskReassignable（E15-T03 / A-FU-009）', () => {
  it('待认领 = 负责人为空且未完成四态', () => {
    expect(
      isTaskClaimable({
        assigneeName: null,
        status: 'PENDING',
      } as never),
    ).toBe(true);
    expect(
      isTaskClaimable({
        assigneeName: null,
        status: 'OVERDUE',
      } as never),
    ).toBe(true);
    expect(
      isTaskClaimable({
        assigneeName: '王医生（示例）',
        status: 'PENDING',
      } as never),
    ).toBe(false);
    expect(
      isTaskClaimable({ assigneeName: null, status: 'COMPLETED' } as never),
    ).toBe(false);
    expect(
      isTaskClaimable({ assigneeName: null, status: 'TERMINATED' } as never),
    ).toBe(false);
  });

  it('未完成四态可改派，终态不可', () => {
    expect(isTaskReassignable({ status: 'PENDING' })).toBe(true);
    expect(isTaskReassignable({ status: 'IN_PROGRESS' })).toBe(true);
    expect(isTaskReassignable({ status: 'OVERDUE' })).toBe(true);
    expect(isTaskReassignable({ status: 'SKIPPED_SUSPENDED' })).toBe(true);
    expect(isTaskReassignable({ status: 'COMPLETED' })).toBe(false);
    expect(isTaskReassignable({ status: 'LOST' })).toBe(false);
    expect(isTaskReassignable({ status: 'TERMINATED' })).toBe(false);
  });
});

describe('taskAssign*（改派结果逐条文案）', () => {
  it('成功「已改派」；跳过按原因码，未知码原样显示', () => {
    expect(taskAssignOutcomeText({ outcome: 'SUCCESS', reason: null })).toBe(
      '已改派',
    );
    expect(
      taskAssignOutcomeText({ outcome: 'SKIP', reason: 'DOCTOR_NOT_IN_WARD' }),
    ).toBe('该医生不负责患者所在病区');
    expect(
      taskAssignOutcomeText({ outcome: 'SKIP', reason: 'TASK_UNCHANGED' }),
    ).toBe('负责人已是所选医生');
    expect(taskAssignOutcomeText({ outcome: 'SKIP', reason: 'XX_NEW' })).toBe(
      'XX_NEW',
    );
  });

  it('越范围行不暴露存在；其余行「患者 · 节点」', () => {
    expect(taskAssignItemLabel({ nodeName: null, patientName: null })).toBe(
      '（不在您的数据范围内）',
    );
    expect(
      taskAssignItemLabel({
        nodeName: '第 4 年随访（示例）',
        patientName: '周*（示例）',
      }),
    ).toBe('周*（示例） · 第 4 年随访（示例）');
  });

  it('计数文案', () => {
    expect(taskAssignSummary({ items: [], skipped: 2, succeeded: 3 })).toBe(
      '已改派 3、跳过 2',
    );
  });
});
