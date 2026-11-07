/**
 * E17-T06 / A-FU-027（Q41）：医院 A 预置对比标红规则正反用例（前端执行器）。
 *
 * 同一份 backend/crdp-followup/src/test/resources/shared-cases/compare-cases-q41.json
 * （components.md §10.5）由后端 Q41CompareRuleCasesTest 与本文件各执行一遍：文件收录
 * followup V67 + V70 预置的 Q41 全量 17 条规则与每条的正例（命中）/ 反例（不命中），
 * 用例按 ruleCodes 引用预置定义（不复写阈值），命中逐项（ruleId / key / categoryKey /
 * message / previousValue / change / changeText）与后端一致；文件规则定义与迁移 SQL 的
 * 绑定由 crdp-server 的 ACompareRulePresetSqlTest.q41SharedCaseFileMatchesPresetRows
 * 锁定——改预置阈值不同步用例文件即两端一起失败。
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

interface Q41Rule extends CompareRuleInput {
  ruleCode: string;
}

interface Q41Case {
  current: Record<string, unknown>;
  expectedHits: ExpectedHit[];
  name: string;
  previous: null | {
    date?: null | string;
    kind?: 'BASELINE' | 'FOLLOWUP';
    values: Record<string, unknown>;
  };
  ruleCodes: string[];
}

interface Q41Suite {
  cases: Q41Case[];
  formCode: string;
  ordinals: Record<string, Record<string, number>>;
  rules: Q41Rule[];
}

/**
 * Q41 用例文件路径：按运行目录解析（happy-dom 环境下 import.meta.url 不保证是 file://）。
 * `pnpm test` 在 frontend/ 运行 → ../backend/...；在仓库根运行 → backend/...。
 */
const Q41_CASES_CANDIDATES = [
  resolve(
    process.cwd(),
    '../backend/crdp-followup/src/test/resources/shared-cases/compare-cases-q41.json',
  ),
  resolve(
    process.cwd(),
    'backend/crdp-followup/src/test/resources/shared-cases/compare-cases-q41.json',
  ),
];

function loadQ41Suite(): Q41Suite {
  const path = Q41_CASES_CANDIDATES.find((item) => existsSync(item));
  if (!path) {
    throw new Error(
      `找不到 Q41 用例文件：${Q41_CASES_CANDIDATES.join(' 或 ')}`,
    );
  }
  return JSON.parse(readFileSync(path, 'utf8')) as Q41Suite;
}

describe('e17-t06 Q41 预置对比标红规则正反用例（与后端同一份用例、同一结果）', () => {
  const suite = loadQ41Suite();
  const rulesByCode = new Map(suite.rules.map((rule) => [rule.ruleCode, rule]));

  const expand = (codes: string[]): CompareRuleInput[] => {
    return codes.map((code) => {
      const rule = rulesByCode.get(code);
      expect(rule, `用例引用了未预置的规则：${code}`).toBeTruthy();
      return rule as CompareRuleInput;
    });
  };

  it('预置规则 17 条（V67 两条 + V70 十五条），用例不少于正反各覆盖', () => {
    expect(suite.formCode).toBe('A_ANNUAL_FU');
    expect(suite.rules).toHaveLength(17);
    expect(suite.cases.length).toBeGreaterThanOrEqual(34); // 17 条 × 正反各一
  });

  it('全部用例：命中逐条与后端一致（ruleId / key / categoryKey / message / previousValue / change / changeText）', () => {
    for (const item of suite.cases) {
      const rules = expand(item.ruleCodes);
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
        suite.ordinals,
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

  it('每条规则至少 1 个正例（命中）与 1 个反例（参与求值但不命中）', () => {
    const positive = new Set<string>();
    const negative = new Set<string>();
    for (const item of suite.cases) {
      const hitIds = new Set(item.expectedHits.map((hit) => hit.ruleId));
      for (const code of item.ruleCodes) {
        if (hitIds.has(code)) {
          positive.add(code);
        } else {
          negative.add(code);
        }
      }
    }
    for (const rule of suite.rules) {
      expect(positive.has(rule.ruleCode), `${rule.ruleCode} 应有正例`).toBe(
        true,
      );
      expect(negative.has(rule.ruleCode), `${rule.ruleCode} 应有反例`).toBe(
        true,
      );
    }
  });
});
