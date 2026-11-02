/**
 * E11-T01 / A-FLD-001、A-FLD-002、C-FLD-001：字段字典（docs/api/form.md §3）。
 *
 * 显示名与编码分离（A-FLD-002）：`label` 是页面显示的显示名，`code` 是字段编码（导入导出与
 * 库名，创建后不可改），`key` 是稳定键（JSON 数据键）；选项同理——数据库存 `code`（0=否 /
 * 1=是），页面显示 `label`。字段可启停（ENABLED/DISABLED，停用不删除）。
 */

/** 表单摘要（GET /api/hospital/form/forms；完整 FormSchema 随 E11-T02）。 */
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
  requiredWhen?: unknown;
  sensitive: boolean;
  softRange?: FieldRange | null;
  status: FieldDictStatus;
  storage: FieldStorage;
  type: FormFieldType;
  unit?: null | string;
  updatedAt?: null | string;
  visibleWhen?: unknown;
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
