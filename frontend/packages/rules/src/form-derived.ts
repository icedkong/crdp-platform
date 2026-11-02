/**
 * E11-T05 / A-FLD-005、C-FLD-006：内置派生函数库（tech-plan §6「派生字段」）——与后端
 * `com.crdp.form.rule.app.FormDerivedEngine` 同语义（验收「BMI、年龄等派生字段两端一致」）。
 *
 * 共享用例 `backend/crdp-form/src/test/resources/shared-cases/form-derived-cases.json`
 * 两端各执行一遍（components.md §10.5），每个派生字段的 value / status / message 逐条一致。
 *
 * - 函数（首期只用内置函数加参数，不开放任意脚本）：
 *   `DAYS_INCLUSIVE(起, 止)`（止 − 起 + 1，含当天）、`MINUTES_BETWEEN(起, 止)`（分钟向下取整）、
 *   `HOURS_BETWEEN_1DP(起, 止)`（小时 1 位小数，160 小时 54 分 → 160.9）、`BMI(体重 kg, 身高 cm)`
 *   （kg ÷ (cm ÷ 100)²，1 位小数）、`AGE_YEARS(出生, 参考日?)`（周岁，参考日缺省按今天）、
 *   `SUM(...)`（求和，整数来源保持整数）、`YEARS_SINCE(起, 止?)`（整周年数）、
 *   `DURATION_YM(起, 止?)`（「X 年 Y 个月」）、`CHANGE_PCT(本次, 上次)`（÷ 上次 × 100，1 位小数）。
 * - 留空与提示（A-FLD-005「输入缺失或结果无效时留空并提示」）：来源缺失 → status=missing、
 *   「缺少{显示名}」（多个用「、」连接，按参数顺序）；来源无效（止早于起、身高 ≤ 0、上次为 0）→
 *   status=invalid、「{止}早于{起}」等——文案与后端逐字一致；显示名取来源字段 label，缺省止日提示「今天」。
 * - 依赖拓扑：派生可以依赖派生，按依赖图拓扑顺序求值；被依赖方来源缺失时取其实际生效值（手工值）。
 * - 防御（与后端同口径，OQ-68）：未知函数、参数个数不符、坏定义、依赖成环的字段不出结果。
 * - 1 位小数舍入：`Math.round(v × 10) ÷ 10`——与 Java Math.round 同为「半值向 +∞」，双精度运算
 *   逐位一致（不用十进制 HALF_UP，避免两端分叉）。
 */

/** 派生定义（fe_field.derived_def / FormSchemaField.derived：{fn, args}，args 为字段稳定键）。 */
export interface DerivedDef {
  args: string[];
  fn: string;
}

/** 参与派生求值的字段（FormSchemaField 子集；label 用于留空提示文案）。 */
export interface DerivedSourceField {
  categoryKey?: string;
  /** 派生定义；仅 type=derived 的字段生效。 */
  derived?: DerivedDef | null;
  key: string;
  /** 显示名（提示文案「缺少身高」用）。 */
  label: string;
  type?: string;
}

/** 求值状态：ok 已算出 / missing 来源缺失（留空并提示）/ invalid 来源无效（留空并提示）。 */
export type DerivedStatus = 'invalid' | 'missing' | 'ok';

/** 一个派生字段的求值结果：value 为 null 即留空，message 为留空原因（ok 为 null，前后端逐字一致）。 */
export interface DerivedOutcome {
  message: null | string;
  status: DerivedStatus;
  value: null | number | string;
}

/** 重算选项：today 为固定参考日（YYYY-MM-DD，测试 / 导入重算用；缺省 = 浏览器当天）。 */
export interface ComputeDerivedOptions {
  today?: string;
}

/**
 * 重算全部派生字段（依赖拓扑顺序求值，结果按字段列表顺序，只含正常配置的派生字段）：
 * 来源字段变化时调用（渲染器实时重算，components.md §10.3「依赖拓扑顺序实时重算」）。
 */
export function computeDerivedValues(
  fields: Array<DerivedSourceField>,
  data: null | Record<string, unknown> | undefined,
  options?: ComputeDerivedOptions,
): Record<string, DerivedOutcome> {
  const evaluation: Evaluation = {
    byKey: new Map(fields.map((field) => [field.key, field])),
    computed: new Map<string, DerivedOutcome>(),
    defs: new Map<string, DerivedDef>(),
    today: todayOf(options),
    values: data ?? {},
  };
  for (const field of fields) {
    if (field.type === 'derived') {
      const def = parseDef(field.derived);
      if (def) evaluation.defs.set(field.key, def);
    }
  }

  for (const key of topologicalOrder(evaluation.defs)) {
    const def = evaluation.defs.get(key);
    if (!def) continue;
    const outcome = evaluate(evaluation, def);
    if (outcome) evaluation.computed.set(key, outcome); // 未知函数 → 不出结果（防御）
  }

  const outcomes: Record<string, DerivedOutcome> = {};
  for (const field of fields) {
    const outcome = evaluation.computed.get(field.key);
    if (outcome) outcomes[field.key] = outcome; // 成环 / 坏定义跳过 → 不产出
  }
  return outcomes;
}

/**
 * 来源缺失（missing）时派生字段允许手工录入（A-FLD-005「只有来源字段缺失时允许手工录入」，
 * 渲染器放开编辑并标 meta.manual）；结果无效（invalid）不允许手工录入，应先修正来源字段。
 */
export function isDerivedManualEditable(outcome: DerivedOutcome): boolean {
  return outcome.status === 'missing';
}

/** 手工录入控件类型：DURATION_YM 为文本（「X 年 Y 个月」），其余函数值为数值。 */
export function derivedManualKind(fn: string): 'number' | 'text' {
  return fn === 'DURATION_YM' ? 'text' : 'number';
}

// ---------------------------------------------------------------- 求值上下文

interface Evaluation {
  byKey: Map<string, DerivedSourceField>;
  computed: Map<string, DerivedOutcome>;
  defs: Map<string, DerivedDef>;
  today: string;
  values: Record<string, unknown>;
}

function evaluate(
  evaluation: Evaluation,
  def: DerivedDef,
): DerivedOutcome | null {
  const label = (arg: string): string =>
    evaluation.byKey.get(arg)?.label ?? arg;
  switch (def.fn) {
    case 'AGE_YEARS': {
      const birth = dateArg(evaluation, def.args[0]);
      if (!birth) return empty('missing', `缺少${label(def.args[0])}`);
      const end = referenceArg(evaluation, def, label);
      if (end.date < birth) {
        return empty('invalid', `${end.label}早于${label(def.args[0])}`);
      }
      return ok(span(birth, end.date).years);
    }
    case 'BMI': {
      const weight = numberArg(evaluation, def.args[0]);
      const height = numberArg(evaluation, def.args[1]);
      if (weight === null || height === null) {
        return empty(
          'missing',
          missingMessage(evaluation, def.args, [weight, height]),
        );
      }
      if (height <= 0) {
        return empty('invalid', `${label(def.args[1])}必须大于 0`);
      }
      return ok(round1(weight / (height / 100) ** 2));
    }
    case 'CHANGE_PCT': {
      const current = numberArg(evaluation, def.args[0]);
      const previous = numberArg(evaluation, def.args[1]);
      if (current === null || previous === null) {
        return empty(
          'missing',
          missingMessage(evaluation, def.args, [current, previous]),
        );
      }
      if (previous === 0) {
        return empty('invalid', `${label(def.args[1])}为 0，不能计算变化率`);
      }
      return ok(round1(((current - previous) / previous) * 100));
    }
    case 'DAYS_INCLUSIVE': {
      const start = dateArg(evaluation, def.args[0]);
      const end = dateArg(evaluation, def.args[1]);
      if (start === null || end === null) {
        return empty(
          'missing',
          missingMessage(evaluation, def.args, [start, end]),
        );
      }
      if (end < start) {
        return empty(
          'invalid',
          `${label(def.args[1])}早于${label(def.args[0])}`,
        );
      }
      return ok(daysBetween(start, end) + 1);
    }
    case 'DURATION_YM': {
      const start = dateArg(evaluation, def.args[0]);
      if (!start) return empty('missing', `缺少${label(def.args[0])}`);
      const end = referenceArg(evaluation, def, label);
      if (end.date < start) {
        return empty('invalid', `${end.label}早于${label(def.args[0])}`);
      }
      const duration = span(start, end.date);
      return ok(`${duration.years} 年 ${duration.months} 个月`);
    }
    case 'HOURS_BETWEEN_1DP':
    case 'MINUTES_BETWEEN': {
      const start = dateTimeArg(evaluation, def.args[0]);
      const end = dateTimeArg(evaluation, def.args[1]);
      if (start === null || end === null) {
        return empty(
          'missing',
          missingMessage(evaluation, def.args, [start, end]),
        );
      }
      if (end < start) {
        return empty(
          'invalid',
          `${label(def.args[1])}早于${label(def.args[0])}`,
        );
      }
      const minutes = (end - start) / 60_000; // 分钟差（毫秒换算，秒随除法截断）
      return ok(
        def.fn === 'HOURS_BETWEEN_1DP'
          ? round1(minutes / 60)
          : Math.floor(minutes),
      );
    }
    case 'SUM': {
      const numbers: number[] = [];
      const missingFields: string[] = [];
      for (const arg of def.args) {
        const number = numberArg(evaluation, arg);
        if (number === null) {
          missingFields.push(label(arg));
        } else {
          numbers.push(number);
        }
      }
      if (missingFields.length > 0) {
        return empty('missing', `缺少${missingFields.join('、')}`);
      }
      return ok(numbers.reduce((total, item) => total + item, 0));
    }
    case 'YEARS_SINCE': {
      const start = dateArg(evaluation, def.args[0]);
      if (!start) return empty('missing', `缺少${label(def.args[0])}`);
      const end = referenceArg(evaluation, def, label);
      if (end.date < start) {
        return empty('invalid', `${end.label}早于${label(def.args[0])}`);
      }
      return ok(span(start, end.date).years);
    }
    default: {
      return null; // 未知函数：不出结果（防御，OQ-68）
    }
  }
}

// ---------------------------------------------------------------- 取值与口径（与后端逐字一致）

/**
 * 参数的实际生效值：普通字段取数据；派生依赖取已算结果（ok → 计算值；missing → 手工值生效；
 * invalid → 空——结果无效不允许手工录入；成环跳过 → 空）。
 */
function effective(evaluation: Evaluation, key: string): unknown {
  if (!evaluation.defs.has(key)) {
    return evaluation.values[key];
  }
  const outcome = evaluation.computed.get(key);
  if (!outcome) return null;
  switch (outcome.status) {
    case 'missing': {
      return evaluation.values[key]; // 手工值生效（来源缺失允许手工录入）
    }
    case 'ok': {
      return outcome.value;
    }
    default: {
      return null;
    }
  }
}

function dateArg(evaluation: Evaluation, key: string): null | string {
  const value = effective(evaluation, key);
  if (typeof value !== 'string' || !DATE_RE.test(value)) return null;
  return isValidDate(value) ? value : null;
}

/** 日期时间（YYYY-MM-DDTHH:mm[:ss]，毫秒时间戳）；日期写法按当日 00:00 归一（与跨字段比较同口径）。 */
function dateTimeArg(evaluation: Evaluation, key: string): null | number {
  const value = effective(evaluation, key);
  if (typeof value !== 'string') return null;
  if (DATE_RE.test(value)) {
    return isValidDate(value) ? Date.parse(`${value}T00:00:00`) : null;
  }
  if (DATETIME_RE.test(value)) {
    const normalized = value.length === 16 ? `${value}:00` : value;
    const [date, time = ''] = normalized.split('T');
    if (!isValidDate(date)) return null;
    const [hour, minute, second] = time.split(':').map(Number);
    if (hour > 23 || minute > 59 || second > 59) return null;
    return Date.parse(normalized);
  }
  return null;
}

function numberArg(evaluation: Evaluation, key: string): null | number {
  const value = effective(evaluation, key);
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

/** 止日参数：args 第 2 位（可省）；字段值可解析用字段值，否则按今天（label 用字段显示名或「今天」）。 */
function referenceArg(
  evaluation: Evaluation,
  def: DerivedDef,
  label: (arg: string) => string,
): { date: string; label: string } {
  if (def.args.length > 1) {
    const fieldDate = dateArg(evaluation, def.args[1]);
    if (fieldDate) return { date: fieldDate, label: label(def.args[1]) };
  }
  return { date: evaluation.today, label: '今天' };
}

/** 需求参数取不到值（缺失 / 不可解析）的「缺少…」提示；全部可解析返回 null。 */
function missingMessage(
  evaluation: Evaluation,
  args: string[],
  resolved: Array<null | unknown>,
): null | string {
  const labels: string[] = [];
  for (let index = 0; index < resolved.length && index < args.length; index++) {
    if (resolved[index] === null || resolved[index] === undefined) {
      labels.push(evaluation.byKey.get(args[index])?.label ?? args[index]);
    }
  }
  return labels.length === 0 ? null : `缺少${labels.join('、')}`;
}

/** 保留 1 位小数：与 Java Math.round 同为「半值向 +∞」，双精度运算两端逐位一致。 */
function round1(value: number): number {
  return Math.round(value * 10) / 10;
}

/** 完整年 + 完整月（日未到减月，跨年借位）。 */
function span(start: string, end: string): { months: number; years: number } {
  const [startYear, startMonth, startDay] = start.split('-').map(Number);
  const [endYear, endMonth, endDay] = end.split('-').map(Number);
  let years = endYear - startYear;
  let months = endMonth - startMonth;
  if (endDay < startDay) months -= 1;
  if (months < 0) {
    months += 12;
    years -= 1;
  }
  return { months, years };
}

/** 天数差（end − start，日历日）。 */
function daysBetween(start: string, end: string): number {
  return (
    (Date.parse(`${end}T00:00:00`) - Date.parse(`${start}T00:00:00`)) /
    86_400_000
  );
}

function todayOf(options?: ComputeDerivedOptions): string {
  if (options?.today && DATE_RE.test(options.today)) return options.today;
  const now = new Date();
  const month = String(now.getMonth() + 1).padStart(2, '0');
  const day = String(now.getDate()).padStart(2, '0');
  return `${now.getFullYear()}-${month}-${day}`;
}

function ok(value: number | string): DerivedOutcome {
  return { message: null, status: 'ok', value };
}

function empty(status: DerivedStatus, message: string): DerivedOutcome {
  return { message, status, value: null };
}

/** 依赖图拓扑顺序（派生依赖派生先算；成环节点排除——防御跳过）；字段列表顺序保证稳定。 */
function topologicalOrder(defs: Map<string, DerivedDef>): string[] {
  const indegree = new Map<string, number>();
  const dependents = new Map<string, string[]>();
  for (const [key, def] of defs) {
    indegree.set(key, 0);
    for (const arg of def.args) {
      if (defs.has(arg) && arg !== key) {
        indegree.set(key, (indegree.get(key) ?? 0) + 1);
        dependents.set(arg, [...(dependents.get(arg) ?? []), key]);
      }
    }
  }
  const order: string[] = [];
  const ready = [...indegree.entries()]
    .filter(([, degree]) => degree === 0)
    .map(([key]) => key);
  for (const key of ready) {
    visit(key, indegree, dependents, order);
  }
  return order; // 未入序的即成环或依赖成环 → 跳过
}

/** 拓扑访问（深度优先遍历就绪节点；入度归零即入序，成环节点入度不归零被跳过）。 */
function visit(
  key: string,
  indegree: Map<string, number>,
  dependents: Map<string, string[]>,
  order: string[],
): void {
  order.push(key);
  for (const dependent of dependents.get(key) ?? []) {
    const degree = (indegree.get(dependent) ?? 0) - 1;
    indegree.set(dependent, degree);
    if (degree === 0) visit(dependent, indegree, dependents, order);
  }
}

/** {fn, args} 解析与参数个数校验（个数不符 / 坏定义 → null：不出结果，OQ-68 防御）。 */
function parseDef(def?: DerivedDef | null): DerivedDef | null {
  if (!def || typeof def !== 'object') return null;
  const fn = typeof def.fn === 'string' ? def.fn : '';
  const args = Array.isArray(def.args) ? def.args : null;
  if (!fn || !args || args.length === 0) return null;
  if (!args.every((arg) => typeof arg === 'string' && arg.length > 0))
    return null;
  return arityMatches(fn, args.length) ? { args: [...args], fn } : null;
}

/** 参数个数口径（与后端 FormDerivedEngine.parseDef 同一清单）：二参函数必须 2 参、可省止日函数 1–2 参、SUM ≥ 1。 */
function arityMatches(fn: string, count: number): boolean {
  switch (fn) {
    case 'AGE_YEARS':
    case 'DURATION_YM':
    case 'YEARS_SINCE': {
      return count === 1 || count === 2;
    }
    case 'BMI':
    case 'CHANGE_PCT':
    case 'DAYS_INCLUSIVE':
    case 'HOURS_BETWEEN_1DP':
    case 'MINUTES_BETWEEN': {
      return count === 2;
    }
    default: {
      return true; // SUM ≥ 1（args.length > 0 已保证）；未知函数放行到求值处统一不出结果
    }
  }
}

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const DATETIME_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/;

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
