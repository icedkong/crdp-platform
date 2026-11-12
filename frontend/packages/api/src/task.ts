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
import type { CompareRule } from './compare-rule';

import { BATCH_SKIP_REASON_TEXT } from './batch-operation';

/** fu_task.status（E15-T01 状态机 + E15-T05 失访候选，components.md §2 task 域的展示派生见 {@link taskDisplayStatus}）。 */
export type TaskStatus =
  | 'COMPLETED'
  | 'IN_PROGRESS'
  | 'LOST'
  | 'LOST_CANDIDATE'
  | 'OVERDUE'
  | 'PENDING'
  | 'SKIPPED_SUSPENDED'
  | 'TERMINATED';

/** 完成方式（followup V53：状态仍为 COMPLETED，由本列派生展示）。 */
export type TaskCompleteKind = 'BEFORE_WINDOW' | 'ON_TIME' | 'OVERDUE' | null;

/** 任务类型（fu_task.task_type，A-FU-026）：FORM 电话随访 + 表单 / REMINDER 站内提醒（无需填表）。 */
export type TaskType = 'FORM' | 'REMINDER';

/** 任务类型中文（components.md §2 task 域）。 */
export const TASK_TYPE_TEXT: Record<TaskType, string> = {
  FORM: '填表随访',
  REMINDER: '站内提醒',
};

/** 临时任务窗口宽度（E15-T06 / A-FU-013）：默认与上限与后端一致（A 医院预置窗口前后 7 天）。 */
export const TEMP_TASK_DEFAULT_WINDOW_DAYS = 7;
export const TEMP_TASK_MAX_WINDOW_DAYS = 365;

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
  /** 完成时间区间闭端（E19-T03 看板已完成列钻取，DATE(completed_at)；页面筛选条暂不提供）。 */
  completedFrom?: null | string;
  completedTo?: null | string;
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
  /** 窗口包含区间（E19-T03 任务表「今日任务」钻取：window_start ≤ windowTo 且 window_end ≥ windowFrom）。 */
  windowFrom?: null | string;
  windowTo?: null | string;
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

/** 补录 / 计划外记录的患者摘要（context 为 null 时表单页头幅用；姓名 / 病案号为脱敏值）。 */
export interface RecordPatientBrief {
  mrn: string;
  patientId: string;
  patientName: string;
  wardName?: null | string;
}

/**
 * 上一次记录（E17-T02 / A-FU-027；由后端按规则参数选取，前端不自己找）：FOLLOWUP = 上一次
 * 已提交的同表单随访记录；BASELINE = 第 1 年（或此前无随访）的基线回落（出院前超声，Q41）。
 * `values` 只带启用规则引用的键（targetField ∪ 新发事件的 params.dateField），缺失键由求值侧
 * 按「上一次该字段为空跳过该条规则」处理（docs/api/followup.md §3）。
 */
export interface RecordPreviousView {
  /** FOLLOWUP 带对比时点；BASELINE 无日期。 */
  date?: null | string;
  kind: 'BASELINE' | 'FOLLOWUP';
  recordId?: null | string;
  /** 提交来源（TASK / BACKFILL / IMPORT），BASELINE 无。 */
  source?: null | string;
  values: Record<string, unknown>;
}

/**
 * 器械上下文（E21-T03 / C-PAT-007、C-FU-009）：按记录随访日期匹配当时在用的器械（visitDate 为空
 * 的任务录入回落当前器械）；页面以 `{ device: … }` 传入 FormRenderer 的 `context` 驱动按器械条件
 * 显示（ctx device.type / device.hasRaLead / device.chambers，components.md §10）。implant 开关
 * 关闭或无匹配器械为 null。
 */
export interface RecordDeviceContext {
  /** 在用电极去重腔室数（AV 间期「双腔及以上」用 gte 2）；无导线为 0。 */
  chambers: number;
  /** 是否当前（在用）器械——按随访日期匹配到历史器械时为 false。 */
  current: boolean;
  /** 有在用 RA 电极（心房参数组显示条件，C-FU-009）。 */
  hasRaLead: boolean;
  implantDate: string;
  implantId: string;
  /** 起搏器类型编码（字典 pacemaker_type）。 */
  type: string;
  typeLabel?: null | string;
}

/** 记录详情（followup.md §3；E15-T07 起附 source 与 visitDate，taskless 记录附 patient 摘要；E17-T02 起附 previous、E17-T03 起附 compareRules、E21-T03 起附 device）。 */
export interface RecordDetailView {
  /** 该表单启用对比规则（E17-T03）：前端实时标红与后端保存复算共用；功能关闭 / 无启用规则为 null。 */
  compareRules?: CompareRule[] | null;
  context?: null | RecordTaskContext;
  data: Record<string, unknown>;
  dataMeta: Record<string, unknown>;
  /** 器械上下文（E21-T03）：implant 开关关闭或无匹配器械为 null。 */
  device?: null | RecordDeviceContext;
  formCode: string;
  formVersion: number;
  id: string;
  patient?: null | RecordPatientBrief;
  /** 上一次记录解析（E17-T02 / A-FU-027）：功能关闭、无启用规则或回落字段全空为 null。 */
  previous?: null | RecordPreviousView;
  source?: null | string;
  status: 'DRAFT' | 'SUBMITTED';
  version: number;
  visitDate?: null | string;
}

/** 历史补录创建结果（recordId 即随访表单路由，pages.md §5）。 */
export interface BackfillCreateView {
  formCode: string;
  patientId: string;
  recordId: string;
  visitDate: string;
}

/**
 * 历史补录 / 计划外随访创建请求（A-FU-014 / C-FU-013 过去日期；C-FU-022 计划外当天可录、
 * 须患者在随访中——E15-T06 开放 UNPLANNED；远程监测 REMOTE 随 E21）。
 */
export interface BackfillCreateRequest {
  source: 'BACKFILL' | 'UNPLANNED';
  visitDate: string;
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
  LOST_CANDIDATE: '失访候选',
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
      'LOST_CANDIDATE',
      'COMPLETED',
      'LOST',
      'TERMINATED',
      'SKIPPED_SUSPENDED',
    ] as const
  ).map((value) => ({ label: TASK_STATUS_TEXT[value], value }));

/** 未完成五态（「我的待办」口径：PENDING / IN_PROGRESS / OVERDUE / LOST_CANDIDATE / SKIPPED_SUSPENDED——失访候选仍需医生确认，E15-T05）。 */
export const TASK_OPEN_STATUSES: TaskStatus[] = [
  'PENDING',
  'IN_PROGRESS',
  'OVERDUE',
  'LOST_CANDIDATE',
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
  taskType?: null | string;
}): string {
  if (task.status === 'COMPLETED') {
    if (task.createType === 'BACKFILL') return '已完成（补录）';
    if (task.completeKind === 'BEFORE_WINDOW') return '已完成（窗口前完成）';
    if (task.completeKind === 'OVERDUE') return '已完成（逾期完成）';
    return '已完成';
  }
  if (task.status === 'PENDING' && task.createType === 'FOLLOWUP') {
    return '待随访（跟进）';
  }
  if (
    task.status === 'PENDING' &&
    task.createType === 'TEMP' &&
    task.taskType === 'REMINDER'
  ) {
    return '待提醒（临时）';
  }
  if (task.status === 'PENDING' && task.createType === 'TEMP') {
    return '待随访（临时）';
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

/**
 * 任务是否可「完成提醒」（E15-T06 / A-FU-026 验收「站内提醒类任务无需填表」）：
 * 站内提醒类任务且未完成——确认即完成，不创建记录；填表任务须提交随访记录。
 */
export function isTaskAcknowledgable(task: {
  status: TaskStatus;
  taskType: string;
}): boolean {
  return (
    task.taskType === 'REMINDER' && TASK_OPEN_STATUSES.includes(task.status)
  );
}

// ---------------------------------------------------------------- 临时添加任务（E15-T06 / A-FU-013）

/** 临时添加任务请求（POST /followup/tasks；除 patientId / planDate 外可空——任务类型缺省 FORM）。 */
export interface TempTaskCreateRequest {
  assigneeId?: number | string;
  enrollmentId?: number | string;
  formCode?: string;
  nodeId?: number | string;
  /** 备注（如「再住院后加访」），≤255 字。 */
  note?: string;
  patientId: number | string;
  planDate: string;
  /** 任务类型：FORM 填表随访 / REMINDER 站内提醒（A-FU-026）。 */
  taskType?: TaskType;
  /** 窗口宽度（天，0–365）：窗口 = 计划日期 ± 宽度，缺省 7。 */
  windowDays?: number;
}

/** 临时添加任务结果（id 超过 2^53 序列化为字符串）。 */
export interface TempTaskView {
  createType: string;
  enrollmentId: string;
  formCode?: null | string;
  nodeId: string;
  patientId: string;
  planDate: string;
  status: TaskStatus;
  taskId: string;
  taskType: TaskType;
  windowEnd: string;
  windowStart: string;
}

/** 完成提醒结果（A-FU-026：确认即完成，状态 COMPLETED + 完成方式）。 */
export interface TaskAcknowledgeView {
  completeKind: TaskCompleteKind;
  status: TaskStatus;
  taskId: string;
}

// ---------------------------------------------------------------- 确认失访（S5 / A-FU-012 · E15-T05）

/**
 * 确认失访结果（POST /followup/tasks/{id}/confirm-lost）：
 * terminatedFollowups = 同窗口关闭的未完成跟进任务数；patientLostPrompt = 连续 2 个
 * 窗口失访——前端提示医生把患者状态改为失访（不自动改，A 规格「系统提示后由医生改为失访」）。
 */
export interface TaskLostConfirmView {
  patientLostPrompt: boolean;
  status: TaskStatus;
  taskId: string;
  terminatedFollowups: number;
}

/** 失访候选任务可「确认失访」（医生确认后标为失访，A-FU-012；随访员只看到候选标记）。 */
export function isTaskLostCandidate(task: { status: TaskStatus }): boolean {
  return task.status === 'LOST_CANDIDATE';
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
