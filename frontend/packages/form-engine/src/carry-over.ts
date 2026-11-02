/**
 * E11-T07 / A-FU-015、C-FU-016：带入上次值（一键带入上一次随访的值再修改）。
 *
 * - 适用字段由模板标记（`FormSchemaField.carryOver`，A 的用药等设置项；**测量值不带入**
 *   ——C 的程控设置项（起搏模式、上下限频率、AV 间期）标记、测量字段不标记即可）。
 * - 只带入：模板标记 && storage=json && 非派生 && 非敏感 && 当前显示（条件隐藏的字段不带入）
 *   && 上次记录该字段有值。带入为**覆盖写入**（带入后再修改），数组 / 对象值深拷贝。
 * - 带入的值以浅色标出（C-FU-016）：渲染器维护 carried 键集合，用户改动该字段后恢复普通样式。
 */

import type { RendererField } from './layout';

import { isEmptyFormValue, isFieldVisible } from '@crdp/rules';

/** 上一次记录（FormRenderer previous prop；规则参数决定取法，后端给）。 */
export interface PreviousRecord {
  date: string;
  recordId: string;
  values: Record<string, unknown>;
}

/** 一条可带入项。 */
export interface CarryOverItem {
  key: string;
  label: string;
  value: unknown;
}

function isCarryOverEligible(
  field: RendererField,
  data: Record<string, unknown>,
  context: null | Record<string, unknown> | undefined,
  previous: null | PreviousRecord,
): boolean {
  if (!previous) return false;
  if (field.carryOver !== true) return false; // 模板未标记：测量值等不带入
  if (field.storage !== 'json') return false; // 实体 / 加密列的值不经表单 JSON
  if (field.type === 'derived') return false; // 派生字段只读、由系统计算
  if (field.sensitive) return false; // 敏感值不下发，无值可带入
  if (!isFieldVisible(field, data, context)) return false; // 条件隐藏不带入
  return !isEmptyFormValue(previous.values[field.key]); // 上次没填不带入
}

/**
 * 带入计划：全部可带入字段（带入顺序 = 模板字段顺序）。空计划 = 不显示「带入上次值」按钮。
 */
export function carryOverPlan(
  fields: RendererField[],
  data: null | Record<string, unknown> | undefined,
  context: null | Record<string, unknown> | undefined,
  previous: null | PreviousRecord | undefined,
): CarryOverItem[] {
  if (!previous) return [];
  const values = data ?? {};
  return fields
    .filter((field) => isCarryOverEligible(field, values, context, previous))
    .map((field) => ({
      key: field.key,
      label: field.label,
      value: cloneValue(previous.values[field.key]),
    }));
}

/** 是否可带入（按钮是否渲染）。 */
export function canCarryOver(
  fields: RendererField[],
  data: null | Record<string, unknown> | undefined,
  context: null | Record<string, unknown> | undefined,
  previous: null | PreviousRecord | undefined,
): boolean {
  return carryOverPlan(fields, data, context, previous).length > 0;
}

/** 执行带入：覆盖写入（新对象，不改入参）。 */
export function applyCarryOver(
  data: null | Record<string, unknown> | undefined,
  plan: CarryOverItem[],
): Record<string, unknown> {
  const next: Record<string, unknown> = { ...data };
  for (const item of plan) {
    next[item.key] = cloneValue(item.value);
  }
  return next;
}

/** 按钮文案（带上次随访日期，便于确认带入的是哪一次）。 */
export function carryOverButtonText(previous: PreviousRecord): string {
  return `带入上次值（${previous.date}）`;
}

/** 数组 / 普通对象深拷贝一份（带入值与上次记录不共享引用）。 */
function cloneValue(value: unknown): unknown {
  if (Array.isArray(value)) return [...value];
  if (typeof value === 'object' && value !== null) return { ...value };
  return value;
}
