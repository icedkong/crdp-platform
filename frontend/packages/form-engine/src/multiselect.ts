/**
 * E11-T07 / C-FLD-004（Q04）：多选字段语义——互斥（「无」）与补充说明（「其他」）。
 *
 * - 值编码：多选存选项编码数组（tech-plan §6）；渲染与变更都按编码数组处理。
 * - 互斥（exclusive，如「无」）：勾选互斥项后其他选项自动取消且不可再勾选；已选互斥项时
 *   勾选其他选项会先取消互斥项（互斥双向）。
 * - 补充说明（needsDetail，如「其他」）：选中后出短文本；说明写入该字段备注（data_meta.note，
 *   Q37「编码 + 文字」的同一口径）；勾选「其他」未填说明时不能保存（missingDetailIssues）。
 */

import type { FormSchemaField } from '@crdp/api';
import type { FieldIssue } from '@crdp/rules';

import type { RendererField } from './layout';

import { isFieldVisible } from '@crdp/rules';

/** 字段备注（data_meta[fieldKey]，说明 / 手工标记 / 报告单带入的子集）。 */
export interface FieldMetaValue {
  fromReport?: string;
  manual?: boolean;
  note?: string;
}

/** 值归一：非数组 / 脏数据防御为空数组，元素转数字。 */
export function normalizeMultiselectValue(value: unknown): number[] {
  if (!Array.isArray(value)) return [];
  return value
    .map((item) => (typeof item === 'number' ? item : Number(item)))
    .filter((item) => Number.isInteger(item));
}

/** 互斥选项编码（「无」）。 */
export function exclusiveCodes(
  field: Pick<FormSchemaField, 'optionSet'>,
): number[] {
  return (field.optionSet ?? [])
    .filter((option) => option.exclusive)
    .map((option) => option.code);
}

/** 需补充说明的选项编码（「其他」）。 */
export function needsDetailCodes(
  field: Pick<FormSchemaField, 'optionSet'>,
): number[] {
  return (field.optionSet ?? [])
    .filter((option) => option.needsDetail)
    .map((option) => option.code);
}

/**
 * 复选组变更后的新值：与前一值 diff 出本次勾选的编码，然后收敛互斥——
 * 勾了互斥项 → 只保留互斥项；勾了普通项 → 移除互斥项；纯取消不影响其余选项。
 * 脏数据（互斥与普通共存）在下一次变更时一并收敛。
 */
export function applyMultiselectChange(
  field: Pick<FormSchemaField, 'optionSet'>,
  previousValue: unknown,
  nextCodes: unknown,
): number[] {
  const previous = normalizeMultiselectValue(previousValue);
  const next = normalizeMultiselectValue(nextCodes);
  const exclusive = new Set(exclusiveCodes(field));
  if (exclusive.size === 0) return next;

  const added = next.filter((code) => !previous.includes(code));
  const addedExclusive = added.find((code) => exclusive.has(code));
  if (addedExclusive !== undefined) {
    // 勾选互斥项（「无」）：其他选项自动取消
    return next.filter((code) => exclusive.has(code));
  }
  // 勾选普通项（互斥项应让位）或纯取消：移除互斥项，保留其余
  return next.filter((code) => !exclusive.has(code));
}

/**
 * 选项是否不可勾选：停用选项不可新选（历史值仍显示，form.md §1）；互斥项选中时
 * 其他选项不可勾选（「症状勾选『无』后，其他选项自动取消且不可勾选」）。
 */
export function isOptionLocked(
  field: Pick<FormSchemaField, 'optionSet'>,
  value: unknown,
  code: number,
): boolean {
  const option = (field.optionSet ?? []).find((item) => item.code === code);
  if (!option || option.disabled) return true;
  const exclusive = new Set(exclusiveCodes(field));
  const selected = normalizeMultiselectValue(value);
  const exclusiveSelected = selected.some((item) => exclusive.has(item));
  return exclusiveSelected && !exclusive.has(code);
}

/** 已选中的需补充说明编码。 */
export function selectedNeedsDetailCodes(
  field: Pick<FormSchemaField, 'optionSet'>,
  value: unknown,
): number[] {
  const required = new Set(needsDetailCodes(field));
  if (required.size === 0) return [];
  return normalizeMultiselectValue(value).filter((code) => required.has(code));
}

/**
 * 「选『其他』须填写说明」缺失清单（勾选未填说明时不能保存）：显示中的多选字段、选中了
 * 需说明选项、而该字段备注（data_meta.note）为空。说明按字段存一份（一个字段一般只有一个
 * 「其他」；多个需说明选项共享同一备注，Q37 字段备注口径）。
 */
export function missingDetailIssues(
  fields: RendererField[],
  data: null | Record<string, unknown> | undefined,
  meta?: null | Record<string, FieldMetaValue | undefined> | undefined,
  context?: null | Record<string, unknown>,
): FieldIssue[] {
  const values = data ?? {};
  const notes = meta ?? {};
  const issues: FieldIssue[] = [];
  for (const field of fields) {
    if (field.type !== 'multiselect') continue;
    if (!isFieldVisible(field, values, context)) continue;
    if (selectedNeedsDetailCodes(field, values[field.key]).length === 0)
      continue;
    if ((notes[field.key]?.note ?? '').trim().length > 0) continue;
    issues.push({
      key: field.key,
      categoryKey: field.categoryKey,
      rule: 'needsDetail',
      message: '选“其他”须填写说明',
    });
  }
  return issues;
}
