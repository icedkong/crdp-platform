import type {
  FieldOption,
  FieldRange,
  FormFieldDef,
  FormFieldUpdateRequest,
} from '@crdp/api';

import { isOptionFieldType, isValidFieldRange } from '@crdp/api';

/**
 * E11-T01 / A-FLD-001、A-FLD-002 · 字段字典页逻辑（纯函数）。
 * 显示名与编码分离：编辑模型只含可维护列（显示名 / 帮助 / 单位 / 软范围 / 必填 / 状态 / 选项
 * 显示名与排序），稳定键、字段编码、类型、硬范围、敏感标记、存储方式不可改也不上送。
 * 校验与后端 FieldDictionaryService 同口径（40001 的前端第一道拦截）。
 */

export interface OptionEditModel {
  code: null | number;
  label: string;
  sortNo: number;
  status: 'DISABLED' | 'ENABLED';
}

export interface FieldEditModel {
  helpText: string;
  label: string;
  options: OptionEditModel[];
  required: boolean;
  softRangeMax: null | number;
  softRangeMin: null | number;
  status: 'DISABLED' | 'ENABLED';
  unit: string;
}

export function toFieldEditModel(field: FormFieldDef): FieldEditModel {
  return {
    helpText: field.help ?? '',
    label: field.label,
    options: (field.options ?? []).map((option) => toOptionModel(option)),
    required: field.required,
    softRangeMax: field.softRange?.max ?? null,
    softRangeMin: field.softRange?.min ?? null,
    status: field.status,
    unit: field.unit ?? '',
  };
}

function toOptionModel(option: FieldOption): OptionEditModel {
  return {
    code: option.code,
    label: option.label,
    sortNo: option.sortNo,
    status: option.status,
  };
}

/** 组装 PUT 请求：编辑模型是全集直接全送；编码没填的新增行由 validateFieldEditModel 先拦下，这里兜底丢弃。 */
export function buildFieldUpdate(
  model: FieldEditModel,
  optionField: boolean,
): FormFieldUpdateRequest {
  return {
    helpText: model.helpText.trim() || null,
    label: model.label.trim(),
    options: optionField
      ? model.options
          .filter((option) => option.code !== null)
          .map((option) => ({
            code: option.code ?? 0,
            label: option.label.trim(),
            sortNo: option.sortNo,
            status: option.status,
          }))
      : undefined,
    required: model.required,
    softRange: softRangeOf(model),
    status: model.status,
    unit: model.unit.trim() || null,
  };
}

function softRangeOf(model: FieldEditModel): FieldRange | null {
  if (model.softRangeMin === null && model.softRangeMax === null) {
    return null;
  }
  return { max: model.softRangeMax, min: model.softRangeMin };
}

/** 编辑校验：显示名必填 ≤128、帮助 ≤500、单位 ≤32、软范围 min≤max、选项编码 / 显示名 / 编码不重复。 */
export function validateFieldEditModel(
  model: FieldEditModel,
  optionField: boolean,
): string[] {
  const errors: string[] = [];
  if (!model.label.trim()) {
    errors.push('显示名不能为空');
  }
  if (model.label.trim().length > 128) {
    errors.push('显示名不能超过 128 字');
  }
  if (model.helpText.length > 500) {
    errors.push('帮助说明不能超过 500 字');
  }
  if (model.unit.length > 32) {
    errors.push('单位不能超过 32 字');
  }
  if (!isValidFieldRange(softRangeOf(model))) {
    errors.push('提示范围的最小值不能大于最大值');
  }
  if (optionField) {
    const codes = new Set<number>();
    for (const option of model.options) {
      if (option.code === null || Number.isNaN(option.code)) {
        errors.push('新增选项必须填选项编码');
        break;
      }
      if (codes.has(option.code)) {
        errors.push(`选项编码重复：${option.code}`);
        break;
      }
      codes.add(option.code);
    }
    if (model.options.some((option) => !option.label.trim())) {
      errors.push('选项显示名不能为空');
    }
  }
  return errors;
}

/** 行内启停的请求体（保持其余可维护列不变，只翻状态）。 */
export function buildStatusToggleUpdate(
  field: FormFieldDef,
  status: 'DISABLED' | 'ENABLED',
): FormFieldUpdateRequest {
  return {
    helpText: field.help ?? null,
    label: field.label,
    options: field.options?.map((option) => ({
      code: option.code,
      label: option.label,
      sortNo: option.sortNo,
      status: option.status,
    })),
    required: field.required,
    softRange: field.softRange ?? null,
    status,
    unit: field.unit ?? null,
  };
}

/** 选项型字段才展示选项维护区（是否类按 0=否 / 1=是 兜底，无选项集）。 */
export function isOptionField(field: FormFieldDef): boolean {
  return isOptionFieldType(field.type) && (field.options?.length ?? 0) > 0;
}

/** 状态切换按钮文案（Popconfirm 标题用）。 */
export function statusActionText(status: 'DISABLED' | 'ENABLED'): string {
  return status === 'ENABLED' ? '停用' : '启用';
}
