/**
 * E17-T03 / A-FU-027（Q41）：对比标红求值共享用例（前端执行器）。
 *
 * 同一份 backend/crdp-followup/src/test/resources/shared-cases/compare-cases.json
 * （components.md §10.5「对比（E17）沿用同一目录」）由后端 CompareEvaluator
 * （CompareEvaluatorSharedCasesTest）与本文件各执行一遍，命中逐条
 * （ruleId / key / categoryKey / message / previousValue / change / changeText）一致。
 * 用例取自需求验收口径：EF 56 → 45 下降 11 → 标红；上一次为空跳过；返流加重 ≥ 2 级或
 * 达到中量 / 大量；新发事件日期晚于上一次随访；超出提示范围 EF ＜ 40%（A-FU-027）。
 */

import type {
  CompareHit,
  ComparePrevious,
  CompareRuleInput,
} from './form-compare';

import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { evaluateCompareRules } from './form-compare';

interface ExpectedHit {
  categoryKey: string;
  change: null | number;
  changeText: null | string;
  key: string;
  message: string;
  previousValue: null | number | string;
  ruleId: string;
}

interface SharedCase {
  current: Record<string, unknown>;
  expectedHits: ExpectedHit[];
  name: string;
  ordinals: Record<string, Record<string, number>>;
  previous: null | {
    date?: null | string;
    kind?: 'BASELINE' | 'FOLLOWUP';
    values: Record<string, unknown>;
  };
  rules: Array<{
    baselineField?: null | string;
    categoryKey: string;
    message: string;
    params: Record<string, unknown>;
    ruleCode: string;
    ruleType: string;
    targetField: string;
    unit?: null | string;
  }>;
}

/**
 * 共享用例文件路径：按运行目录解析（happy-dom 环境下 import.meta.url 不保证是 file://）。
 * `pnpm test` 在 frontend/ 运行 → ../backend/...；在仓库根运行 → backend/...。
 */
const SHARED_CASES_CANDIDATES = [
  resolve(
    process.cwd(),
    '../backend/crdp-followup/src/test/resources/shared-cases/compare-cases.json',
  ),
  resolve(
    process.cwd(),
    'backend/crdp-followup/src/test/resources/shared-cases/compare-cases.json',
  ),
];

function loadSharedCases(): SharedCase[] {
  const path = SHARED_CASES_CANDIDATES.find((item) => existsSync(item));
  if (!path) {
    throw new Error(
      `找不到前后端共享用例文件：${SHARED_CASES_CANDIDATES.join(' 或 ')}`,
    );
  }
  return (JSON.parse(readFileSync(path, 'utf8')) as { cases: SharedCase[] })
    .cases;
}

describe('e17-t03 对比标红求值（与后端同一份用例、同一结果）', () => {
  const cases = loadSharedCases();

  it('共享用例文件可读且不少于 20 条（增删须两端同步）', () => {
    expect(cases.length).toBeGreaterThanOrEqual(20);
  });

  it('全部用例：命中逐条与后端一致（ruleId / key / categoryKey / message / previousValue / change / changeText）', () => {
    for (const item of cases) {
      const rules = item.rules as CompareRuleInput[];
      const previous: ComparePrevious | null = item.previous
        ? {
            date: item.previous.date ?? null,
            kind: item.previous.kind,
            values: item.previous.values,
          }
        : null;
      const hits: CompareHit[] = evaluateCompareRules(
        rules,
        item.current,
        previous,
        item.ordinals,
      );
      expect(hits, `${item.name} → 命中条数`).toHaveLength(
        item.expectedHits.length,
      );
      for (const [index, expected] of item.expectedHits.entries()) {
        const hit = hits[index];
        expect(hit, `${item.name} → 第 ${index + 1} 条命中`).toEqual({
          ruleId: expected.ruleId,
          key: expected.key,
          categoryKey: expected.categoryKey,
          message: expected.message,
          previousValue: expected.previousValue,
          change: expected.change,
          changeText: expected.changeText,
        });
      }
    }
  });
});
