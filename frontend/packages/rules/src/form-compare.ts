/**
 * E17-T03 / A-FU-027（Q41）、C-FU-011（随 E21）：录入时对比标红求值器（前端执行器）。
 *
 * 与后端 `com.crdp.followup.compare.domain.CompareEvaluator` 是同一份实现的两侧，由共享用例
 * `backend/crdp-followup/src/test/resources/shared-cases/compare-cases.json` 锁定逐条一致
 * （components.md §10.5「对比（E17）沿用同一目录」）。求值口径（hospital-a-spec 第 5 章
 * 「录入时对比提示规则」、tech-plan §11、迁移 V67 注释）：
 *
 * - 上一次取值：FOLLOWUP 取 `values[targetField]`；BASELINE（第 1 年或此前无随访）取规则的
 *   回落基线列 `values[baselineField]`；previous 为空时对比类规则全部跳过；
 * - 「上一次该字段为空时跳过该条规则」：DIFF / CHANGE_PCT / ORDINAL_RISE 需要上一次有值；
 *   FLIP_TO_YES 上一次为空时仅无回落列的规则生效（如本次「是否死亡」= 是）；
 * - 空值口径：null / 缺键 / 空串为空（0 与 false 有效）；本次为空一律不命中；
 * - 边界符：DIFF / ORDINAL_RISE ≥、CHANGE_PCT 严格 >、OUT_OF_RANGE / VALUE_HIT 按 op；
 * - 变化量文案（验收「标红字段旁显示对比值与变化量」）前后端逐字一致：数值两位小数内去尾零、
 *   % 显示「个百分点」、其余单位前加空格；
 * - 命中顺序 = 规则入参顺序；只在表单内提示，不弹窗、不发通知、不拦提交（A-FU-027）。
 */

/** 规则类型（与后端 CompareRuleType / @crdp/api CompareRuleType 同源）。 */
export type CompareRuleKind =
  | 'CHANGE_PCT'
  | 'DIFF'
  | 'FLIP_TO_YES'
  | 'NEW_EVENT'
  | 'ORDINAL_RISE'
  | 'OUT_OF_RANGE'
  | 'VALUE_HIT';

/** 阈值参数（与 @crdp/api CompareRuleParams 同形；求值只读阈值键）。 */
export interface CompareRuleThresholds {
  codes?: string[];
  dateField?: string;
  direction?: 'ANY' | 'DROP' | 'RISE';
  grades?: number;
  op?: 'GT' | 'GTE' | 'LT' | 'LTE';
  percent?: number;
  reachCodes?: string[];
  threshold?: number;
  triggerCodes?: string[];
  value?: number;
}

/** 求值规则（@crdp/api CompareRule 的求值子集；启用行由调用方过滤）。 */
export interface CompareRuleInput {
  baselineField?: null | string;
  categoryKey: string;
  message: string;
  params: CompareRuleThresholds;
  ruleCode: string;
  ruleType: CompareRuleKind;
  targetField: string;
  unit?: null | string;
}

/** 上一次（E17-T02 `previous` 的求值子集；null / undefined = 无上一次且基线回落全空）。 */
export interface ComparePrevious {
  /** 上一次对比时点（FOLLOWUP = 记录日期；BASELINE 无日期）。 */
  date?: null | string;
  kind?: 'BASELINE' | 'FOLLOWUP';
  values: Record<string, unknown>;
}

/** 一条命中（FormRenderer highlight 事件载荷；与后端 Hit 同形）。 */
export interface CompareHit {
  /** 分类锚点键（分组标题 / CategoryBar 标红数挂靠）。 */
  categoryKey: string;
  /** 有符号变化量（本次 − 上一次；序数规则为级数差；无变化量概念为 null）。 */
  change: null | number;
  /** 变化量文案（「下降 11 个百分点」「新发」…；固定阈值规则为 null）。 */
  changeText: null | string;
  /** 命中字段稳定键。 */
  key: string;
  /** 规则完整提示文案（tooltip）。 */
  message: string;
  /** 命中规则用到的上一次值（原样；固定阈值规则为 null）。 */
  previousValue: unknown;
  /** 命中规则稳定编码（ruleCode）。 */
  ruleId: string;
}

/** 序数表：字段稳定键 → 选项编码 → 序数（fe_option.ordinal，渲染器从模板 optionSet 取）。 */
export type OrdinalMap = Record<string, Record<string, number>>;

const COMPARE_RULE_KINDS = new Set<string>([
  'CHANGE_PCT',
  'DIFF',
  'FLIP_TO_YES',
  'NEW_EVENT',
  'ORDINAL_RISE',
  'OUT_OF_RANGE',
  'VALUE_HIT',
]);

/**
 * 下发规则行（@crdp/api CompareRule，字段单位列叫 fieldUnit）→ 求值规则（unit）。
 * 未知规则类型返回 null（与后端「未知类型不参与求值」防御口径一致）。
 */
export function toCompareRuleInput(rule: {
  baselineField?: null | string;
  categoryKey: string;
  fieldUnit?: null | string;
  message: string;
  params: CompareRuleThresholds;
  ruleCode: string;
  ruleType: string;
  targetField: string;
}): CompareRuleInput | null {
  if (!COMPARE_RULE_KINDS.has(rule.ruleType)) {
    return null;
  }
  return {
    baselineField: rule.baselineField ?? null,
    categoryKey: rule.categoryKey,
    message: rule.message,
    params: rule.params,
    ruleCode: rule.ruleCode,
    ruleType: rule.ruleType as CompareRuleKind,
    targetField: rule.targetField,
    unit: rule.fieldUnit ?? null,
  };
}

/** 求值：命中列表（规则顺序；无规则 / 全不命中为空数组）。 */
export function evaluateCompareRules(
  rules: CompareRuleInput[] | null | undefined,
  current: Record<string, unknown>,
  previous: ComparePrevious | null | undefined,
  ordinals?: null | OrdinalMap,
): CompareHit[] {
  const hits: CompareHit[] = [];
  for (const rule of rules ?? []) {
    const hit = evaluateRule(rule, current, previous ?? null, ordinals ?? {});
    if (hit) hits.push(hit);
  }
  return hits;
}

// ---------------------------------------------------------------- 各类型

/** 差值下降 / 上升：|本次 − 上一次| ≥ threshold（DROP 看 prev − cur，RISE 看 cur − prev）。 */
function evaluateDiff(
  rule: CompareRuleInput,
  current: Record<string, unknown>,
  previous: ComparePrevious | null,
): CompareHit | null {
  const cur = numberOf(current[rule.targetField]);
  const prevRaw = previousValueOf(rule, previous);
  const prev = numberOf(prevRaw);
  if (cur === null || prev === null) return null;
  const rawDelta = cur - prev;
  const direction = rule.params.direction;
  const threshold = rule.params.threshold ?? 0;
  let hit = false;
  if (direction === 'DROP') {
    hit = -rawDelta >= threshold;
  } else if (direction === 'RISE') {
    hit = rawDelta >= threshold;
  }
  if (!hit) return null;
  const change = round2(rawDelta);
  return {
    ruleId: rule.ruleCode,
    key: rule.targetField,
    categoryKey: rule.categoryKey,
    message: rule.message,
    previousValue: prevRaw,
    change,
    changeText: `${direction === 'DROP' ? '下降' : '增加'} ${formatCompareNumber(
      Math.abs(change),
    )}${unitSuffix(rule.unit)}`,
  };
}

/** 变化率：（本次 − 上一次）÷ 上一次 × 100，严格 > percent；上一次为 0 无效跳过。 */
function evaluateChangePct(
  rule: CompareRuleInput,
  current: Record<string, unknown>,
  previous: ComparePrevious | null,
): CompareHit | null {
  const cur = numberOf(current[rule.targetField]);
  const prevRaw = previousValueOf(rule, previous);
  const prev = numberOf(prevRaw);
  if (cur === null || prev === null || prev === 0) return null;
  const rawPct = ((cur - prev) / prev) * 100;
  const direction = rule.params.direction;
  const percent = rule.params.percent ?? 0;
  let hit = false;
  switch (direction) {
    case 'ANY': {
      hit = Math.abs(rawPct) > percent;

      break;
    }
    case 'DROP': {
      hit = -rawPct > percent;

      break;
    }
    case 'RISE': {
      hit = rawPct > percent;

      break;
    }
    // No default
  }
  if (!hit) return null;
  const change = round2(rawPct);
  const word =
    direction === 'RISE' || (direction === 'ANY' && rawPct > 0)
      ? '升高'
      : '下降';
  return {
    ruleId: rule.ruleCode,
    key: rule.targetField,
    categoryKey: rule.categoryKey,
    message: rule.message,
    previousValue: prevRaw,
    change,
    changeText: `${word} ${formatCompareNumber(Math.abs(change))}%`,
  };
}

/** 序数升级：序数差 ≥ grades，或本次取值 ∈ reachCodes；任一侧序数未知跳过。 */
function evaluateOrdinalRise(
  rule: CompareRuleInput,
  current: Record<string, unknown>,
  previous: ComparePrevious | null,
  ordinals: OrdinalMap,
): CompareHit | null {
  const curCode = optionCodeOf(current[rule.targetField]);
  const prevRaw = previousValueOf(rule, previous);
  const prevCode = optionCodeOf(prevRaw);
  const ordinalMap = ordinals[rule.targetField];
  if (curCode === null || prevCode === null || !ordinalMap) return null;
  const curOrdinal = ordinalMap[String(curCode)];
  const prevOrdinal = ordinalMap[String(prevCode)];
  if (curOrdinal === undefined || prevOrdinal === undefined) return null;
  const delta = curOrdinal - prevOrdinal;
  const grades = rule.params.grades ?? 0;
  const reach = (rule.params.reachCodes ?? []).includes(String(curCode));
  if (delta < grades && !reach) return null;
  return {
    ruleId: rule.ruleCode,
    key: rule.targetField,
    categoryKey: rule.categoryKey,
    message: rule.message,
    previousValue: prevRaw,
    change: delta,
    changeText: delta >= grades ? `升高 ${delta} 级` : '达到预设档位',
  };
}

/**
 * 新发事件：本次命中（triggerCodes；缺省 = 非空且编码 ≠ 0「无」），且上一次该事件为空 / 为无，
 * 或事件日期晚于上一次随访日期（「或上一次未填 / 为无」口径与迁移 V67 注释一致）。
 */
function evaluateNewEvent(
  rule: CompareRuleInput,
  current: Record<string, unknown>,
  previous: ComparePrevious | null,
): CompareHit | null {
  const curRaw = current[rule.targetField];
  const triggers = rule.params.triggerCodes ?? [];
  const currentHit =
    triggers.length > 0
      ? triggers.includes(String(optionCodeOf(curRaw)))
      : isFilled(curRaw) && !isZero(curRaw);
  if (!currentHit) return null;
  const prevRaw = previousValueOf(rule, previous);
  const previousEmpty = !isFilled(prevRaw) || isZero(prevRaw);
  let isNew: boolean;
  if (previousEmpty) {
    isNew = true;
  } else {
    const dateField = rule.params.dateField ?? null;
    const curDate = textOf(dateField ? current[dateField] : null);
    isNew =
      dateField !== null &&
      curDate !== null &&
      previous?.date !== null &&
      curDate > previous.date;
  }
  if (!isNew) return null;
  return {
    ruleId: rule.ruleCode,
    key: rule.targetField,
    categoryKey: rule.categoryKey,
    message: rule.message,
    previousValue: isFilled(prevRaw) ? prevRaw : null,
    change: null,
    changeText: '新发',
  };
}

/** 由否变是：上一次为否且本次为是；上一次为空时仅无回落列的规则（本次 = 是即新发，如死亡）。 */
function evaluateFlipToYes(
  rule: CompareRuleInput,
  current: Record<string, unknown>,
  previous: ComparePrevious | null,
): CompareHit | null {
  if (!isYes(current[rule.targetField])) return null;
  const prevRaw = previousValueOf(rule, previous);
  if (!isFilled(prevRaw)) {
    // 有回落列却缺值：上一次为空跳过；无对比对象（如「是否死亡」）：本次 = 是即标红
    if (rule.baselineField !== null && rule.baselineField !== undefined) {
      return null;
    }
  } else if (!isNo(prevRaw)) {
    return null;
  }
  return {
    ruleId: rule.ruleCode,
    key: rule.targetField,
    categoryKey: rule.categoryKey,
    message: rule.message,
    previousValue: isFilled(prevRaw) ? prevRaw : null,
    change: null,
    changeText: '由否变是',
  };
}

/** 超出提示范围：本次数值与 {op, value} 比较（与字段字典取值校验范围分开配置，不拦提交）。 */
function evaluateOutOfRange(
  rule: CompareRuleInput,
  current: Record<string, unknown>,
): CompareHit | null {
  const cur = numberOf(current[rule.targetField]);
  if (cur === null) return null;
  return compareOp(rule.params.op, cur, rule.params.value ?? 0)
    ? fixedHit(rule)
    : null;
}

/** 取值命中：codes（选项编码）或 {op, value} 二选一。 */
function evaluateValueHit(
  rule: CompareRuleInput,
  current: Record<string, unknown>,
): CompareHit | null {
  const raw = current[rule.targetField];
  const codes = rule.params.codes ?? [];
  if (codes.length > 0) {
    const code = optionCodeOf(raw);
    return code !== null && codes.includes(String(code))
      ? fixedHit(rule)
      : null;
  }
  const cur = numberOf(raw);
  return cur !== null && compareOp(rule.params.op, cur, rule.params.value ?? 0)
    ? fixedHit(rule)
    : null;
}

/** 固定阈值命中（无对比值与变化量，tooltip 只看规则文案）。 */
function fixedHit(rule: CompareRuleInput): CompareHit {
  return {
    ruleId: rule.ruleCode,
    key: rule.targetField,
    categoryKey: rule.categoryKey,
    message: rule.message,
    previousValue: null,
    change: null,
    changeText: null,
  };
}

function evaluateRule(
  rule: CompareRuleInput,
  current: Record<string, unknown>,
  previous: ComparePrevious | null,
  ordinals: OrdinalMap,
): CompareHit | null {
  switch (rule.ruleType) {
    case 'CHANGE_PCT': {
      return evaluateChangePct(rule, current, previous);
    }
    case 'DIFF': {
      return evaluateDiff(rule, current, previous);
    }
    case 'FLIP_TO_YES': {
      return evaluateFlipToYes(rule, current, previous);
    }
    case 'NEW_EVENT': {
      return evaluateNewEvent(rule, current, previous);
    }
    case 'ORDINAL_RISE': {
      return evaluateOrdinalRise(rule, current, previous, ordinals);
    }
    case 'OUT_OF_RANGE': {
      return evaluateOutOfRange(rule, current);
    }
    case 'VALUE_HIT': {
      return evaluateValueHit(rule, current);
    }
    default: {
      return null; // 未知类型不参与求值（与后端防御口径一致）
    }
  }
}

// ---------------------------------------------------------------- 取值与口径

/** 上一次该规则的取值：FOLLOWUP 取 targetField；BASELINE 取回落基线列。 */
function previousValueOf(
  rule: CompareRuleInput,
  previous: ComparePrevious | null,
): unknown {
  if (!previous) return null;
  const key =
    previous.kind === 'BASELINE' ? rule.baselineField : rule.targetField;
  return key === null || key === undefined ? null : previous.values[key];
}

function compareOp(
  op: CompareRuleThresholds['op'],
  cur: number,
  bound: number,
): boolean {
  switch (op) {
    case 'GT': {
      return cur > bound;
    }
    case 'GTE': {
      return cur >= bound;
    }
    case 'LT': {
      return cur < bound;
    }
    case 'LTE': {
      return cur <= bound;
    }
    default: {
      return false;
    }
  }
}

function numberOf(value: unknown): null | number {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** 选项编码（单选 / 下拉存整数编码；整数直接取，小数取整）。 */
function optionCodeOf(value: unknown): null | number {
  if (typeof value === 'number' && Number.isFinite(value)) {
    return Math.round(value);
  }
  return null;
}

function textOf(value: unknown): null | string {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : null;
}

/** 空值口径：null / 缺键 / 空串 / 空数组为空（0 与 false 有效）。 */
function isFilled(value: unknown): boolean {
  if (value === null || value === undefined) return false;
  if (typeof value === 'string') return value.trim() !== '';
  if (Array.isArray(value)) return value.length > 0;
  return true;
}

function isZero(value: unknown): boolean {
  return typeof value === 'number' && value === 0;
}

/** 「是 / 否」口径：yesno 编码 1 / 0 与 true / false。 */
function isYes(value: unknown): boolean {
  if (typeof value === 'boolean') return value;
  return typeof value === 'number' && value === 1;
}

function isNo(value: unknown): boolean {
  if (typeof value === 'boolean') return !value;
  return typeof value === 'number' && value === 0;
}

/**
 * 变化量按两位小数收敛（IEEE 尘埃 11.299…→ 11.3，与文案同口径；两端运算一致）。
 */
function round2(value: number): number {
  return Math.round(value * 100) / 100;
}

/** 数值文案（前后端逐字一致）：两位小数内去尾零 —— 整数显示整数、11.3 显示 11.3。 */
export function formatCompareNumber(value: number): string {
  const rounded = round2(value);
  return Number.isInteger(rounded) ? String(rounded) : String(rounded);
}

/** 单位后缀：% 显示「个百分点」（EF 下降 10 个百分点），其余单位前加空格。 */
function unitSuffix(unit: null | string | undefined): string {
  if (!unit) return '';
  return unit === '%' ? ' 个百分点' : ` ${unit}`;
}
