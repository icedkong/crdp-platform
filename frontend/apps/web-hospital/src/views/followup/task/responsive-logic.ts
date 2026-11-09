/**
 * E24-T05 / NFR-RESP-002 · 我的任务页响应式纯逻辑（pages.md §7：
 * 手机任务卡片（患者、节点、窗口、状态、逾期标记）、状态筛选用顶部分段切换、
 * 无批量改派；平板保留主要列、点行进表单）。
 *
 * - 状态分段（全部 + 看板四态，BOARD_COLUMNS 同口径）：单选；分段放不下的
 *   状态（失访候选 / 多选钻取参数）由页面用条件 chip 展示与清除，不丢筛选；
 * - 逾期标记：状态 OVERDUE（窗口判定由后端状态机承担，前端不重复按日期推导）；
 * - 手机卡片操作：点卡片 = 进表单（可录入且有权）/ 只读查看（已有关联记录），
 *   次操作按钮（完成提醒 / 认领 / 改派）显隐与桌面行内同源——手机只去掉
 *   「批量」改派（勾选与批量条随表格一起隐藏），单条改派仍按权限开放。
 */
import type { TaskRow, TaskStatus } from '@crdp/api';

import {
  isTaskAcknowledgable,
  isTaskClaimable,
  isTaskEnterable,
  isTaskReassignable,
} from '@crdp/api';

/** 手机状态分段（pages.md §7「状态筛选用顶部分段切换」）：全部 + 看板四态，单选。 */
export const MOBILE_STATUS_SEGMENTS = [
  { label: '全部', value: 'ALL' },
  { label: '待随访', value: 'PENDING' },
  { label: '进行中', value: 'IN_PROGRESS' },
  { label: '逾期', value: 'OVERDUE' },
  { label: '已完成', value: 'COMPLETED' },
] as const;

export type MobileStatusSegment =
  (typeof MOBILE_STATUS_SEGMENTS)[number]['value'];

/** 分段可表示的四态（其余状态走条件 chip，不硬塞进分段）。 */
const SEGMENT_STATUSES: ReadonlySet<string> = new Set([
  'COMPLETED',
  'IN_PROGRESS',
  'OVERDUE',
  'PENDING',
]);

/** 分段值 → 状态筛选（'ALL' = 不筛；MINE 页签未显式选状态时后端按未完成五态取）。 */
export function statusOfSegment(
  value: MobileStatusSegment,
): null | TaskStatus[] {
  return value === 'ALL' ? null : [value];
}

/**
 * 状态筛选 → 分段值：空 → 'ALL'；四态单选 → 对应分段；其余（多选、失访候选等）
 * 返回 null（分段回落显示『全部』但不回写，页面另用条件 chip 展示与清除）。
 */
export function segmentOfStatus(
  status: null | TaskStatus[],
): MobileStatusSegment | null {
  if (!status || status.length === 0) return 'ALL';
  if (status.length === 1 && SEGMENT_STATUSES.has(status[0] as string)) {
    return status[0] as MobileStatusSegment;
  }
  return null;
}

/** 手机卡片「逾期标记」（pages.md §7 五要素之一；OVERDUE 由后端按窗口判定）。 */
export function isTaskOverdue(task: { status: TaskStatus }): boolean {
  return task.status === 'OVERDUE';
}

export type TaskCardSecondaryAction = 'acknowledge' | 'assign' | 'claim';

/**
 * 手机任务卡片操作：primary = 点卡片的主操作（进表单 / 只读查看，与桌面行内
 * 「录入 / 查看」同源），secondary = 卡片尾部按钮（完成提醒 / 认领 / 改派，
 * 按能力 + 权限过滤；pages.md §7 只去掉「批量」改派）。
 */
export function taskCardActions(
  task: TaskRow,
  perms: { canAssign: boolean; canEditRecord: boolean },
): {
  primary: 'enter' | 'view' | null;
  secondary: TaskCardSecondaryAction[];
} {
  let primary: 'enter' | 'view' | null = null;
  if (perms.canEditRecord && isTaskEnterable(task)) {
    primary = 'enter';
  } else if (task.recordId) {
    primary = 'view';
  }
  const secondary: TaskCardSecondaryAction[] = [];
  if (perms.canEditRecord && isTaskAcknowledgable(task)) {
    secondary.push('acknowledge');
  }
  if (isTaskClaimable(task)) secondary.push('claim');
  if (perms.canAssign && isTaskReassignable(task)) secondary.push('assign');
  return { primary, secondary };
}

/** 手机「筛选」抽屉携带的筛选状态（状态分段常驻顶栏，不进抽屉）。 */
export interface TaskDrawerFilterState {
  assignee: 'ALL' | 'UNASSIGNED';
  dueRange: [string, string] | null;
  wardIds: number[];
}

/** 抽屉内生效条件数（角标）：病区 / 计划日期（两端齐备）/ 待认领各计 1。 */
export function drawerFilterCount(state: TaskDrawerFilterState): number {
  let count = 0;
  if (state.wardIds.length > 0) count += 1;
  if (state.dueRange?.[0] && state.dueRange[1]) count += 1;
  if (state.assignee === 'UNASSIGNED') count += 1;
  return count;
}
