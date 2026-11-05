/**
 * E15-T02 / A-FU-004、C-FU-020 · E15-T03 A-FU-009：我的任务页（pages.md §7）的纯逻辑。
 *
 * - 路由 query（tab=mine|ward|all、status=）与筛选状态互转（分享链接与页签切换）；
 * - 看板分组（按状态分列：待随访 / 进行中 / 逾期 / 已完成；不支持拖拽改状态——状态由业务流转决定）；
 * - 录入入口：未完成 FORM 任务经 POST /tasks/{id}/start 换 recordId 后新标签页打开
 *   /followup/record/:recordId（pages.md §5「任务开启时已创建草稿记录，统一用 recordId」）；
 *   已关联草稿（recordId 有值）直接打开；
 * - 检索请求装配（MINE 页签默认不强制未完成状态——显式选状态时可回看已完成）；
 * - 认领与改派（E15-T03）：待认领筛选（unassigned，负责人下拉「全部 / 待认领」）、
 *   可改派行判定（未完成四态）、改派结果逐条文案。
 */
import type {
  TaskRow,
  TaskScope,
  TaskSearchRequest,
  TaskStatus,
} from '@crdp/api';

import { TASK_OPEN_STATUSES } from '@crdp/api';

/** 页签 → 路由 query 值（pages.md §7：tab=mine|ward|all）。 */
const SCOPE_QUERY: Record<TaskScope, string> = {
  MINE: 'mine',
  WARD: 'ward',
  ALL: 'all',
};

const QUERY_SCOPE: Record<string, TaskScope> = {
  mine: 'MINE',
  ward: 'WARD',
  all: 'ALL',
};

/** 路由 query → 页签（未知值回落 MINE 我的待办，A-FU-004「默认显示我的待办」）。 */
export function scopeOfQuery(tab: null | string | string[]): TaskScope {
  const value = Array.isArray(tab) ? tab[0] : tab;
  return (value && QUERY_SCOPE[value]) || 'MINE';
}

/** 页签 → 路由 query 值。 */
export function queryOfScope(scope: TaskScope): string {
  return SCOPE_QUERY[scope];
}

/** 路由 query status（逗号分隔枚举）→ 状态筛选（未知值丢弃）。 */
export function statusOfQuery(
  status: null | string | string[],
): TaskStatus[] | undefined {
  const value = Array.isArray(status) ? status[0] : status;
  if (!value) return undefined;
  const allowed = new Set<string>([
    'COMPLETED',
    'IN_PROGRESS',
    'LOST',
    'OVERDUE',
    'PENDING',
    'SKIPPED_SUSPENDED',
    'TERMINATED',
  ]);
  const parsed = value
    .split(',')
    .map((item) => item.trim().toUpperCase())
    .filter((item) => allowed.has(item)) as TaskStatus[];
  return parsed.length === 0 ? undefined : parsed;
}

/** 负责人筛选（A-FU-004 负责人维度的最小面：全部 / 待认领，E15-T03 公共池入口）。 */
export const ASSIGNEE_FILTER_OPTIONS = [
  { label: '全部', value: 'ALL' },
  { label: '待认领', value: 'UNASSIGNED' },
] as const;

export type AssigneeFilter = (typeof ASSIGNEE_FILTER_OPTIONS)[number]['value'];

/** 检索请求装配（页签 + 状态 / 病区 / 计划日期窗口 / 负责人筛选；MINE 未显式选状态时不传 status——后端按未完成四态取）。 */
export function buildSearchPayload(input: {
  assignee: AssigneeFilter;
  dueRange?: [string, string] | null;
  scope: TaskScope;
  status: null | TaskStatus[];
  wardIds: number[];
}): TaskSearchRequest {
  const payload: TaskSearchRequest = { scope: input.scope };
  if (input.status && input.status.length > 0) {
    payload.status = input.status;
  }
  if (input.wardIds.length > 0) {
    payload.wardIds = input.wardIds;
  }
  if (input.dueRange && input.dueRange[0] && input.dueRange[1]) {
    payload.dueFrom = input.dueRange[0];
    payload.dueTo = input.dueRange[1];
  }
  if (input.assignee === 'UNASSIGNED') {
    payload.unassigned = true;
  }
  return payload;
}

/** 看板列（按状态分列，pages.md §7：待随访 / 进行中 / 逾期 / 已完成）。 */
export const BOARD_COLUMNS: Array<{
  key: string;
  label: string;
  statuses: TaskStatus[];
}> = [
  { key: 'pending', label: '待随访', statuses: ['PENDING'] },
  { key: 'progress', label: '进行中', statuses: ['IN_PROGRESS'] },
  { key: 'overdue', label: '逾期', statuses: ['OVERDUE'] },
  { key: 'done', label: '已完成', statuses: ['COMPLETED'] },
];

/** 看板分组（当前页行按状态分列；不属四列的状态不进看板——先用状态筛选）。 */
export function boardGroups(rows: TaskRow[]) {
  return BOARD_COLUMNS.map((column) => ({
    ...column,
    rows: rows.filter((row) => column.statuses.includes(row.status)),
  }));
}

/**
 * 录入入口：行已有关联草稿（recordId）直接用；否则先 start 换 recordId。
 * 返回 null = 该行不可进入（终态或 REMINDER）。
 */
export function recordRouteTarget(
  row: TaskRow,
  started: null | TaskRow['recordId'],
): null | string {
  if (row.taskType !== 'FORM') return null;
  if (!TASK_OPEN_STATUSES.includes(row.status)) return null;
  const recordId = row.recordId ?? started;
  return recordId ?? null;
}

/** 录入链接：新标签页打开，携带当前筛选（表单页「上一条 / 下一条」顺当前列表走）。 */
export function recordFormPath(
  recordId: string,
  filter: TaskSearchRequest,
): string {
  const params = new URLSearchParams();
  params.set('scope', queryOfScope(filter.scope ?? 'MINE'));
  if (filter.status && filter.status.length > 0) {
    params.set('status', filter.status.join(','));
  }
  if (filter.wardIds && filter.wardIds.length > 0) {
    params.set('wardIds', filter.wardIds.join(','));
  }
  if (filter.dueFrom) params.set('dueFrom', filter.dueFrom);
  if (filter.dueTo) params.set('dueTo', filter.dueTo);
  if (filter.unassigned) params.set('unassigned', '1');
  return `/followup/record/${recordId}?${params.toString()}`;
}

/** 表单页侧的队列筛选还原（recordFormPath 的逆过程；scope 或 tab 均可）。 */
export function queueFilterOfQuery(query: Record<string, unknown>): {
  scope: TaskScope;
  status?: TaskStatus[];
  unassigned?: boolean;
  wardIds?: number[];
} {
  const scopeRaw =
    (query.scope as null | string) ?? (query.tab as null | string);
  const scope = scopeOfQuery(scopeRaw);
  const status = statusOfQuery(query.status as string);
  let wardIdsRaw: unknown[] = [];
  if (Array.isArray(query.wardIds)) {
    wardIdsRaw = query.wardIds;
  } else if (typeof query.wardIds === 'string' && query.wardIds) {
    wardIdsRaw = [query.wardIds];
  }
  const wardIds = wardIdsRaw
    .map((item) => Number.parseInt(String(item), 10))
    .filter((item) => Number.isInteger(item) && item > 0);
  return {
    scope,
    status,
    unassigned: query.unassigned === '1' || query.unassigned === 'true',
    wardIds: wardIds.length > 0 ? wardIds : undefined,
  };
}

/** 空态文案（有 followup:plan:edit 才显示「去新建计划」按钮，pages.md §7）。 */
export const TASK_EMPTY_TEXT = '暂无随访任务 · 去新建计划';
