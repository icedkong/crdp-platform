/**
 * E11-T01 / A-FLD-001、A-FLD-002、C-FLD-001：字段字典（docs/api/form.md §3）。
 *
 * 显示名与编码分离（A-FLD-002）：`label` 是页面显示的显示名，`code` 是字段编码（导入导出与
 * 库名，创建后不可改），`key` 是稳定键（JSON 数据键）；选项同理——数据库存 `code`（0=否 /
 * 1=是），页面显示 `label`。字段可启停（ENABLED/DISABLED，停用不删除）。
 */

import type { ConditionTree, CrossFieldRule } from '@crdp/rules';

/** 表单摘要（GET /api/hospital/form/forms；完整模板见 FormSchema，E11-T02）。 */
export interface FormSummary {
  code: string;
  currentVersion: number;
  name: string;
  status: FieldDictStatus;
  /** BASELINE 基线 / FOLLOWUP 随访 / IMPLANT 植入 / REPORT 报告单字段集 */
  usage: FormUsage;
}

export type FieldDictStatus = 'DISABLED' | 'ENABLED';

export type FormUsage = 'BASELINE' | 'FOLLOWUP' | 'IMPLANT' | 'REPORT';

/** 字段类型（components.md §10.1 FormField['type'] 同口径）。 */
export type FormFieldType =
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'derived'
  | 'multiselect'
  | 'number'
  | 'pair_number'
  | 'radio'
  | 'select'
  | 'text'
  | 'textarea';

/** 存储方式（tech-plan §6 混合存储）。 */
export type FieldStorage = 'column' | 'encrypted' | 'json';

/** 数值范围（软提示 / 硬拦截同构；空端不限制）。 */
export interface FieldRange {
  max?: null | number;
  min?: null | number;
}

/** 选项：code 是数据库存的编码，label 是页面显示名（A-FLD-002）。 */
export interface FieldOption {
  code: number;
  exclusive?: boolean;
  label: string;
  needsDetail?: boolean;
  ordinal?: null | number;
  sortNo: number;
  status: FieldDictStatus;
}

/** 派生定义（E11-T05 内置函数库；派生字段只读）。 */
export interface FieldDerivedDef {
  args: string[];
  fn: string;
}

/** 字段视图（GET /api/hospital/form/fields 的行）。 */
export interface FormFieldDef {
  categoryKey: string;
  categoryLabel: string;
  code: string;
  decimals?: null | number;
  derivedDef?: FieldDerivedDef | null;
  formCode: string;
  formName: string;
  hardRange?: FieldRange | null;
  help?: null | string;
  key: string;
  label: string;
  options?: FieldOption[] | null;
  required: boolean;
  /** 条件必填（E11-T03；与 FormSchemaField.requiredWhen 同构的条件树）。 */
  requiredWhen?: ConditionTree | null;
  sensitive: boolean;
  softRange?: FieldRange | null;
  status: FieldDictStatus;
  storage: FieldStorage;
  type: FormFieldType;
  unit?: null | string;
  updatedAt?: null | string;
  /** 条件显示条件树（E11-T03，字典维护页只读展示；求值与 FormSchemaField 同一套）。 */
  visibleWhen?: ConditionTree | null;
}

/** 选项维护：code 已存在为改（显示名 / 排序 / 停用），不存在为新增；编码不可改、不删除。 */
export interface FieldOptionUpdate {
  code: number;
  label: string;
  sortNo?: number;
  status?: FieldDictStatus;
}

/** 字段维护（PUT /fields/{key}?formCode=；键、编码、类型、硬范围、敏感标记不可改也不上送）。 */
export interface FormFieldUpdateRequest {
  helpText?: null | string;
  label: string;
  options?: FieldOptionUpdate[];
  required: boolean;
  softRange?: FieldRange | null;
  status: FieldDictStatus;
  unit?: null | string;
}

export const FIELD_DICT_STATUS_TEXT: Record<FieldDictStatus, string> = {
  DISABLED: '已停用',
  ENABLED: '启用',
};

export function fieldDictStatusText(status: null | string | undefined): string {
  return (
    FIELD_DICT_STATUS_TEXT[status as FieldDictStatus] ?? String(status ?? '—')
  );
}

export const FORM_FIELD_TYPE_TEXT: Record<FormFieldType, string> = {
  boolean: '是否',
  date: '日期',
  datetime: '日期时间',
  derived: '派生（自动计算）',
  multiselect: '多选',
  number: '数值',
  pair_number: '数值 × 2（长 × 宽）',
  radio: '单选',
  select: '下拉单选',
  text: '单行文本',
  textarea: '多行文本',
};

export function formFieldTypeText(type: null | string | undefined): string {
  return FORM_FIELD_TYPE_TEXT[type as FormFieldType] ?? String(type ?? '—');
}

export const FIELD_STORAGE_TEXT: Record<FieldStorage, string> = {
  column: '实体列（计划 / 统计绑定）',
  encrypted: '加密列（敏感，不进 JSON）',
  json: '记录 JSON',
};

export function fieldStorageText(storage: null | string | undefined): string {
  return FIELD_STORAGE_TEXT[storage as FieldStorage] ?? String(storage ?? '—');
}

export const FORM_USAGE_TEXT: Record<FormUsage, string> = {
  BASELINE: '基线',
  FOLLOWUP: '随访',
  IMPLANT: '植入',
  REPORT: '报告单字段集',
};

export function formUsageText(usage: null | string | undefined): string {
  return FORM_USAGE_TEXT[usage as FormUsage] ?? String(usage ?? '—');
}

/** 选项型字段（下拉 / 单选 / 多选）才有选项可维护。 */
export function isOptionFieldType(type: FormFieldType | string): boolean {
  return type === 'select' || type === 'radio' || type === 'multiselect';
}

/** 范围展示：「130–210」；只有一端只显一端；空返回空串。 */
export function fieldRangeText(range?: FieldRange | null): string {
  if (!range) return '';
  const { max, min } = range;
  const hasMin = min !== null && min !== undefined;
  const hasMax = max !== null && max !== undefined;
  if (!hasMin && !hasMax) return '';
  if (hasMin && hasMax) return `${min}–${max}`;
  return hasMin ? `≥ ${min}` : `≤ ${max}`;
}

/** 范围校验：两端都有时 min ≤ max。 */
export function isValidFieldRange(range?: FieldRange | null): boolean {
  if (!range) return true;
  const { max, min } = range;
  const hasMin = min !== null && min !== undefined;
  const hasMax = max !== null && max !== undefined;
  if (hasMin && hasMax && (min as number) > (max as number)) {
    return false;
  }
  return true;
}

/** 搜索：命中显示名、字段编码或稳定键（A 规格「按分类列出字段，可搜索」）。 */
export function matchFieldKeyword(
  field: Pick<FormFieldDef, 'code' | 'key' | 'label'>,
  keyword: string,
): boolean {
  const query = keyword.trim().toLowerCase();
  if (!query) return true;
  return (
    field.label.toLowerCase().includes(query) ||
    field.code.toLowerCase().includes(query) ||
    field.key.toLowerCase().includes(query)
  );
}

/* ----------------------------------------------------------------------
 * E11-T02 / A-NFR-002、C-NFR-002：FormSchema（GET /api/hospital/form/forms/{code}?version=，
 * components.md §10.1 契约 + name / usage）。不传 version 取当前发布版；已有记录按其保存时的
 * 版本取（旧版本快照可读）。rules 为 fe_rule 跨字段校验（E11-T04 已落地：实时读取、不进版本快照），
 * 前后端同语义校验见 @crdp/rules 的 validateFormData（共享用例锁定一致）。
 */

export interface FormSchema {
  categories: FormSchemaCategory[];
  code: string;
  name: string;
  /** fe_rule 跨字段校验（E11-T04）：severity=HARD 违反拦截（后端提交 42200），SOFT 黄色提示可保存。 */
  rules: CrossFieldRule[];
  usage: FormUsage;
  /** 模板版本：记录保存时固化的 formVersion 用它取旧版本渲染。 */
  version: number;
}

export interface FormSchemaCategory {
  fields: FormSchemaField[];
  /** 稳定锚点键（报告单位置、分类条依赖，模板改版不变）。 */
  key: string;
  label: string;
  order: number;
}

export interface FormSchemaField {
  /** 字段编码（导入导出名）。 */
  code: string;
  decimals?: null | number;
  derived?: FieldDerivedDef | null;
  hardRange?: FieldRange | null;
  help?: null | string;
  /** 稳定 ASCII 键（如 f019），JSON 数据键。 */
  key: string;
  label: string;
  /** 选项（单选 / 下拉 / 多选）：编码 + 显示名 + 停用标记（A-FLD-002）。 */
  optionSet?: FormSchemaOption[] | null;
  required: boolean;
  /** 条件必填（E11-T03；有效必填 = 显示 &&（required || requiredWhen 成立），@crdp/rules）。 */
  requiredWhen?: ConditionTree | null;
  sensitive: boolean;
  softRange?: FieldRange | null;
  storage: FieldStorage;
  type: FormFieldType;
  unit?: null | string;
  /** 条件显示条件树（E11-T03，前后端一致求值：@crdp/rules evaluateCondition / 后端 FormConditionEngine）。 */
  visibleWhen?: ConditionTree | null;
}

export interface FormSchemaOption {
  code: number;
  /** 停用选项：不可新选，历史值仍按编码显示（不删除即不失效）。 */
  disabled?: boolean;
  exclusive?: boolean;
  label: string;
  needsDetail?: boolean;
  ordinal?: null | number;
}

/** 发布结果（POST /api/hospital/form/forms/{code}/publish，followup:field:edit）。 */
export interface FormPublishResult {
  code: string;
  /** 本次固化的启用字段数（停用字段不进新版本）。 */
  fieldCount: number;
  name: string;
  publishedAt: string;
  version: number;
}

/** 全表单字段索引：稳定键 → 字段（渲染器按记录 data 的键取定义）。 */
export function indexSchemaFields(
  schema: Pick<FormSchema, 'categories'>,
): Map<string, FormSchemaField> {
  const index = new Map<string, FormSchemaField>();
  for (const category of schema.categories) {
    for (const field of category.fields) {
      index.set(field.key, field);
    }
  }
  return index;
}

/** 选项显示名：按编码取显示名（数据库存编码、页面显示显示名，A-FLD-002）；未知编码回落编码本身。 */
export function schemaOptionLabel(
  field: Pick<FormSchemaField, 'optionSet'>,
  code: null | number | undefined,
): string {
  if (code === null || code === undefined) return '—';
  const option = field.optionSet?.find((item) => item.code === code);
  return option ? option.label : String(code);
}
