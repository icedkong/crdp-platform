import type { ScheduleParams } from './plan-date';

import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { addDateUnit, computeNodeSchedule } from './plan-date';

/**
 * E14-T10 / A-FU-002、A-FU-016、A-NFR-006：计划引擎日期计算共享用例（前端执行器）。
 *
 * 同一份 backend/crdp-followup/src/test/resources/shared-cases/plan-date-cases.json
 * （components.md §10.5）由后端 PlanDateCalculator（PlanDateSharedCasesTest）与本文件
 * 各执行一遍，期望结果逐条一致（跨年、闰年、月末钳制、窗口与开启日 = min）。
 * occurrenceCases（持续循环第 N 次）仅后端执行：计算预览只算第 1 次，循环推进在
 * 调度端（E14-T04）。
 */

/** 单位运算用例（addDateUnitCases[]）：跨年 / 闰年 / 月末钳制。 */
interface AddDateUnitCase {
  base: string;
  expected: string;
  name: string;
  unit: 'DAY' | 'MONTH' | 'WEEK' | 'YEAR';
  value: number;
}

/** fu_plan_node 日期参数（fu_plan_node 的偏移 / 提醒 / 窗口列，同 NodeTiming）。 */
interface CaseTiming {
  offsetUnit: 'DAY' | 'MONTH' | 'WEEK' | 'YEAR';
  offsetValue: number;
  remindUnit: 'DAY' | 'MONTH' | 'WEEK';
  remindValue: number;
  windowAfterUnit: 'DAY' | 'MONTH' | 'WEEK' | 'YEAR';
  windowAfterValue: number;
  windowBeforeUnit: 'DAY' | 'MONTH' | 'WEEK' | 'YEAR';
  windowBeforeValue: number;
}

/** 完整排期用例（scheduleCases[]）：计划日期、提醒开始日、窗口开始 / 结束日、开启日。 */
interface ScheduleCase {
  basis: string;
  expected: {
    openDate: string;
    planDate: string;
    remindStart: string;
    windowEnd: string;
    windowStart: string;
  };
  name: string;
  timing: CaseTiming;
}

/**
 * 共享用例文件路径：按运行目录解析（happy-dom 环境下 import.meta.url 不保证是 file://）。
 * `pnpm test` 在 frontend/ 运行 → ../backend/...；在仓库根运行 → backend/...。
 */
const SHARED_CASES_CANDIDATES = [
  resolve(
    process.cwd(),
    '../backend/crdp-followup/src/test/resources/shared-cases/plan-date-cases.json',
  ),
  resolve(
    process.cwd(),
    'backend/crdp-followup/src/test/resources/shared-cases/plan-date-cases.json',
  ),
];

function loadSharedCases(): {
  addDateUnitCases: AddDateUnitCase[];
  scheduleCases: ScheduleCase[];
} {
  const path = SHARED_CASES_CANDIDATES.find((item) => existsSync(item));
  if (!path) {
    throw new Error(
      `找不到前后端共享用例文件：${SHARED_CASES_CANDIDATES.join(' 或 ')}`,
    );
  }
  return JSON.parse(readFileSync(path, 'utf8')) as {
    addDateUnitCases: AddDateUnitCase[];
    scheduleCases: ScheduleCase[];
  };
}

/** 共享用例的 timing → 计算预览入参（字段一一对应，仅声明序不同）。 */
function toScheduleParams(timing: CaseTiming): ScheduleParams {
  return {
    offsetUnit: timing.offsetUnit,
    offsetValue: timing.offsetValue,
    remindUnit: timing.remindUnit,
    remindValue: timing.remindValue,
    windowAfterUnit: timing.windowAfterUnit,
    windowAfterValue: timing.windowAfterValue,
    windowBeforeUnit: timing.windowBeforeUnit,
    windowBeforeValue: timing.windowBeforeValue,
  };
}

describe('e14-t10 日期计算共享用例（与后端同一份用例、同一结果：跨年 / 闰年 / 窗口）', () => {
  const file = loadSharedCases();

  it('共享用例文件可读且两组用例数与后端断言一致（增删须两端同步）', () => {
    expect(file.addDateUnitCases.length).toBe(19);
    expect(file.scheduleCases.length).toBe(16);
  });

  it('addDateUnitCases：单位运算与后端逐条一致（跨年 / 闰年 / 月末钳制）', () => {
    for (const item of file.addDateUnitCases) {
      expect(
        addDateUnit(item.base, item.value, item.unit),
        `${item.name} → addDateUnit`,
      ).toBe(item.expected);
    }
  });

  it('scheduleCases：计划日期、提醒开始日、窗口与开启日与后端逐条一致', () => {
    for (const item of file.scheduleCases) {
      const result = computeNodeSchedule(
        toScheduleParams(item.timing),
        item.basis,
      );
      expect(result.planDate, `${item.name} → planDate`).toBe(
        item.expected.planDate,
      );
      expect(result.remindStart, `${item.name} → remindStart`).toBe(
        item.expected.remindStart,
      );
      expect(result.windowStart, `${item.name} → windowStart`).toBe(
        item.expected.windowStart,
      );
      expect(result.windowEnd, `${item.name} → windowEnd`).toBe(
        item.expected.windowEnd,
      );
      expect(
        result.openDate,
        `${item.name} → openDate = min(remindStart, windowStart)`,
      ).toBe(item.expected.openDate);
    }
  });
});
