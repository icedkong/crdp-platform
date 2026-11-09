/**
 * E14-T02 / A-FU-002、A-FU-016：计划节点日期计算（计划日期、提醒开始日、窗口、任务开启日）。
 * pages.md §8：计划配置页的「计算预览」用本模块实时计算，与后端日期计算库（E14-T03）共享用例
 * （A 年度、B 3 个月、B 1 年、B 术后 1/3/5 天）。口径与 fu_plan_node 迁移（V46）一致：
 * 按月 / 年加遇月末取当月最后一天；业务日期为北京时间 LocalDate，这里用 'YYYY-MM-DD' 字符串
 * 纯函数计算，不引第三方日期库（与后端 LocalDate.plus 同口径）。
 */

/** 偏移与窗口可用的单位（fu_plan_node.offset_unit / window_*_unit）。 */
export type OffsetUnit = 'DAY' | 'MONTH' | 'WEEK' | 'YEAR';

/** 提醒提前量单位（A-FU-016：N 天 / 周 / 月）。 */
export type RemindUnit = 'DAY' | 'MONTH' | 'WEEK';

const ISO_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

const DAYS_IN_MONTH = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

function daysInMonth(year: number, month: number): number {
  if (month === 2 && isLeapYear(year)) {
    return 29;
  }
  return DAYS_IN_MONTH[month - 1] ?? 0;
}

function parseIsoDate(value: string): {
  day: number;
  month: number;
  year: number;
} {
  const matched = ISO_DATE.exec(value);
  if (!matched) {
    throw new Error(`日期格式应为 YYYY-MM-DD：${value}`);
  }
  // E24-T07：下标取组替代 matched.at(n)（微信 XWeb 等旧内核无 Array.prototype.at）
  const year = Number.parseInt(matched[1] ?? '', 10);
  const month = Number.parseInt(matched[2] ?? '', 10);
  const day = Number.parseInt(matched[3] ?? '', 10);
  if (month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month)) {
    throw new Error(`日期不合法：${value}`);
  }
  return { day, month, year };
}

function toEpochDay(year: number, month: number, day: number): number {
  // 与 java.time.LocalDate.toEpochDay 同构的民用历换算（1970-01-01 = 0）。
  const y = month <= 2 ? year - 1 : year;
  const m = month <= 2 ? month + 12 : month;
  const era = Math.floor(y / 400);
  const yearOfEra = y - era * 400;
  const dayOfYear = Math.floor((153 * (m - 3) + 2) / 5) + day - 1;
  const daysOfEra =
    yearOfEra * 365 + Math.floor(yearOfEra / 4) - Math.floor(yearOfEra / 100);
  return era * 146_097 + daysOfEra + dayOfYear - 719_468;
}

function fromEpochDay(epochDay: number): {
  day: number;
  month: number;
  year: number;
} {
  const zeroDay = epochDay + 719_468;
  const era = Math.floor(zeroDay / 146_097);
  let dayOfEra = zeroDay - era * 146_097;
  const yearOfEra = Math.floor(
    (dayOfEra -
      Math.floor(dayOfEra / 1460) +
      Math.floor(dayOfEra / 36_524) -
      Math.floor(dayOfEra / 146_096)) /
      365,
  );
  dayOfEra =
    dayOfEra -
    (yearOfEra * 365 + Math.floor(yearOfEra / 4) - Math.floor(yearOfEra / 100));
  const marchMonth = Math.floor((5 * dayOfEra + 2) / 153);
  const day = dayOfEra - Math.floor((153 * marchMonth + 2) / 5) + 1;
  const month = marchMonth + 3 - 12 * Math.floor(marchMonth / 10);
  const year = yearOfEra + era * 400 + Math.floor(marchMonth / 10);
  return { day, month, year };
}

function formatIso(parts: {
  day: number;
  month: number;
  year: number;
}): string {
  const y = String(parts.year).padStart(4, '0');
  const m = String(parts.month).padStart(2, '0');
  const d = String(parts.day).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

/**
 * 起算日期 + 偏移（value 可为负，用于减提醒提前量 / 窗口前）。
 * 月 / 年加遇月末取当月最后一天（V46 注释、E14-T03 同口径）：1-31 + 1 月 = 2-28。
 */
export function addDateUnit(
  base: string,
  value: number,
  unit: OffsetUnit | RemindUnit,
): string {
  const { day, month, year } = parseIsoDate(base);
  if (unit === 'DAY') {
    return formatIso(fromEpochDay(toEpochDay(year, month, day) + value));
  }
  if (unit === 'WEEK') {
    return formatIso(fromEpochDay(toEpochDay(year, month, day) + value * 7));
  }
  const months = unit === 'MONTH' ? value : value * 12;
  const total = year * 12 + (month - 1) + months;
  const targetYear = Math.floor(total / 12);
  const targetMonth = (total % 12) + 1;
  const targetDay = Math.min(day, daysInMonth(targetYear, targetMonth));
  return formatIso({ day: targetDay, month: targetMonth, year: targetYear });
}

/** 计算预览入参（fu_plan_node 的偏移 / 提醒 / 窗口列；基准由调用方决定传起算日期还是上次随访日期）。 */
export interface ScheduleParams {
  offsetUnit: OffsetUnit;
  offsetValue: number;
  remindUnit: RemindUnit;
  remindValue: number;
  windowAfterUnit: OffsetUnit;
  windowAfterValue: number;
  windowBeforeUnit: OffsetUnit;
  windowBeforeValue: number;
}

/** 计算预览结果（pages.md §8：计划日期、窗口、开启日实时计算展示）。 */
export interface NodeSchedule {
  /** 任务开启日 = 提醒开始日与窗口开始日中较早的一天（A-FU-016）。 */
  openDate: string;
  /** 计划日期 = 起算日期 + 偏移。 */
  planDate: string;
  /** 提醒开始日 = 计划日期 − 提醒提前量（0 = 当天开始提醒）。 */
  remindStart: string;
  /** 窗口结束日 = 计划日期 + 窗口后。 */
  windowEnd: string;
  /** 窗口开始日 = 计划日期 − 窗口前。 */
  windowStart: string;
}

/**
 * 按节点参数与示例起算日期（或上次实际随访日期）计算一次排期。
 * 基准 PLAN_START / LAST_VISIT 的区别只是调用方传哪个日期（A-FU-016）。
 */
export function computeNodeSchedule(
  params: ScheduleParams,
  startDate: string,
): NodeSchedule {
  const planDate = addDateUnit(
    startDate,
    params.offsetValue,
    params.offsetUnit,
  );
  const remindStart = addDateUnit(
    planDate,
    -params.remindValue,
    params.remindUnit,
  );
  const windowStart = addDateUnit(
    planDate,
    -params.windowBeforeValue,
    params.windowBeforeUnit,
  );
  const windowEnd = addDateUnit(
    planDate,
    params.windowAfterValue,
    params.windowAfterUnit,
  );
  // ISO 日期字符串按字典序即时间序比较；Math.min 会把字符串转数字（NaN），这里用显式比较
  let openDate = remindStart;
  if (windowStart < openDate) {
    openDate = windowStart;
  }
  return { openDate, planDate, remindStart, windowEnd, windowStart };
}
