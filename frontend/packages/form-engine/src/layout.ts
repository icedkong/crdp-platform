/**
 * E11-T07 / A-FLD-007、C-FLD-010：分类布局推导。
 *
 * - 分类（基线「分类」/ 随访「分组」统一口径，components.md §10.1）按模板 `order` 升序渲染；
 *   `order` 相同保持输入顺序（稳定排序）。
 * - 组内字段保持模板数组顺序——**顺序只来自模板**（C-FLD-010「顺序与纸质表一致，可由租户
 *   管理员调整」），渲染器不按 key / label 自行排序。
 * - 每个分类输出稳定锚点 `crdp-anchor-<key>`（components.md §10.3 锚点；报告单位置与分类条依赖）。
 * - 字段平铺后携带 `categoryKey`（校验命中挂靠 fieldErrors[].categoryKey 同源）。
 */

import type { FormSchema, FormSchemaField } from '@crdp/api';

/** 参与渲染 / 校验的字段（FormSchemaField + 所属分类锚点键）。 */
export interface RendererField extends FormSchemaField {
  categoryKey: string;
}

/** 布局后的分类（字段已带 categoryKey）。 */
export interface RendererCategory {
  fields: RendererField[];
  key: string;
  label: string;
  order: number;
}

/** 稳定锚点键（components.md §10.3：`crdp-anchor-<key>`，模板改版不变）。 */
export function anchorId(key: string): string {
  return `crdp-anchor-${key}`;
}

/**
 * 分类 / 分节锚点（E11-T11 / A-PAT-021、C-PAT-018）：每个分类输出稳定锚点键与
 * 所属模板版本——报告单位置（A-ATT-002：锚点、锚点所属模板版本；E16-T13 按版本校验）
 * 与分类条点击定位（E13-T11）都用它；改显示名不影响跳转与报告单位置（A-PAT-021）。
 */
export interface CategoryAnchor {
  /** 模板稳定锚点键（`crdp-anchor-<key>` 的 key 部分）。 */
  key: string;
  label: string;
  order: number;
  /** 锚点所属模板版本（FormSchema.version；报告单位置校验锚点须在当前模板版本内）。 */
  templateVersion: number;
}

/** 全部分类锚点（按模板 order 升序）；模板版本取 schema.version，整表单一致。 */
export function categoryAnchors(
  schema: Pick<FormSchema, 'categories' | 'version'>,
): CategoryAnchor[] {
  return layoutCategories(schema).map(({ key, label, order }) => ({
    key,
    label,
    order,
    templateVersion: schema.version,
  }));
}

/**
 * 分类布局：按 order 升序（稳定），字段按模板顺序平铺挂上 categoryKey。
 * 分类 key 或字段缺失时防御跳过（坏模板不炸渲染）。
 */
export function layoutCategories(
  schema: Pick<FormSchema, 'categories'>,
): RendererCategory[] {
  const sorted = (schema.categories ?? []).toSorted(
    (a, b) => a.order - b.order,
  );
  const laid: RendererCategory[] = [];
  for (const category of sorted) {
    if (!category?.key) continue;
    laid.push({
      key: category.key,
      label: category.label ?? category.key,
      order: category.order ?? 0,
      fields: (category.fields ?? [])
        .filter((field) => Boolean(field?.key))
        .map((field) => ({ ...field, categoryKey: category.key })),
    });
  }
  return laid;
}

/** 全部字段平铺（校验 / 带入上次值 / 条件求值都按全表字段算）。 */
export function flattenRendererFields(
  categories: RendererCategory[],
): RendererField[] {
  return categories.flatMap((category) => category.fields);
}

/** 控件形态（components.md §10.3）：决定渲染哪种输入。 */
export type FieldControlKind =
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'derived'
  | 'multiselect'
  | 'number'
  | 'pair-number'
  | 'radio'
  | 'select'
  | 'sensitive'
  | 'text'
  | 'textarea';

/**
 * 控件形态推导：
 * - 敏感字段（storage=encrypted，值不经表单 JSON）渲染脱敏只读（「敏感 · 默认脱敏」chip）；
 * - 派生字段只读（「自动计算 · 只读」chip；来源缺失时放开手工录入，E11-T05 / A-FLD-005）；
 * - 单选（radio / select / boolean）：启用选项 ≤ 4 用单选按钮组，否则下拉（§10.3 选项）；
 * - 多选用复选组（互斥 / 补充说明见 multiselect.ts）。
 */
export function controlKind(
  field: Pick<FormSchemaField, 'optionSet' | 'sensitive' | 'storage' | 'type'>,
): FieldControlKind {
  if (field.sensitive || field.storage === 'encrypted') return 'sensitive';
  switch (field.type) {
    case 'boolean':
    case 'radio':
    case 'select': {
      return enabledOptionCount(field) <= 4 ? 'radio' : 'select';
    }
    case 'date': {
      return 'date';
    }
    case 'datetime': {
      return 'datetime';
    }
    case 'derived': {
      return 'derived';
    }
    case 'multiselect': {
      return 'multiselect';
    }
    case 'number': {
      return 'number';
    }
    case 'pair_number': {
      return 'pair-number';
    }
    case 'textarea': {
      return 'textarea';
    }
    default: {
      return 'text';
    }
  }
}

/** 单选控件的可选项（boolean 无选项集时按 0=否 / 1=是 兜底，编码映射 false / true）。 */
export interface ChoiceOption {
  disabled?: boolean;
  exclusive?: boolean;
  label: string;
  needsDetail?: boolean;
  value: boolean | number;
}

function enabledOptionCount(
  field: Pick<FormSchemaField, 'optionSet' | 'type'>,
): number {
  if (field.type === 'boolean') return 2; // 否 / 是（兜底）
  return (field.optionSet ?? []).filter((option) => !option.disabled).length;
}

/** 单选（radio / select / boolean）选项列表：boolean 的 0/1 编码映射为 false / true 存储值。 */
export function choiceOptions(
  field: Pick<FormSchemaField, 'optionSet' | 'type'>,
): ChoiceOption[] {
  if (field.type === 'boolean') {
    const byCode = new Map(
      (field.optionSet ?? []).map((option) => [option.code, option]),
    );
    const no = byCode.get(0);
    const yes = byCode.get(1);
    return [
      { label: no?.label ?? '否', value: false },
      { label: yes?.label ?? '是', value: true },
    ];
  }
  return (field.optionSet ?? []).map((option) => ({
    label: option.label,
    value: option.code,
    disabled: option.disabled || undefined,
    exclusive: option.exclusive || undefined,
    needsDetail: option.needsDetail || undefined,
  }));
}
