/**
 * E11-T07：值的显示格式（上次值提示「上次 xx」、只读视图、带入标记）。
 *
 * 显示名与编码分离（A-FLD-002）：数据库存编码（选项编码整数 / true/false / 数值 / 日期字符串），
 * 页面显示显示名——这里统一把值格式化成中文展示串；未知编码回落编码本身（停用选项的历史值）。
 */

import type { FormSchemaField } from '@crdp/api';

import { isEmptyFormValue } from '@crdp/rules';

/** 选项显示名（未知编码回落编码本身，与 @crdp/api schemaOptionLabel 同口径；字典来源编码为字符串，OQ-69④）。 */
function optionLabel(
  field: Pick<FormSchemaField, 'optionSet'>,
  code: number | string,
): string {
  const option = (field.optionSet ?? []).find((item) => item.code === code);
  return option ? option.label : String(code);
}

/**
 * 值 → 展示串（空值为空串）：单选 / 下拉 / 多选显显示名；是否显 是 / 否；数值带单位；
 * 数值 ×2 显「长 × 宽」（只显已填分量）；日期 / 日期时间 / 文本按原值。
 */
export function formatFieldValue(
  field: Pick<FormSchemaField, 'optionSet' | 'type' | 'unit'>,
  value: unknown,
): string {
  if (isEmptyFormValue(value)) return '';
  switch (field.type) {
    case 'boolean': {
      if (value === true) return '是';
      if (value === false) return '否';
      return String(value);
    }
    case 'multiselect': {
      const codes = Array.isArray(value) ? value : [];
      return codes
        .map((code) => optionLabel(field, code as number | string))
        .join('、');
    }
    case 'number': {
      return joinUnit(String(value), field.unit);
    }
    case 'pair_number': {
      const pair = (value ?? {}) as Record<string, unknown>;
      const parts = ['length', 'width']
        .map((name) => pair[name])
        .filter((item) => item !== null && item !== undefined)
        .map(String);
      return parts.join(' × ');
    }
    case 'radio':
    case 'select': {
      return optionLabel(field, value as number | string);
    }
    default: {
      return String(value);
    }
  }
}

/** 字段下方的上次值提示：「上次 xx」；无值为空串（不渲染提示）。 */
export function previousValueText(
  field: Pick<FormSchemaField, 'optionSet' | 'type' | 'unit'>,
  value: unknown,
): string {
  const formatted = formatFieldValue(field, value);
  return formatted ? `上次 ${formatted}` : '';
}

/** 只读视图值：空值显示「未填写」（次要色），其余同 formatFieldValue。 */
export function readonlyFieldValue(
  field: Pick<FormSchemaField, 'optionSet' | 'type' | 'unit'>,
  value: unknown,
): string {
  const formatted = formatFieldValue(field, value);
  return formatted || '未填写';
}

function joinUnit(text: string, unit?: null | string): string {
  return unit ? `${text} ${unit}` : text;
}
