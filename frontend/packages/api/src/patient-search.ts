/**
 * E13-T04 / A-PAT-001、A-PAT-002、C-PAT-009：患者列表、检索与筛选的类型与纯逻辑；
 * E13-T06 / A-PAT-014、A-NFR-009、C-PAT-013：高级检索条件树并入检索请求体
 * （接口契约 docs/api/patient.md §1，后端 `com.crdp.followup.patient.api.PatientSearchController`）。
 *
 * 病区隔离由后端数据范围承担（行级，越范围不可见，高级检索条件树同样被改写——自动追加
 * 病区条件）；姓名 / 病案号检索为盲索引精确匹配（keyword 一个输入同时命中两个字段）。
 * 检索词只放组件状态，不写 URL（components.md §11.1）；条件树为结构化 JSON，
 * 后端按字段字典目录编译为参数化查询（不允许原始 SQL）。
 */
import type { ConditionGroup } from './advanced-search';

import { PATIENT_STATUS_TEXT } from './enums';

/** 列表行（patient.md §1 PatientRow）：name / patientNo（本系统编号 = 病案号）只含后端脱敏值。 */
export interface PatientSearchRow {
  age?: null | number;
  baselineCompleteness?: null | number;
  dischargeDate?: null | string;
  doctorName?: null | string;
  followupCompleteness?: null | number;
  gender?: null | string;
  id: string;
  legacyNo?: null | string;
  name: string;
  nextVisitDate?: null | string;
  patientNo: string;
  status: string;
  surgeryDate?: null | string;
  wardName?: null | string;
}

/** 分页结果（统一分页口径）。 */
export interface PatientSearchPage {
  items: PatientSearchRow[];
  pageNo: number;
  pageSize: number;
  total: number;
}

/** 病区筛选候选（启用项）。 */
export interface PatientWardOption {
  id: number;
  name: string;
}

export interface PatientFilterOptions {
  wards: PatientWardOption[];
}

/** 排序项（后端白名单 nextVisitDate / dischargeDate）。 */
export interface PatientSortSpec {
  field: 'dischargeDate' | 'nextVisitDate';
  order: 'asc' | 'desc';
}

/**
 * 列表页筛选状态（页面持有；日期为 YYYY-MM-DD 字符串）。
 * 姓名 / 病案号共用一个检索词（A-PAT-002「病案号、姓名精确查找」，后端盲索引 OR 匹配）。
 */
export interface PatientFilterState {
  dischargeRange?: [string, string] | null;
  keyword: string;
  nextVisitRange?: [string, string] | null;
  status?: null | string;
  wardId?: null | number | undefined;
}

/** 病区已选（Ant Select 清空会置 undefined，两种「无值」都视为未筛选）。 */
function wardSelected(state: PatientFilterState): boolean {
  return state.wardId !== null && state.wardId !== undefined;
}

/** 检索词长度口径与后端一致（≤64）。 */
export const KEYWORD_MAX_LENGTH = 64;

/** 检索词是否可用（去空白后非空且 ≤64；超长由后端 40001 兜底，前端提前提示）。 */
export function isKeywordValid(keyword: string): boolean {
  const trimmed = keyword.trim();
  return trimmed.length > 0 && trimmed.length <= KEYWORD_MAX_LENGTH;
}

/** 组装后的检索请求体（docs/api/README.md §2.4 结构；condition 为高级检索条件树）。 */
export interface PatientSearchPayload {
  condition?: ConditionGroup;
  filters: Record<string, unknown>;
  keyword?: string;
  pageNo: number;
  pageSize: number;
  sort?: PatientSortSpec[];
}

/** 组装检索请求体：空筛选不进 filters；日期区间未选全不发送（后端按闭区间过滤）。 */
export function buildPatientSearchPayload(
  state: PatientFilterState,
  pageNo: number,
  pageSize: number,
  sort?: PatientSortSpec,
  condition?: ConditionGroup,
): PatientSearchPayload {
  const payload: PatientSearchPayload = { pageNo, pageSize, filters: {} };

  const keyword = state.keyword.trim();
  if (keyword.length > 0) {
    payload.keyword = keyword;
  }
  if (wardSelected(state)) {
    payload.filters.wardIds = [state.wardId];
  }
  if (state.status) {
    payload.filters.status = [state.status];
  }
  if (state.dischargeRange?.[0] && state.dischargeRange?.[1]) {
    payload.filters.dischargeFrom = state.dischargeRange[0];
    payload.filters.dischargeTo = state.dischargeRange[1];
  }
  if (state.nextVisitRange?.[0] && state.nextVisitRange?.[1]) {
    payload.filters.nextVisitFrom = state.nextVisitRange[0];
    payload.filters.nextVisitTo = state.nextVisitRange[1];
  }
  if (sort) {
    payload.sort = [sort];
  }
  if (condition && condition.items.length > 0) {
    payload.condition = condition;
  }
  return payload;
}

/** 是否存在生效的筛选（空态文案分流：无患者 vs 没有符合条件的患者，pages.md §3）。 */
export function hasActiveFilters(
  state: PatientFilterState,
  hasAdvancedCondition = false,
): boolean {
  return (
    state.keyword.trim().length > 0 ||
    wardSelected(state) ||
    Boolean(state.status) ||
    Boolean(state.dischargeRange?.[0] && state.dischargeRange?.[1]) ||
    Boolean(state.nextVisitRange?.[0] && state.nextVisitRange?.[1]) ||
    hasAdvancedCondition
  );
}

/** 重置后的筛选状态（检索词与全部筛选清空）。 */
export function emptyPatientFilters(): PatientFilterState {
  return {
    keyword: '',
    status: null,
    wardId: null,
    dischargeRange: null,
    nextVisitRange: null,
  };
}

/** 随访状态选项（components.md §2 patient 域；顺序即展示顺序）。 */
export function patientStatusOptions(): Array<{
  label: string;
  value: string;
}> {
  return Object.entries(PATIENT_STATUS_TEXT).map(([value, label]) => ({
    label,
    value,
  }));
}

/**
 * 路由 query status → 随访状态筛选（E19-T02 指标卡钻取入口：首页点状态指标带
 * status 进患者列表）；未知值回落 undefined（不筛选），与列表页 query 互转口径一致。
 */
export function patientStatusOfQuery(
  status: null | string | string[],
): null | string | undefined {
  const value = Array.isArray(status) ? status[0] : status;
  if (!value) return undefined;
  const normalized = value.trim().toUpperCase();
  return Object.hasOwn(PATIENT_STATUS_TEXT, normalized)
    ? normalized
    : undefined;
}

/** 空态提示（pages.md §3）：canCreate 决定「暂无患者」是否带新建入口。 */
export function patientEmptyText(
  filtered: boolean,
  canCreate: boolean,
): { actionText: null | string; text: string } {
  if (filtered) {
    return { text: '没有符合条件的患者', actionText: '清除筛选' };
  }
  return {
    text: '暂无患者',
    actionText: canCreate ? '新建患者' : null,
  };
}
