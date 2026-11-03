/**
 * E13-T06 / A-PAT-014、A-PAT-015 · 患者列表高级检索与「我的筛选」的纯逻辑：
 * 保存 / 应用时在页面筛选状态（PatientFilterState + 条件树）与保存的筛选 payload
 * （{filters?, condition?}，不含检索词与分页）之间转换。
 *
 * 口径：检索词（可能是患者姓名）不进保存的筛选（与「检索词不写 URL」同口径）；
 * 应用保存的筛选后仍走 /patients/search 正常校验与数据范围（病区条件由后端自动追加）。
 */
import type {
  ConditionGroup,
  PatientFilterState,
  SavedFilterPayload,
} from '@crdp/api';

import { buildPatientSearchPayload } from '@crdp/api';

/** 当前筛选状态 → 保存的筛选 payload（简单筛选 + 条件树，不含检索词）。 */
export function savedFilterPayloadOf(
  state: PatientFilterState,
  condition: ConditionGroup | null,
): SavedFilterPayload {
  // 复用检索请求体组装（同一套「空筛选不进 filters」口径），再剥掉分页 / 检索词 / 排序
  const body = buildPatientSearchPayload(
    state,
    1,
    20,
    undefined,
    condition ?? undefined,
  );
  const payload: SavedFilterPayload = {};
  if (body.filters && Object.keys(body.filters).length > 0) {
    payload.filters = body.filters;
  }
  if (body.condition) {
    payload.condition = body.condition;
  }
  return payload;
}

/** 保存的筛选 payload → 页面筛选状态（未覆盖的字段清空，检索词一并清空）。 */
export function applySavedFilter(payload: SavedFilterPayload): {
  condition: ConditionGroup | null;
  state: PatientFilterState;
} {
  const filters = payload.filters ?? {};
  return {
    condition: payload.condition ?? null,
    state: {
      keyword: '',
      status: Array.isArray(filters.status)
        ? String(filters.status[0] ?? '') || null
        : null,
      wardId: Array.isArray(filters.wardIds)
        ? ((filters.wardIds[0] as number) ?? null)
        : null,
      dischargeRange: rangeOf(filters.dischargeFrom, filters.dischargeTo),
      nextVisitRange: rangeOf(filters.nextVisitFrom, filters.nextVisitTo),
    },
  };
}

function rangeOf(from: unknown, to: unknown): [string, string] | null {
  if (typeof from === 'string' && typeof to === 'string' && from && to) {
    return [from, to];
  }
  return null;
}

/** payload 是否为空（无简单筛选也无条件树——「保存」按钮置灰与后端校验同口径）。 */
export function savedFilterPayloadIsEmpty(
  payload: SavedFilterPayload,
): boolean {
  const hasFilters =
    payload.filters !== undefined && Object.keys(payload.filters).length > 0;
  return !hasFilters && payload.condition === undefined;
}
