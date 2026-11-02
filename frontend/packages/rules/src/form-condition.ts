/**
 * E11-T03 / A-FLD-004、C-FLD-005：条件 DSL 求值——条件显示与条件必填（tech-plan §6「条件显示与条件
 * 必填」，与高级检索同构的条件树），与后端 `com.crdp.form.rule.app.FormConditionEngine` 同语义
 * （验收「前后端同一套用例结果一致」）。
 *
 * 共享用例 `backend/crdp-form/src/test/resources/shared-cases/form-condition-cases.json`
 * 两端各执行一遍（components.md §10.5），visible / missingRequired 逐条一致。
 *
 * - 条件树：节点 `{all: [...]}`（全部成立，空数组恒真）/ `{any: [...]}`（任一成立，空数组恒假），
 *   可嵌套；叶子 `{field | ctx, op, value}`——field 取本表单字段值（稳定键），ctx 取上下文变量
 *   （如医院 C 的 `device.type`，按点路径在嵌套对象中取值）。
 * - 操作符：`eq | ne | in | nin | gt | gte | lt | lte | empty | notEmpty`。比较按 tech-plan §6 值编码
 *   口径：数值与数值、布尔与布尔、字符串与字符串严格同型（1 与 "1" 不等）；gt / gte / lt / lte 只比
 *   数值与日期 / 日期时间（日期归一当日 00:00，与跨字段 COMPARE 同口径，复用 compareValues），
 *   普通文本不可比 → 不成立。
 * - 操作数缺失（未答 / null / undefined）：除 `empty` / `notEmpty` 外一律不成立——「是否死亡」未答 →
 *   死亡日期不显示、不加必填（未答 ≠ 否）。
 * - 空值口径：null / undefined / 空串 / 空数组 / 分量全缺的 pair_number 对象为空；`0` 与 `false`
 *   是已答，不算空。
 * - 防御：条件为空 / null / 非法结构 / 残缺叶子恒真——配置错误不隐藏字段、不加必填（OQ-65）。
 */

import { compareValues } from './form-validation';

/** 条件操作符（components.md §10.1 ConditionOp）。 */
export type ConditionOp =
  | 'empty'
  | 'eq'
  | 'gt'
  | 'gte'
  | 'in'
  | 'lt'
  | 'lte'
  | 'ne'
  | 'nin'
  | 'notEmpty';

/** 本表单字段叶子：field = 字段稳定键。 */
export interface FieldCondition {
  field: string;
  op: ConditionOp;
  value?: unknown;
}

/** 上下文变量叶子：ctx = 点路径（如 'device.type'），来源 C 器械上下文（E21-T03）等业务侧。 */
export interface CtxCondition {
  ctx: string;
  op: ConditionOp;
  value?: unknown;
}

/** 条件树（components.md §10.1 ConditionTree）：all 全部成立 / any 任一成立，可嵌套。 */
export interface ConditionTree {
  all?: Condition[];
  any?: Condition[];
}

export type Condition = ConditionTree | CtxCondition | FieldCondition;

/** 参与条件求值的字段（FormSchemaField 子集；type / storage 只影响必填是否参与，不影响显示求值）。 */
export interface ConditionalField {
  categoryKey: string;
  key: string;
  /** 基础必填（fe_field.required）。 */
  required?: boolean;
  /** 条件必填（fe_field.required_when；为空不追加必填）。 */
  requiredWhen?: ConditionTree | null;
  /** 存储方式；非 json 的字段值不经表单 JSON 记录，不做条件必填（实体必填随 E13）。 */
  storage?: string;
  /** 字段类型；derived 不做条件必填（由 E11-T05 重算）。 */
  type?: string;
  /** 条件显示（fe_field.visible_when；为空恒显示）。 */
  visibleWhen?: ConditionTree | null;
}

/** 一条必填缺失命中：与后端 42200 data.fieldErrors[] 元素同构（rule=required，文案「必填」）。 */
export interface RequiredIssue {
  categoryKey: string;
  key: string;
  message: string;
  rule: string;
}

/**
 * 条件树求值。visibleWhen / requiredWhen 共用：visibleWhen 成立才显示；requiredWhen 成立在
 * required 之外追加必填（有效必填 = 显示 &&（required || requiredWhen 成立））。
 * 条件为空 / null / 非法结构恒真（防御：不隐藏、不加必填）。
 */
export function evaluateCondition(
  tree: ConditionTree | null | undefined,
  data: null | Record<string, unknown> | undefined,
  context?: null | Record<string, unknown>,
): boolean {
  if (tree === null || tree === undefined) return true;
  if (!isPlainObject(tree)) return true; // 坏配置防御恒真

  const node = tree as Record<string, unknown>;
  const all = node.all;
  const any = node.any;
  const hasAll = Array.isArray(all);
  const hasAny = Array.isArray(any);

  if (hasAll) {
    for (const child of all as Condition[]) {
      if (!evaluateCondition(child as ConditionTree, data, context))
        return false;
    }
  }
  if (hasAny) {
    let hit = false;
    for (const child of any as Condition[]) {
      if (evaluateCondition(child as ConditionTree, data, context)) {
        hit = true;
        break;
      }
    }
    if (!hit) return false;
  }
  if (hasAll || hasAny) return true;
  return evaluateLeaf(node, data, context);
}

/** 字段是否显示：visibleWhen 为空恒显示（无条件字段）。 */
export function isFieldVisible(
  field: Pick<ConditionalField, 'key' | 'visibleWhen'>,
  data: null | Record<string, unknown> | undefined,
  context?: null | Record<string, unknown>,
): boolean {
  return evaluateCondition(field.visibleWhen, data, context);
}

/** 显示中的字段里被隐藏的稳定键（渲染器清空值并 emit cleared-hidden；提交时后端同样清空）。 */
export function hiddenFieldKeys(
  fields: Array<Pick<ConditionalField, 'key' | 'visibleWhen'>>,
  data: null | Record<string, unknown> | undefined,
  context?: null | Record<string, unknown>,
): string[] {
  return fields
    .filter((field) => !isFieldVisible(field, data, context))
    .map((field) => field.key);
}

/**
 * 有效必填：字段显示 &&（required || requiredWhen 成立）。requiredWhen 为空不追加必填（无条件必填
 * 就是 required 列）；隐藏字段永不必填（值清空、不计完整度，A-PAT-019）。
 */
export function isRequiredField(
  field: Pick<
    ConditionalField,
    'key' | 'required' | 'requiredWhen' | 'visibleWhen'
  >,
  data: null | Record<string, unknown> | undefined,
  context?: null | Record<string, unknown>,
): boolean {
  if (!isFieldVisible(field, data, context)) return false;
  if (field.required) return true;
  if (field.requiredWhen === null || field.requiredWhen === undefined)
    return false;
  return evaluateCondition(field.requiredWhen, data, context);
}

/**
 * 条件必填缺失清单（FormRenderer validate 的 missingRequired；与后端 FormRuleEngine.checkRequired
 * 同语义）：显示中、参与必填（storage=json 且非派生）、有效必填而值为空。
 */
export function missingRequiredFields(
  fields: ConditionalField[],
  data: null | Record<string, unknown> | undefined,
  context?: null | Record<string, unknown>,
): RequiredIssue[] {
  const values = data ?? {};
  const missing: RequiredIssue[] = [];
  for (const field of fields) {
    if (field.storage !== undefined && field.storage !== 'json') continue;
    if (field.type === 'derived') continue;
    if (!isFieldVisible(field, values, context)) continue;
    if (!isRequiredField(field, values, context)) continue;
    if (isEmptyFormValue(values[field.key])) {
      // 文案与后端 FormRuleEngine 逐字一致（components.md §10.3「必填」）
      missing.push({
        key: field.key,
        categoryKey: field.categoryKey,
        rule: 'required',
        message: '必填',
      });
    }
  }
  return missing;
}

/**
 * tech-plan §6「被隐藏字段的值清空」：移除隐藏字段残留的值（只移除有值的键），返回清洗后的 data
 * 与被清空的稳定键（cleared-hidden 口径；与后端 FormService.stripHiddenFields 同语义）。
 * 不修改入参（返回新对象）。
 */
export function stripHiddenFieldValues(
  fields: Array<Pick<ConditionalField, 'key' | 'visibleWhen'>>,
  data: null | Record<string, unknown> | undefined,
  context?: null | Record<string, unknown>,
): { clearedKeys: string[]; data: Record<string, unknown> } {
  const values = data ?? {};
  const hidden = new Set(hiddenFieldKeys(fields, values, context));
  const cleaned: Record<string, unknown> = {};
  const clearedKeys: string[] = [];
  for (const [key, value] of Object.entries(values)) {
    if (hidden.has(key)) {
      if (value !== null && value !== undefined) {
        clearedKeys.push(key);
      }
      continue;
    }
    cleaned[key] = value;
  }
  return { data: cleaned, clearedKeys };
}

// ---------------------------------------------------------------- 叶子求值

function evaluateLeaf(
  leaf: Record<string, unknown>,
  data: null | Record<string, unknown> | undefined,
  context?: null | Record<string, unknown>,
): boolean {
  const op = typeof leaf.op === 'string' ? leaf.op : null;
  const field =
    typeof leaf.field === 'string' && leaf.field ? leaf.field : null;
  const ctx = typeof leaf.ctx === 'string' && leaf.ctx ? leaf.ctx : null;
  if (op === null || (field === null) === (ctx === null)) return true; // 残缺防御恒真

  const operand =
    field === null ? resolvePath(context, ctx) : (data ?? {})[field];
  const expected = 'value' in leaf ? leaf.value : undefined;

  switch (op) {
    case 'empty': {
      return isEmptyFormValue(operand);
    }
    case 'eq': {
      return (
        operand !== null && operand !== undefined && eqValue(operand, expected)
      );
    }
    case 'gt':
    case 'gte':
    case 'lt':
    case 'lte': {
      return orderValue(op, operand, expected);
    }
    case 'in': {
      return (
        operand !== null &&
        operand !== undefined &&
        Array.isArray(expected) &&
        expected.some((item) => eqValue(operand, item))
      );
    }
    case 'ne': {
      return (
        operand !== null && operand !== undefined && !eqValue(operand, expected)
      );
    }
    case 'nin': {
      return (
        operand !== null &&
        operand !== undefined &&
        !(
          Array.isArray(expected) &&
          expected.some((item) => eqValue(operand, item))
        )
      );
    }
    case 'notEmpty': {
      return !isEmptyFormValue(operand);
    }
    default: {
      return true;
    } // 不认识的操作符：防御恒真
  }
}

/** eq / in 的元素比较：数值与数值、布尔与布尔、字符串与字符串严格同型（1 与 "1" 不等）。 */
function eqValue(operand: unknown, expected: unknown): boolean {
  if (isNumber(expected) && isNumber(operand)) {
    return expected === operand;
  }
  if (typeof expected === 'boolean' && typeof operand === 'boolean') {
    return expected === operand;
  }
  if (typeof expected === 'string' && typeof operand === 'string') {
    return expected === operand;
  }
  return false; // 类型不匹配（含 expected 为 null / undefined / 对象）不成立
}

/** gt / gte / lt / lte：三态比较（compareValues 值编码口径），不可比不成立。 */
function orderValue(
  op: 'gt' | 'gte' | 'lt' | 'lte',
  operand: unknown,
  expected: unknown,
): boolean {
  if (expected === null || expected === undefined) return false;
  if (!isNumber(expected) && typeof expected !== 'string') return false; // 布尔 / 数组等不参与大小比较
  const comparison = compareValues(operand, expected);
  if (comparison === null) return false; // 不可比（数值 × 日期、普通文本、一侧为空）：不成立
  switch (op) {
    case 'gt': {
      return comparison > 0;
    }
    case 'gte': {
      return comparison >= 0;
    }
    case 'lt': {
      return comparison < 0;
    }
    default: {
      return comparison <= 0;
    }
  }
}

// ---------------------------------------------------------------- 取值与空值口径

/** ctx 点路径取值（'device.type' → context.device.type），路径断链返回 undefined（缺失 → 不成立）。 */
function resolvePath(
  context: null | Record<string, unknown> | undefined,
  path: string,
): unknown {
  let node: unknown = context ?? {};
  for (const part of path.split('.')) {
    if (!isPlainObject(node)) return undefined;
    node = (node as Record<string, unknown>)[part];
  }
  return node;
}

/**
 * 空值口径（与 empty / notEmpty 及必填缺失同一口径，后端 FormConditionEngine.isEmptyValue 同语义）：
 * null / undefined / 空串 / 空数组 / 值全为空的 pair_number 对象为空；0 与 false 是已答，不算空。
 */
export function isEmptyFormValue(value: unknown): boolean {
  if (value === null || value === undefined) return true;
  if (typeof value === 'string') return value.length === 0;
  if (Array.isArray(value)) return value.length === 0;
  if (isPlainObject(value)) {
    return Object.values(value).every((item) => isEmptyFormValue(item));
  }
  return false;
}

function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}
