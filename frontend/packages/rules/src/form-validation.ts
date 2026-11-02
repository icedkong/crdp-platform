/**
 * E11-T04 / A-FLD-003、A-FLD-006、C-FLD-007：表单值校验（范围 + 跨字段），与后端
 * `com.crdp.form.rule.app.FormRuleEngine` 同语义（验收「前后端校验一致」）。
 *
 * 共享用例 `backend/crdp-form/src/test/resources/shared-cases/form-validation-cases.json`
 * 两端各执行一遍（components.md §10.5），errors / warnings 逐条（key、categoryKey、rule、message）一致。
 *
 * - 范围：`number` 与 `pair_number`（length / width 各查同一范围）按 hardRange / softRange 检查——
 *   超硬范围拦截（errors，保存 / 提交 42200）、超软范围提示可保存（warnings）；硬范围已命中不叠加软提示；
 *   派生字段与空值跳过。
 * - 跨字段：`rules[]`（fe_rule，FormSchema 实时下发）COMPARE 语义「leftField op rightField 须成立」
 *   （op = lt / lte），数值比大小、日期 / 日期时间比先后（日期按当日 00:00 归一，分钟 / 秒两种写法等价）；
 *   一侧为空或不可比不触发。HARD 违反进 errors，SOFT 进 warnings。
 * - 单位（unit）不做换算：范围值与录入值同单位（A-FLD-003「数值有单位与合理范围」），单位文案由渲染器展示（E11-T07）。
 */

/** 数值范围（与 packages/api FieldRange 同构；本包不依赖工作区包）。 */
export interface ValidationRange {
  max?: null | number;
  min?: null | number;
}

/** 跨字段比较参数（fe_rule.params）。 */
export interface CrossFieldCompareParams {
  leftField: string;
  op: 'lt' | 'lte';
  rightField: string;
}

/** 跨字段校验规则（FormSchema.rules[] 下发口径，fe_rule 实时读取）。 */
export interface CrossFieldRule {
  categoryKey: string;
  /** 规则稳定编码（命中时的 rule 标识）。 */
  id: string;
  /** 提示挂靠字段稳定键（fieldErrors[].key 同源）。 */
  key: string;
  message: string;
  params: CrossFieldCompareParams;
  /** HARD 违反拦截（42200）；SOFT 黄色提示可保存。 */
  severity: 'HARD' | 'SOFT';
}

/** 一条校验命中：与后端 42200 data.fieldErrors[] 元素同构。 */
export interface FieldIssue {
  categoryKey: string;
  key: string;
  message: string;
  /** hardRange / softRange / 规则 id。 */
  rule: string;
}

/** 校验结果：errors 拦截提交，warnings 黄色提示（要求确认，不阻止保存）。 */
export interface FormValidationResult {
  errors: FieldIssue[];
  warnings: FieldIssue[];
}

/** 参与校验的字段（FormSchemaField 子集；范围字段才查范围）。 */
export interface ValidatableField {
  categoryKey: string;
  hardRange?: null | ValidationRange;
  key: string;
  softRange?: null | ValidationRange;
  type: string;
}

/** 就地校验一份表单数据：字段口径来自模板（不得页面写死），规则来自 FormSchema.rules。 */
export function validateFormData(
  fields: ValidatableField[],
  rules: CrossFieldRule[],
  data: null | Record<string, unknown> | undefined,
): FormValidationResult {
  const values = data ?? {};
  const errors: FieldIssue[] = [];
  const warnings: FieldIssue[] = [];

  for (const field of fields) {
    if (field.type !== 'number' && field.type !== 'pair_number') continue;
    const hard = normalizeRange(field.hardRange);
    const soft = normalizeRange(field.softRange);
    if (!hard && !soft) continue;
    for (const value of numericValues(field.type, values[field.key])) {
      if (hard && !hard.contains(value)) {
        errors.push({
          key: field.key,
          categoryKey: field.categoryKey,
          rule: 'hardRange',
          message: hard.message(),
        });
      } else if (soft && !soft.contains(value)) {
        warnings.push({
          key: field.key,
          categoryKey: field.categoryKey,
          rule: 'softRange',
          message: soft.message(),
        });
      }
    }
  }

  for (const rule of rules) {
    const comparison = compareValues(
      values[rule.params?.leftField],
      values[rule.params?.rightField],
    );
    if (comparison === null) continue; // 一侧为空或不可比：不触发
    const holds = rule.params.op === 'lte' ? comparison <= 0 : comparison < 0;
    if (holds) continue;
    const issue: FieldIssue = {
      key: rule.key,
      categoryKey: rule.categoryKey,
      rule: rule.id,
      message: rule.message,
    };
    if (rule.severity === 'SOFT') {
      warnings.push(issue);
    } else {
      errors.push(issue);
    }
  }

  return { errors, warnings };
}

// ---------------------------------------------------------------- 范围

function numericValues(type: string, value: unknown): number[] {
  if (type === 'number') {
    return isNumber(value) ? [value] : [];
  }
  // pair_number：取已填分量；形状问题交给类型闸门，这里防御跳过
  if (isPlainObject(value)) {
    const pair = value as Record<string, unknown>;
    return ['length', 'width']
      .map((name) => pair[name])
      .filter((item): item is number => isNumber(item));
  }
  return [];
}

function isNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

interface NormalizedRange {
  contains(value: number): boolean;
  max?: number;
  message(): string;
  min?: number;
}

function normalizeRange(
  range?: null | ValidationRange,
): NormalizedRange | null {
  if (!range) return null;
  const min = range.min ?? undefined;
  const max = range.max ?? undefined;
  if (min === undefined && max === undefined) return null;
  return {
    min,
    max,
    contains(value: number) {
      return (
        (min === undefined || value >= min) &&
        (max === undefined || value <= max)
      );
    },
    // 文案与后端 FormRuleEngine 逐字一致（共享用例锁定）
    message() {
      if (min !== undefined && max !== undefined) {
        return `取值应在 ${formatNumber(min)}–${formatNumber(max)} 之间`;
      }
      if (min !== undefined) {
        return `取值不能小于 ${formatNumber(min)}`;
      }
      return `取值不能大于 ${formatNumber(max)}`;
    },
  };
}

/** 整数不带小数点（50），小数按原样（2.5）——与 Java Double → String 的格式化一致。 */
function formatNumber(value: number): string {
  return Number.isInteger(value) ? String(value) : String(value);
}

// ---------------------------------------------------------------- 跨字段比较（值编码口径）

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const DATETIME_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/;

/** 可比较键（数值或归一后的时间键）的三态比较：负 / 零 / 正。 */
function threeWay(left: number | string, right: number | string): number {
  if (left < right) return -1;
  if (left > right) return 1;
  return 0;
}

/**
 * 比较两个值（负 / 零 / 正；不可比或一侧为空返回 null）：数值比大小；日期归一为当日
 * 00:00、日期时间补秒后按时间先后；数值 × 日期、普通文本不可比。
 */
export function compareValues(left: unknown, right: unknown): null | number {
  if (
    left === null ||
    left === undefined ||
    right === null ||
    right === undefined
  ) {
    return null;
  }
  const leftNumber = isNumber(left);
  const rightNumber = isNumber(right);
  if (leftNumber && rightNumber) {
    return threeWay(left, right);
  }
  if (leftNumber !== rightNumber) return null; // 数值与日期不可比
  if (typeof left === 'string' && typeof right === 'string') {
    const leftKey = temporalKey(left);
    const rightKey = temporalKey(right);
    if (leftKey === null || rightKey === null) return null; // 非日期写法不比较
    return threeWay(leftKey, rightKey);
  }
  return null;
}

/** 日期 / 日期时间 → 可字典序比较的归一键 `YYYY-MM-DDTHH:mm:ss`（与 Java temporalKey 一致）。 */
function temporalKey(raw: string): null | string {
  if (DATE_RE.test(raw)) {
    return isValidDate(raw) ? `${raw}T00:00:00` : null;
  }
  if (DATETIME_RE.test(raw)) {
    const normalized = raw.length === 16 ? `${raw}:00` : raw;
    const [date, time = ''] = normalized.split('T');
    if (!isValidDate(date)) return null;
    const [hour, minute, second] = time.split(':').map(Number);
    if (hour > 23 || minute > 59 || second > 59) return null;
    return normalized;
  }
  return null;
}

function isValidDate(raw: string): boolean {
  const [year, month, day] = raw.split('-').map(Number);
  if (month < 1 || month > 12 || day < 1) return false;
  const leap = (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
  const daysInMonth = [
    31,
    leap ? 29 : 28,
    31,
    30,
    31,
    30,
    31,
    31,
    30,
    31,
    30,
    31,
  ][month - 1];
  return day <= daysInMonth;
}
