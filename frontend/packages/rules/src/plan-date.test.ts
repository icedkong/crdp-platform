import type { ScheduleParams } from './plan-date';

import { describe, expect, it } from 'vitest';

import { addDateUnit, computeNodeSchedule } from './plan-date';

// A-FU-002、A-FU-016（偏移 / 提醒 / 窗口 / 周期在计划中设置）、医院 B 预置（hospital-a-spec §14）、
// 医院 C 节点表（hospital-c-spec §5）。pages.md §8：计算预览用本模块，与后端日期计算库（E14-T03）
// 共享用例——A 年度、B 3 个月、B 1 年、B 术后 1/3/5 天四个验算例；月末 / 闰年 / 跨年口径见
// fu_plan_node 迁移注释（V46：按月 / 年加遇月末取当月最后一天）。

describe('addDateUnit（起算日期 + 偏移；V46：按月 / 年加遇月末取当月最后一天）', () => {
  it('天与周（周 = 7 天）', () => {
    expect(addDateUnit('2026-03-01', 1, 'DAY')).toBe('2026-03-02');
    expect(addDateUnit('2026-03-01', 3, 'DAY')).toBe('2026-03-04');
    expect(addDateUnit('2026-03-01', 5, 'DAY')).toBe('2026-03-06');
    expect(addDateUnit('2026-03-01', 2, 'WEEK')).toBe('2026-03-15');
  });

  it('加月：小月到大月、跨年、遇月末取当月最后一天', () => {
    expect(addDateUnit('2026-03-01', 3, 'MONTH')).toBe('2026-06-01');
    expect(addDateUnit('2026-12-20', 1, 'MONTH')).toBe('2027-01-20');
    expect(addDateUnit('2025-01-31', 1, 'MONTH')).toBe('2025-02-28');
    expect(addDateUnit('2026-01-31', 1, 'MONTH')).toBe('2026-02-28');
  });

  it('加年：闰日加一年取平年 2 月最后一天；跨年', () => {
    expect(addDateUnit('2025-03-15', 1, 'YEAR')).toBe('2026-03-15');
    expect(addDateUnit('2024-02-29', 1, 'YEAR')).toBe('2025-02-28');
    expect(addDateUnit('2025-02-28', 1, 'YEAR')).toBe('2026-02-28');
    expect(addDateUnit('2026-11-05', 10, 'YEAR')).toBe('2036-11-05');
  });

  it('减去同样口径（提醒提前量、窗口前的计算基础）', () => {
    expect(addDateUnit('2026-06-01', -2, 'WEEK')).toBe('2026-05-18');
    expect(addDateUnit('2027-03-01', -1, 'MONTH')).toBe('2027-02-01');
    expect(addDateUnit('2026-03-31', -1, 'MONTH')).toBe('2026-02-28');
    expect(addDateUnit('2026-03-15', 0, 'DAY')).toBe('2026-03-15');
  });
});

/** A 医院（A-FU-002 / A-FU-017）：+1 年、提前 7 天、窗口前后 7 天。 */
const PLAN_A_ANNUAL: ScheduleParams = {
  offsetUnit: 'YEAR',
  offsetValue: 1,
  remindUnit: 'DAY',
  remindValue: 7,
  windowAfterUnit: 'DAY',
  windowAfterValue: 7,
  windowBeforeUnit: 'DAY',
  windowBeforeValue: 7,
};

/** 医院 B 3 个月节点（§14）：+3 个月、提前 2 周、前后 14 天。 */
const PLAN_B_3M: ScheduleParams = {
  offsetUnit: 'MONTH',
  offsetValue: 3,
  remindUnit: 'WEEK',
  remindValue: 2,
  windowAfterUnit: 'DAY',
  windowAfterValue: 14,
  windowBeforeUnit: 'DAY',
  windowBeforeValue: 14,
};

/** 医院 B 1 年节点（§14）：+1 年、提前 1 个月、前后 1 个月。 */
const PLAN_B_1Y: ScheduleParams = {
  offsetUnit: 'YEAR',
  offsetValue: 1,
  remindUnit: 'MONTH',
  remindValue: 1,
  windowAfterUnit: 'MONTH',
  windowAfterValue: 1,
  windowBeforeUnit: 'MONTH',
  windowBeforeValue: 1,
};

/** 医院 B 术后第 N 天节点（§14）：+N 天、当天提醒（0）、前 0 后 1 天。 */
function planBPostOpDay(days: number): ScheduleParams {
  return {
    offsetUnit: 'DAY',
    offsetValue: days,
    remindUnit: 'DAY',
    remindValue: 0,
    windowAfterUnit: 'DAY',
    windowAfterValue: 1,
    windowBeforeUnit: 'DAY',
    windowBeforeValue: 0,
  };
}

describe('computeNodeSchedule（pages.md §8 四个共享验算例 + C 节点表）', () => {
  it('医院 A 年度：出院 2025-03-15 → 计划 2026-03-15、开启日 2026-03-08（pages.md 示例原句）', () => {
    expect(computeNodeSchedule(PLAN_A_ANNUAL, '2025-03-15')).toEqual({
      openDate: '2026-03-08',
      planDate: '2026-03-15',
      remindStart: '2026-03-08',
      windowEnd: '2026-03-22',
      windowStart: '2026-03-08',
    });
  });

  it('医院 B 3 个月节点：手术 2026-03-01 → 计划 2026-06-01、2026-05-18 开启、窗口至 06-15（§14 示例）', () => {
    expect(computeNodeSchedule(PLAN_B_3M, '2026-03-01')).toEqual({
      openDate: '2026-05-18',
      planDate: '2026-06-01',
      remindStart: '2026-05-18',
      windowEnd: '2026-06-15',
      windowStart: '2026-05-18',
    });
  });

  it('医院 B 1 年节点：手术 2026-03-01 → 计划 2027-03-01、2027-02-01 开启、窗口至 04-01（§14 示例）', () => {
    expect(computeNodeSchedule(PLAN_B_1Y, '2026-03-01')).toEqual({
      openDate: '2027-02-01',
      planDate: '2027-03-01',
      remindStart: '2027-02-01',
      windowEnd: '2027-04-01',
      windowStart: '2027-02-01',
    });
  });

  it('医院 B 术后第 1 / 3 / 5 天：计划日期 03-02 / 03-04 / 03-06，窗口当日开启、次日出窗（§14 示例）', () => {
    expect(computeNodeSchedule(planBPostOpDay(1), '2026-03-01')).toEqual({
      openDate: '2026-03-02',
      planDate: '2026-03-02',
      remindStart: '2026-03-02',
      windowEnd: '2026-03-03',
      windowStart: '2026-03-02',
    });
    expect(computeNodeSchedule(planBPostOpDay(3), '2026-03-01').planDate).toBe(
      '2026-03-04',
    );
    expect(computeNodeSchedule(planBPostOpDay(5), '2026-03-01').planDate).toBe(
      '2026-03-06',
    );
  });

  it('医院 C 术后 72 小时：植入 2026-05-10 → 窗口 = 植入日至植入后 7 天（不对称窗口前 3 后 4）', () => {
    const result = computeNodeSchedule(
      {
        offsetUnit: 'DAY',
        offsetValue: 3,
        remindUnit: 'DAY',
        remindValue: 0,
        windowAfterUnit: 'DAY',
        windowAfterValue: 4,
        windowBeforeUnit: 'DAY',
        windowBeforeValue: 3,
      },
      '2026-05-10',
    );
    expect(result.planDate).toBe('2026-05-13');
    expect(result.windowStart).toBe('2026-05-10');
    expect(result.windowEnd).toBe('2026-05-17');
  });

  it('医院 C 常规随访滚动：基准上次实际随访（调用方传上次随访日期），+12 个月、前后 30 天', () => {
    const result = computeNodeSchedule(
      {
        offsetUnit: 'MONTH',
        offsetValue: 12,
        remindUnit: 'DAY',
        remindValue: 0,
        windowAfterUnit: 'DAY',
        windowAfterValue: 30,
        windowBeforeUnit: 'DAY',
        windowBeforeValue: 30,
      },
      '2026-06-20',
    );
    expect(result.planDate).toBe('2027-06-20');
    expect(result.windowStart).toBe('2027-05-21');
    expect(result.windowEnd).toBe('2027-07-20');
    expect(result.openDate).toBe('2027-05-21');
  });

  it('任务开启日 = 提醒开始日与窗口开始日中较早的一天（A-FU-016；提醒 0 时按窗口开始日）', () => {
    const result = computeNodeSchedule(
      {
        ...PLAN_A_ANNUAL,
        remindValue: 0,
        windowBeforeValue: 7,
      },
      '2025-03-15',
    );
    expect(result.remindStart).toBe('2026-03-15');
    expect(result.windowStart).toBe('2026-03-08');
    expect(result.openDate).toBe('2026-03-08');
  });

  it('提醒提前量单位可用周 / 月（A-FU-016：N 天 / 周 / 月）', () => {
    expect(
      computeNodeSchedule(
        { ...PLAN_A_ANNUAL, remindUnit: 'WEEK', remindValue: 1 },
        '2025-03-15',
      ).remindStart,
    ).toBe('2026-03-08');
    expect(
      computeNodeSchedule(
        { ...PLAN_A_ANNUAL, remindUnit: 'MONTH', remindValue: 1 },
        '2025-03-15',
      ).remindStart,
    ).toBe('2026-02-15');
  });
});

// E24-T07 真机兼容：Array.prototype.at（Chromium 92+）在微信 XWeb 等旧内核上
// 不存在，正则匹配数组的 matched.at(n) 会直接 TypeError——计划日期计算在
// 表单 / 计划页（响应式范围）真机上属阻断问题。删掉该原型方法模拟旧内核回归。
describe('e24-T07 真机内核下限：Array.prototype.at 缺失时计划日期仍可计算', () => {
  it('addDateUnit / computeNodeSchedule 在无 Array.prototype.at 环境正常工作', () => {
    const arrayAt = Array.prototype.at;
    // oxlint-disable-next-line no-extend-native -- 测试临时摘除原型方法模拟旧内核，finally 恢复
    Object.defineProperty(Array.prototype, 'at', {
      configurable: true,
      value: undefined,
    });
    try {
      expect(addDateUnit('2026-03-01', 3, 'MONTH')).toBe('2026-06-01');
      expect(computeNodeSchedule(PLAN_A_ANNUAL, '2025-03-15').planDate).toBe(
        '2026-03-15',
      );
    } finally {
      // oxlint-disable-next-line no-extend-native -- 测试临时摘除原型方法模拟旧内核，finally 恢复
      Object.defineProperty(Array.prototype, 'at', {
        configurable: true,
        value: arrayAt,
      });
    }
  });
});
