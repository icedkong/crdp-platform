/**
 * E15-T02 / A-FU-004、A-FU-005、C-FU-020：我的任务 / 任务列表与随访工作台
 * （docs/api/followup.md §2、§3，页面 /followup/task 与 /followup/record/:recordId，pages.md §5、§7）。
 *
 * 后端状态为 fu_task 原始枚举（E15-T01 状态机）+ completeKind（followup V53）+ createType
 * （E14-T04 / E14-T05），展示文案按 components.md §2 task 域派生：已完成按完成方式细分
 * 「已完成 / 已完成（窗口前完成）/ 已完成（逾期完成）」，补录任务派生「已完成（补录）」。
 * 认领与改派（E15-T03 / A-FU-009）已实现：待认领行可认领（followup:task:view，
 * 认领人 = 当前登录用户），单个与批量改派走 batch-assign（followup:task:assign，
 * 改派后新负责人收 TASK_REASSIGNED 站内信）；最近联系随 E15-T04（fu_contact_log）；
 * C 的器械类型 / 随访时间点 / 上次标红项数随 E21 / E17。
 */
import { BATCH_SKIP_REASON_TEXT } from './batch-operation';

/** fu_task.status（E15-T01 状态机，components.md §2 task 域的展示派生见 {@link taskDisplayStatus}）。 */
export type TaskStatus =
  | 'COMPLETED'
  | 'IN_PROGRESS'
  | 'LOST'
  | 'OVERDUE'
  | 'PENDING'
  | 'SKIPPED_SUSPENDED'
  | 'TERMINATED';

/** 完成方式（followup V53：状态仍为 COMPLETED，由本列派生展示）。 */
export type TaskCompleteKind = 'BEFORE_WINDOW' | 'ON_TIME' | 'OVERDUE' | null;

/** 页签（A-FU-004：默认显示「我的待办」）。 */
export type TaskScope = 'ALL' | 'MINE' | 'WARD';

/** 列表行（姓名 / 病案号只含脱敏值；列表不提供逐行明文）。 */
export interface TaskRow {
  assigneeName?: null | string;
  completeKind?: TaskCompleteKind;
  createType?: null | string;
  /** 已关联的草稿记录（开始录入后；pages.md §5 统一用 recordId）。 */
  id: string;
  lastContactResult?: null | string;
  legacyNo?: null | string;
  mrn: string;
  /** 下次计划日期缓存（fu_enrollment.next_plan_date，「下次随访 · 计划 …」）。 */
  nextPlanDate?: null | string;
  nodeName?: null | string;
  /** 第 N 次（随访年度等展示位）。 */
  occurrence: number;
  patientId: string;
  patientName: string;
  planDate: string;
  recordId?: null | string;
  status: TaskStatus;
  taskType: string;
  wardName?: null | string;
  windowEnd: string;
  windowStart: string;
}

/** 页签徽标计数（同一非页签筛选下三态各计一次，「我的待办 12 · 本病区 40 · 全部」）。 */
export interface TaskTabCounts {
  mine: number;
  total: number;
  ward: number;
}

/** 病区候选（列表页病区下拉，启用项；行级隔离仍按数据范围）。 */
export interface TaskWardOption {
  id: number;
  name: string;
}

/** 检索结果（统一分页口径 + 页签计数 + 病区候选）。 */
export interface TaskPage {
  items: TaskRow[];
  pageNo: number;
  pageSize: number;
  tabCounts: TaskTabCounts;
  total: number;
  wards: TaskWardOption[];
}

/** 检索请求（docs/api/followup.md §2；scope 缺省 MINE 我的待办 = 未完成四态）。 */
export interface TaskSearchRequest {
  doctorId?: number | string;
  dueFrom?: null | string;
  dueTo?: null | string;
  keyword?: null | string;
  nodeName?: null | string;
  pageNo?: number;
  pageSize?: number;
  /** 页签：MINE 我的待办（默认）/ WARD 本病区 / ALL 全部。 */
  scope?: TaskScope;
  status?: null | TaskStatus[];
  /** 只看待认领（assignee NULL，病区公共池，A-FU-009 / E15-T03）。 */
  unassigned?: boolean;
  wardIds?: null | number[];
}

/** 队列结果（表单页「上一条 / 下一条」，尽头 null）。 */
export interface TaskQueueView {
  nextId?: null | string;
  prevId?: null | string;
}

/** 开始录入结果（统一用 recordId 路由；幂等：已开始直接返回已有草稿）。 */
export interface TaskStartView {
  recordCreated: boolean;
  recordId: string;
  taskId: string;
}

/** 记录详情的任务上下文（表单页顶栏与横幅）。 */
export interface RecordTaskContext {
  assigneeName?: null | string;
  completeKind?: TaskCompleteKind;
  createType?: null | string;
  mrn: string;
  nextPlanDate?: null | string;
  nodeName?: null | string;
  occurrence: number;
  patientId: string;
  patientName: string;
  planDate: string;
  taskId: string;
  taskStatus: TaskStatus;
  taskType: string;
  wardName?: null | string;
  windowEnd: string;
  windowStart: string;
}

/** 记录详情（followup.md §3 的 E15-T02 子集；previous / history 随后续任务）。 */
export interface RecordDetailView {
  context?: null | RecordTaskContext;
  data: Record<string, unknown>;
  dataMeta: Record<string, unknown>;
  formCode: string;
  formVersion: number;
  id: string;
  status: 'DRAFT' | 'SUBMITTED';
  version: number;
}

/** 提交结果（「提交后任务完成」验收位；无关联任务时 taskId / taskStatus 为空）。 */
export interface RecordSubmitResult {
  completeKind?: TaskCompleteKind;
  fieldChanges: number;
  recordId: string;
  taskId?: null | string;
  taskStatus?: null | TaskStatus;
  warnings: Array<{ key: string; message: string }>;
}

/** 基础状态文案（components.md §2 task 域；细分展示态见 {@link taskDisplayStatus}）。 */
export const TASK_STATUS_TEXT: Record<TaskStatus, string> = {
  PENDING: '待随访',
  IN_PROGRESS: '进行中',
  COMPLETED: '已完成',
  OVERDUE: '逾期',
  LOST: '失访',
  TERMINATED: '停止',
  SKIPPED_SUSPENDED: '期间未执行',
};

/** 状态筛选下拉（顺序即展示顺序；「我的待办」页签默认未完成四态）。 */
export const TASK_STATUS_OPTIONS: Array<{ label: string; value: TaskStatus }> =
  (
    [
      'PENDING',
      'IN_PROGRESS',
      'OVERDUE',
      'COMPLETED',
      'LOST',
      'TERMINATED',
      'SKIPPED_SUSPENDED',
    ] as const
  ).map((value) => ({ label: TASK_STATUS_TEXT[value], value }));

/** 未完成四态（「我的待办」口径：PENDING / IN_PROGRESS / OVERDUE / SKIPPED_SUSPENDED）。 */
export const TASK_OPEN_STATUSES: TaskStatus[] = [
  'PENDING',
  'IN_PROGRESS',
  'OVERDUE',
  'SKIPPED_SUSPENDED',
];

/** 页签定义（pages.md §7 顶部 a-tabs；徽标取 tabCounts 对应位）。 */
export const TASK_TABS: Array<{ label: string; scope: TaskScope }> = [
  { scope: 'MINE', label: '我的待办' },
  { scope: 'WARD', label: '本病区' },
  { scope: 'ALL', label: '全部' },
];

/** 展示状态派生（StatusTag 传中文，颜色由 tokens statusTone 决定）。 */
export function taskDisplayStatus(task: {
  completeKind?: TaskCompleteKind;
  createType?: null | string;
  status: TaskStatus;
}): string {
  if (task.status === 'COMPLETED') {
    if (task.createType === 'BACKFILL') return '已完成（补录）';
    if (task.completeKind === 'BEFORE_WINDOW') return '已完成（窗口前完成）';
    if (task.completeKind === 'OVERDUE') return '已完成（逾期完成）';
    return '已完成';
  }
  return TASK_STATUS_TEXT[task.status];
}

/** 状态提示（StatusTag tooltip）：窗口结束后完成的验收口径。 */
export function taskStatusTooltip(display: string): string {
  if (display === '已完成（逾期完成）') {
    return '窗口结束后完成，记逾期完成';
  }
  if (display === '已完成（窗口前完成）') {
    return '任务开启日早于窗口开始日时，窗口开启前提交';
  }
  return '';
}

/** 任务是否可进入录入（未完成四态且 FORM 任务；站内提醒类任务无需填表，A-FU-026）。 */
export function isTaskEnterable(task: TaskRow): boolean {
  return task.taskType === 'FORM' && TASK_OPEN_STATUSES.includes(task.status);
}

// ---------------------------------------------------------------- 认领与改派（E15-T03 / A-FU-009）

/** 改派逐条结果（patientName / mrn 只含脱敏值；TASK_NOT_FOUND 行不带摘要，不暴露存在）。 */
export interface TaskAssignItem {
  mrn: null | string;
  nodeName: null | string;
  outcome: 'SKIP' | 'SUCCESS';
  /** 越范围 / 不存在时 null（不暴露存在）。 */
  patientId: null | string;
  patientName: null | string;
  reason: null | string;
  taskId: string;
  wardName: null | string;
}

/** 批量改派结果（items 与请求顺序一致，逐条可查）。 */
export interface TaskAssignResult {
  items: TaskAssignItem[];
  skipped: number;
  succeeded: number;
}

/** 批量改派请求（单个改派 = 传一条；reason 可选、≤255 字）。 */
export interface TaskAssignRequest {
  assigneeId: number | string;
  reason?: string;
  taskIds: Array<number | string>;
}

/** 认领结果。 */
export interface TaskClaimView {
  status: TaskStatus;
  taskId: string;
}

/** 任务是否可认领（待认领 = 负责人为空，且未完成四态，A-FU-009「从公共池认领」）。 */
export function isTaskClaimable(task: TaskRow): boolean {
  return !task.assigneeName && TASK_OPEN_STATUSES.includes(task.status);
}

/** 任务是否可改派（未完成四态；终态任务的随访已执行完，无可再派的人）。 */
export function isTaskReassignable(task: { status: TaskStatus }): boolean {
  return TASK_OPEN_STATUSES.includes(task.status);
}

/** 改派结果逐条文案（成功「已改派」；跳过按原因码，码未知时原样显示）。 */
export function taskAssignOutcomeText(item: {
  outcome: string;
  reason: null | string;
}): string {
  if (item.outcome === 'SUCCESS') return '已改派';
  return item.reason
    ? (BATCH_SKIP_REASON_TEXT[item.reason] ?? item.reason)
    : '跳过';
}

/** 改派结果行定位文案（患者脱敏名 + 节点；越范围行不暴露存在）。 */
export function taskAssignItemLabel(item: {
  nodeName: null | string;
  patientName: null | string;
}): string {
  if (!item.patientName) return '（不在您的数据范围内）';
  return item.nodeName
    ? `${item.patientName} · ${item.nodeName}`
    : item.patientName;
}

/** 改派结果计数文案（「已改派 3、跳过 2」）。 */
export function taskAssignSummary(result: TaskAssignResult): string {
  return `已改派 ${result.succeeded}、跳过 ${result.skipped}`;
}

/** 窗口列文案（「10-29 ~ 11-12」，同月省略月份：pages.md §7 列样例）。 */
export function taskWindowText(windowStart: string, windowEnd: string): string {
  if (!windowStart || !windowEnd) return '—';
  const start = windowStart.slice(0, 10);
  const end = windowEnd.slice(0, 10);
  const [startYear, startMonth, startDay] = start.split('-');
  const [endYear, endMonth, endDay] = end.split('-');
  if (startYear === endYear && startMonth === endMonth) {
    return `${startMonth}-${startDay} ~ ${endMonth}-${endDay}`;
  }
  return `${start.slice(5)} ~ ${end.slice(5)}`;
}
