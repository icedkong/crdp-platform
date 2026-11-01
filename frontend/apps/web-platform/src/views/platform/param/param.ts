import type { ParamValueType, PlatformParamView } from '@crdp/api';

import { paramValueTypeText, validateOverrideValue } from '@crdp/api';

/**
 * E06-T10 / PLT-CFG-003 · 平台系统参数页逻辑（纯函数）。 校验口径与后端
 * PlatformParamAdminService / ParamValueValidator 一致：可覆盖参数必须配置取值范围（BOOLEAN / JSON
 * 除外）；平台默认值也要过范围校验。
 */

/** 参数键：小写字母开头，小写字母 / 数字 / 点（与后端 PARAM_KEY 同口径）。 */
export const PARAM_KEY_PATTERN = /^[a-z][a-z0-9.]{2,63}$/;

export const PARAM_VALUE_TYPES: ParamValueType[] = [
  'TEXT',
  'NUMBER',
  'BOOLEAN',
  'ENUM',
  'JSON',
];

export interface ParamEditModel {
  description: string;
  name: string;
  paramKey: string;
  platformValue: string;
  tenantOverridable: boolean;
  valueRange: string;
  valueType: ParamValueType;
}

export function emptyParamModel(): ParamEditModel {
  return {
    description: '',
    name: '',
    paramKey: '',
    platformValue: '',
    tenantOverridable: false,
    valueRange: '',
    valueType: 'TEXT',
  };
}

export function toParamModel(row: PlatformParamView): ParamEditModel {
  return {
    description: row.description ?? '',
    name: row.name,
    paramKey: row.paramKey,
    platformValue: row.platformValue,
    tenantOverridable: row.tenantOverridable,
    valueRange: row.valueRange ?? '',
    valueType: row.valueType,
  };
}

/** 参数键文案（列表展示：键不可改）。 */
export function levelText(overridable: boolean): string {
  return overridable ? '租户可覆盖' : '平台锁定';
}

/** 校验模型（新建走完整校验；维护不校验键格式与冲突）。 */
export function validateParamModel(
  model: ParamEditModel,
  creating: boolean,
): string[] {
  const errors: string[] = [];
  if (creating && !PARAM_KEY_PATTERN.test(model.paramKey.trim())) {
    errors.push(
      '参数键必须是小写字母开头的 3–64 位小写字母、数字或点（如 tenant.db.pool.max）',
    );
  }
  if (!model.name.trim()) {
    errors.push('名称不能为空');
  }
  if (model.description.trim().length > 255) {
    errors.push('说明不能超过 255 个字符');
  }
  const range = model.valueRange.trim() || null;
  if (
    model.tenantOverridable &&
    range === null &&
    model.valueType !== 'BOOLEAN' &&
    model.valueType !== 'JSON'
  ) {
    errors.push('租户可覆盖的参数必须配置取值范围（布尔 / JSON 除外）');
  }
  if (range !== null) {
    const rangeError = validateRangeShape(model.valueType, range);
    if (rangeError) {
      errors.push(rangeError);
    }
  }
  const valueError = validateOverrideValue(
    model.valueType,
    model.platformValue,
    range,
  );
  if (valueError) {
    errors.push(`平台默认值：${valueError}`);
  }
  return errors;
}

/** 取值范围 JSON 形状校验（与后端 rangeError 同口径；「可覆盖必填」在 validateParamModel 里判）。 */
export function validateRangeShape(
  valueType: ParamValueType | string,
  range: string,
): null | string {
  let parsed: any;
  try {
    parsed = JSON.parse(range);
  } catch {
    return '取值范围必须是 JSON 对象';
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return '取值范围必须是 JSON 对象';
  }
  switch (valueType) {
    case 'BOOLEAN': {
      return '布尔参数没有取值范围（取值只能是 true / false）';
    }
    case 'ENUM': {
      if (
        !Array.isArray(parsed.options) ||
        parsed.options.length === 0 ||
        parsed.options.some(
          (item: unknown) => typeof item !== 'string' || !item,
        )
      ) {
        return '枚举参数的取值范围必须是非空 options 字符串数组';
      }
      return null;
    }
    case 'JSON': {
      return 'JSON 参数没有取值范围';
    }
    case 'NUMBER': {
      // 与后端同序：先看是否给了端点（存在即算），再看给的是不是数字
      const minPresent = parsed.min !== undefined && parsed.min !== null;
      const maxPresent = parsed.max !== undefined && parsed.max !== null;
      if (!minPresent && !maxPresent) {
        return '数值参数的取值范围必须包含 min 或 max 至少一端';
      }
      const minNumeric = typeof parsed.min === 'number';
      const maxNumeric = typeof parsed.max === 'number';
      if ((minPresent && !minNumeric) || (maxPresent && !maxNumeric)) {
        return '数值参数的取值范围 min / max 必须是数字';
      }
      return null;
    }
    case 'TEXT': {
      if (
        typeof parsed.maxLength !== 'number' ||
        parsed.maxLength < 1 ||
        parsed.maxLength > 512
      ) {
        return '文本参数的取值范围必须是 1–512 的 maxLength';
      }
      return null;
    }
    default: {
      return `不支持的值类型：${String(valueType)}`;
    }
  }
}

/** 值类型的范围编辑提示（表单 placeholder）。 */
export function rangePlaceholder(valueType: ParamValueType | string): string {
  switch (valueType) {
    case 'ENUM': {
      return '{"options":["DAILY","WEEKLY"]}';
    }
    case 'NUMBER': {
      return '{"min":5,"max":50}';
    }
    case 'TEXT': {
      return '{"maxLength":255}';
    }
    default: {
      return '该类型无需取值范围';
    }
  }
}

/** 值输入的提示（按类型与范围）。 */
export function valuePlaceholder(
  valueType: ParamValueType | string,
  range: null | string | undefined,
): string {
  switch (valueType) {
    case 'BOOLEAN': {
      return 'true 或 false';
    }
    case 'ENUM': {
      return range
        ? `选项：${JSON.parse(range).options?.join(' / ') ?? ''}`
        : '选项值';
    }
    case 'JSON': {
      return '{"page":20} 这样的合法 JSON';
    }
    case 'NUMBER': {
      return range ? `数字，范围 ${JSON.parse(range)}` : '数字';
    }
    default: {
      return '文本';
    }
  }
}

export function valueTypeLabel(type: ParamValueType | string): string {
  return paramValueTypeText(type);
}
