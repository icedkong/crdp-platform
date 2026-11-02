import type { DerivedOutcome, DerivedSourceField } from './form-derived';

import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import {
  computeDerivedValues,
  derivedManualKind,
  isDerivedManualEditable,
} from './form-derived';

/**
 * E11-T05 / A-FLD-005、C-FLD-006：内置派生函数库前端单测。
 *
 * 验收原文：BMI、年龄等派生字段两端一致。「一致」由共享用例锁定——同一份
 * backend/crdp-form/src/test/resources/shared-cases/form-derived-cases.json 由后端
 * FormDerivedEngineTest 与本文件各执行一遍，value / status / message 逐条一致
 * （components.md §10.5）。
 */

interface SharedCase {
  data: Record<string, unknown>;
  expected: Array<{
    key: string;
    message: null | string;
    status: string;
    value: null | number | string;
  }>;
  fields: DerivedSourceField[];
  name: string;
  today?: string;
}

/**
 * 共享用例文件路径：按运行目录解析（happy-dom 环境下 import.meta.url 不保证是 file://）。
 * `pnpm test` 在 frontend/ 运行 → ../backend/...；在仓库根运行 → backend/...。
 */
const SHARED_CASES_CANDIDATES = [
  resolve(
    process.cwd(),
    '../backend/crdp-form/src/test/resources/shared-cases/form-derived-cases.json',
  ),
  resolve(
    process.cwd(),
    'backend/crdp-form/src/test/resources/shared-cases/form-derived-cases.json',
  ),
];

function loadSharedCases(): SharedCase[] {
  const path = SHARED_CASES_CANDIDATES.find((item) => existsSync(item));
  if (!path) {
    throw new Error(
      `找不到前后端共享用例文件：${SHARED_CASES_CANDIDATES.join(' 或 ')}`,
    );
  }
  const file = JSON.parse(readFileSync(path, 'utf8')) as {
    cases: SharedCase[];
  };
  return file.cases;
}

describe('e11-t05 共享用例（与后端 FormDerivedEngine 同一份用例、同一结果）', () => {
  const cases = loadSharedCases();

  it('共享用例文件可读且不少于既有用例数（增删须两端同步）', () => {
    expect(cases.length).toBeGreaterThanOrEqual(33);
  });

  it('全部共享用例：value / status / message 与后端逐条一致（用例名见断言消息）', () => {
    for (const item of cases) {
      const outcomes = computeDerivedValues(
        item.fields,
        item.data,
        item.today ? { today: item.today } : undefined,
      );
      expect(
        Object.keys(outcomes),
        `${item.name} → 结果键与顺序（只含派生字段）`,
      ).toEqual(item.expected.map((expected) => expected.key));
      for (const expected of item.expected) {
        const outcome: DerivedOutcome = outcomes[expected.key];
        expect(outcome, `${item.name} → ${expected.key}`).toEqual({
          message: expected.message,
          status: expected.status,
          value: expected.value,
        });
      }
    }
  });
});

describe('e11-t05 派生函数库口径（渲染器接入用）', () => {
  const height: DerivedSourceField = {
    categoryKey: 'basic_info',
    key: 'f011',
    label: '身高',
    type: 'number',
  };
  const weight: DerivedSourceField = {
    categoryKey: 'basic_info',
    key: 'f013',
    label: '体重',
    type: 'number',
  };
  const bmi: DerivedSourceField = {
    categoryKey: 'basic_info',
    derived: { args: ['f013', 'f011'], fn: 'BMI' },
    key: 'f031',
    label: 'BMI',
    type: 'derived',
  };

  it('来源缺失 → 允许手工录入（渲染器放开编辑并标 meta.manual）；ok / invalid 不可手工', () => {
    expect(
      isDerivedManualEditable(
        computeDerivedValues([height, weight, bmi], {}).f031,
      ),
    ).toBe(true);
    expect(
      isDerivedManualEditable(
        computeDerivedValues([height, weight, bmi], { f011: 175, f013: 70 })
          .f031,
      ),
    ).toBe(false);
    expect(
      isDerivedManualEditable(
        computeDerivedValues([height, weight, bmi], { f011: 0, f013: 70 }).f031,
      ),
    ).toBe(false);
  });

  it('手工录入控件类型：DURATION_YM 文本、其余数值', () => {
    expect(derivedManualKind('DURATION_YM')).toBe('text');
    expect(derivedManualKind('BMI')).toBe('number');
  });

  it('today 缺省按浏览器当天（AGE_YEARS 无参考字段）', () => {
    const birth: DerivedSourceField = {
      categoryKey: 'basic_info',
      key: 'f003',
      label: '出生日期',
      type: 'date',
    };
    const age: DerivedSourceField = {
      categoryKey: 'followup',
      derived: { args: ['f003'], fn: 'AGE_YEARS' },
      key: 'f103',
      label: '年龄',
      type: 'derived',
    };
    const today = new Date();
    const month = String(today.getMonth() + 1).padStart(2, '0');
    const day = String(today.getDate()).padStart(2, '0');
    const birth45 = `${today.getFullYear() - 45}-${month}-${day}`;
    expect(
      computeDerivedValues([birth, age], { f003: birth45 }).f103.value,
    ).toBe(45);
  });

  it('防御：无派生字段 / data 为 null → 空结果；不可解析来源按缺失计', () => {
    expect(computeDerivedValues([height], null)).toEqual({});
    expect(
      computeDerivedValues([height, weight, bmi], {
        f011: 175,
        f013: '七十公斤',
      }).f031,
    ).toEqual({ message: '缺少体重', status: 'missing', value: null });
  });
});
