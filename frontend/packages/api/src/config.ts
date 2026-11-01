/**
 * E06-T10 / PLT-CFG-001..004、011：平台与租户字典、系统参数与租户参数覆盖。
 * 与后端 `PlatformDictAdminService` / `PlatformParamAdminService` / `TenantDictService` /
 * `TenantParamService` 视图同构（docs/api/platform.md §7、system.md §4）。
 */

/** 字典项来源（租户字典）：PLATFORM 平台项（只能停用 / 恢复）/ TENANT 本院自有项。 */
export type DictItemSource = 'PLATFORM' | 'TENANT';

export type DictStatus = 'DISABLED' | 'ENABLED';

/** 参数值类型（platform-spec §10「关键字字段 · 系统参数」）。 */
export type ParamValueType = 'BOOLEAN' | 'ENUM' | 'JSON' | 'NUMBER' | 'TEXT';

/** 平台字典类型视图（GET /api/platform/dicts）。 */
export interface PlatformDictView {
  code: string;
  description: null | string;
  enabledItemCount: number;
  itemCount: number;
  name: string;
  status: DictStatus;
  updatedAt: null | string;
}

/** 平台字典项视图（GET /api/platform/dicts/{code}/items）。 */
export interface PlatformDictItemView {
  itemCode: string;
  label: string;
  sortNo: number;
  status: DictStatus;
  updatedAt: null | string;
}

/** 平台字典新建 / 维护请求。编码创建后不可改。 */
export interface PlatformDictSaveRequest {
  code?: string;
  description?: null | string;
  name: string;
  status?: DictStatus | null;
}

export interface PlatformDictItemSaveRequest {
  itemCode?: string;
  label: string;
  sortNo?: null | number;
  status?: DictStatus | null;
}

/** 平台参数视图（GET /api/platform/params）。 */
export interface PlatformParamView {
  description: null | string;
  name: string;
  paramKey: string;
  platformValue: string;
  tenantOverridable: boolean;
  updatedAt: null | string;
  /** 取值范围 JSON：NUMBER {min,max} / TEXT {maxLength} / ENUM {options[]}；BOOLEAN / JSON 为空。 */
  valueRange: null | string;
  valueType: ParamValueType;
}

/** 平台参数新建 / 维护请求。参数键创建后不可改。 */
export interface PlatformParamSaveRequest {
  description?: null | string;
  name: string;
  paramKey?: string;
  platformValue: string;
  tenantOverridable: boolean;
  valueRange?: null | string;
  valueType: ParamValueType;
}

/** 租户字典类型视图（GET /api/hospital/system/dicts）：source 区分平台继承与本院自有。 */
export interface TenantDictTypeView {
  code: string;
  description: null | string;
  /** 本院停用的平台项数。 */
  disabledPlatformCount: number;
  itemCount: number;
  name: string;
  source: DictItemSource;
  status: DictStatus;
  tenantItemCount: number;
  updatedAt: null | string;
}

/** 租户字典项视图（生效集 = 平台项 − 本院停用 + 本院自有项）。 */
export interface TenantDictItemView {
  editable: boolean;
  itemCode: string;
  label: string;
  sortNo: number;
  source: DictItemSource;
  status: DictStatus;
  updatedAt: null | string;
}

/** 租户字典新建 / 维护请求。本院字典编码创建后不可改；平台字典不可改（40001）。 */
export interface TenantDictSaveRequest {
  code?: string;
  description?: null | string;
  name: string;
  status?: DictStatus | null;
}

export interface TenantDictItemSaveRequest {
  itemCode?: string;
  label: string;
  sortNo?: null | number;
  /** 平台项只认 status（停用 / 恢复）；本院自有项可改文字与排序。 */
  status?: DictStatus | null;
}

/** 租户参数视图（GET /api/hospital/system/params）。 */
export interface TenantParamView {
  description: null | string;
  effectiveValue: string;
  name: string;
  overridden: boolean;
  paramKey: string;
  platformValue: string;
  /** 参数被平台锁定且本院有覆盖时 true（覆盖失效并提示，覆盖行保留）。 */
  stale: boolean;
  tenantOverridable: boolean;
  tenantValue: null | string;
  updatedAt: null | string;
  valueRange: null | string;
  valueType: ParamValueType;
}

/** 覆盖请求（PUT /api/hospital/system/params/{key}）。 */
export interface TenantParamOverrideRequest {
  reason?: null | string;
  value: string;
}

/** 覆盖变更记录行（GET /api/hospital/system/params/logs，倒序）。 */
export interface TenantParamLogView {
  action: 'OVERRIDE' | 'RESET';
  createdAt: string;
  newValue: null | string;
  oldValue: null | string;
  operatorId: null | number;
  paramKey: string;
  reason: null | string;
}

// ---------------------------------------------------------------- 文案与展示 helper

export const PARAM_VALUE_TYPE_TEXT: Record<ParamValueType, string> = {
  BOOLEAN: '布尔',
  ENUM: '枚举',
  JSON: 'JSON',
  NUMBER: '数值',
  TEXT: '文本',
};

export function paramValueTypeText(type: null | string | undefined): string {
  return PARAM_VALUE_TYPE_TEXT[type as ParamValueType] ?? String(type ?? '—');
}

export const DICT_STATUS_TEXT: Record<DictStatus, string> = {
  DISABLED: '停用',
  ENABLED: '启用',
};

export function dictStatusText(status: null | string | undefined): string {
  return DICT_STATUS_TEXT[status as DictStatus] ?? String(status ?? '—');
}

export const DICT_SOURCE_TEXT: Record<DictItemSource, string> = {
  PLATFORM: '平台',
  TENANT: '本院',
};

export function dictSourceText(source: null | string | undefined): string {
  return DICT_SOURCE_TEXT[source as DictItemSource] ?? String(source ?? '—');
}

export const PARAM_LOG_ACTION_TEXT: Record<
  TenantParamLogView['action'],
  string
> = {
  OVERRIDE: '覆盖',
  RESET: '恢复默认',
};

export function paramLogActionText(action: null | string | undefined): string {
  return (
    PARAM_LOG_ACTION_TEXT[action as TenantParamLogView['action']] ??
    String(action ?? '—')
  );
}

/** 取值范围的可读文案（列表列与编辑提示共用）：数值 5–50；文本 ≤255 字；枚举列出选项；空返回「—」。 */
export function paramRangeText(
  valueType: null | ParamValueType | string | undefined,
  range: null | string | undefined,
): string {
  if (!range) {
    return valueType === 'BOOLEAN' || valueType === 'JSON' ? '不限' : '—';
  }
  let parsed: any;
  try {
    parsed = JSON.parse(range);
  } catch {
    return range;
  }
  if (valueType === 'NUMBER') {
    const min = parsed?.min;
    const max = parsed?.max;
    if (min !== undefined && max !== undefined) {
      return `${min}–${max}`;
    }
    if (min !== undefined) {
      return `≥ ${min}`;
    }
    if (max !== undefined) {
      return `≤ ${max}`;
    }
  }
  if (valueType === 'TEXT' && typeof parsed?.maxLength === 'number') {
    return `≤ ${parsed.maxLength} 字`;
  }
  if (valueType === 'ENUM' && Array.isArray(parsed?.options)) {
    return parsed.options.join(' / ');
  }
  return range;
}

/**
 * 覆盖值的本地预校验（与后端 ParamValueValidator 同口径，后端为准）。
 * 返回 null 表示通过，否则为错误消息。
 */
export function validateOverrideValue(
  valueType: ParamValueType | string,
  value: string,
  range: null | string | undefined,
): null | string {
  const text = (value ?? '').trim();
  if (!text) {
    return '取值不能为空';
  }
  if (text.length > 512) {
    return '取值不能超过 512 个字符';
  }
  let parsedRange: any;
  try {
    parsedRange = range ? JSON.parse(range) : null;
  } catch {
    parsedRange = null;
  }
  switch (valueType) {
    case 'BOOLEAN': {
      return text === 'true' || text === 'false'
        ? null
        : '取值只能是 true 或 false';
    }
    case 'ENUM': {
      const options: unknown[] = Array.isArray(parsedRange?.options)
        ? parsedRange.options
        : [];
      return options.includes(text)
        ? null
        : `取值必须是选项之一：${options.join(' / ')}`;
    }
    case 'NUMBER': {
      if (!/^-?\d+(\.\d+)?$/.test(text)) {
        return '取值必须是数字';
      }
      const num = Number(text);
      const { min, max } = parsedRange ?? {};
      if (typeof min === 'number' && num < min) {
        return `取值不得小于 ${min}`;
      }
      if (typeof max === 'number' && num > max) {
        return `取值不得大于 ${max}`;
      }
      return null;
    }
    case 'TEXT': {
      const maxLength =
        typeof parsedRange?.maxLength === 'number'
          ? parsedRange.maxLength
          : 512;
      return text.length > maxLength ? `文本长度不能超过 ${maxLength}` : null;
    }
    case 'JSON': {
      try {
        JSON.parse(text);
        return null;
      } catch {
        return '取值必须是合法 JSON';
      }
    }
    default: {
      return `不支持的值类型：${String(valueType)}`;
    }
  }
}
