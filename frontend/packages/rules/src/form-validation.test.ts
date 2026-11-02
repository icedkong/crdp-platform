import type {
  CrossFieldRule,
  FieldIssue,
  ValidatableField,
} from './form-validation';

import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { compareValues, validateFormData } from './form-validation';

/**
 * E11-T04 / A-FLD-003、A-FLD-006、C-FLD-007：表单校验前端单测。
 *
 * 验收原文：前后端校验一致，超范围拦截。「一致」由共享用例锁定——同一份
 * backend/crdp-form/src/test/resources/shared-cases/form-validation-cases.json 由后端
 * FormRuleEngineTest 与本文件各执行一遍，errors / warnings 逐条（key、categoryKey、rule、message）
 * 完全一致（components.md §10.5）。
 */

interface SharedCase {
  data: Record<string, unknown>;
  expectedErrors: FieldIssue[];
  expectedWarnings: FieldIssue[];
  fields: ValidatableField[];
  name: string;
  rules: CrossFieldRule[];
}

/**
 * 共享用例文件路径：按运行目录解析（happy-dom 环境下 import.meta.url 不保证是 file://）。
 * `pnpm test` 在 frontend/ 运行 → ../backend/...；在仓库根运行 → backend/...。
 */
const SHARED_CASES_CANDIDATES = [
  resolve(
    process.cwd(),
    '../backend/crdp-form/src/test/resources/shared-cases/form-validation-cases.json',
  ),
  resolve(
    process.cwd(),
    'backend/crdp-form/src/test/resources/shared-cases/form-validation-cases.json',
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

describe('e11-t04 共享用例（与后端 FormRuleEngine 同一份用例、同一结果）', () => {
  const cases = loadSharedCases();

  it('共享用例文件可读且不少于既有用例数（增删须两端同步）', () => {
    expect(cases.length).toBeGreaterThanOrEqual(19);
  });

  it('全部共享用例：errors / warnings 与后端逐条一致（含文案，用例名见断言消息）', () => {
    for (const item of cases) {
      const result = validateFormData(item.fields, item.rules, item.data);
      expect(result.errors, `${item.name} → errors`).toEqual(
        item.expectedErrors,
      );
      expect(result.warnings, `${item.name} → warnings`).toEqual(
        item.expectedWarnings,
      );
    }
  });
});

describe('e11-t04 validateFormData 边界语义', () => {
  const height: ValidatableField = {
    key: 'f011',
    categoryKey: 'basic_info',
    type: 'number',
    hardRange: { min: 50, max: 250 },
    softRange: { min: 130, max: 210 },
  };

  it('空数据 / null 数据不产生任何命中', () => {
    expect(validateFormData([height], [], {})).toEqual({
      errors: [],
      warnings: [],
    });
    expect(validateFormData([height], [], undefined)).toEqual({
      errors: [],
      warnings: [],
    });
    expect(validateFormData([height], [], null)).toEqual({
      errors: [],
      warnings: [],
    });
  });

  it('非数值类型字段不查范围（文本 / 日期 / 派生）', () => {
    const fields: ValidatableField[] = [
      {
        key: 'f001',
        categoryKey: 'basic',
        type: 'text',
        hardRange: { min: 1 },
      },
      {
        key: 'f003',
        categoryKey: 'basic',
        type: 'date',
        hardRange: { min: 1 },
      },
      {
        key: 'f070',
        categoryKey: 'lab',
        type: 'derived',
        hardRange: { min: 10, max: 60 },
      },
    ];
    const result = validateFormData(fields, [], {
      f001: '文字（示例）',
      f003: '2024-05-06',
      f070: 999,
    });
    expect(result).toEqual({ errors: [], warnings: [] });
  });

  it('pair_number 缺失分量只查已填分量；空对象无命中', () => {
    const pair: ValidatableField = {
      key: 'f060',
      categoryKey: 'echo',
      type: 'pair_number',
      hardRange: { min: 5, max: 250 },
    };
    expect(validateFormData([pair], [], { f060: { length: 60 } })).toEqual({
      errors: [],
      warnings: [],
    });
    expect(
      validateFormData([pair], [], { f060: { length: 60, width: 300 } }).errors,
    ).toEqual([
      {
        key: 'f060',
        categoryKey: 'echo',
        rule: 'hardRange',
        message: '取值应在 5–250 之间',
      },
    ]);
    expect(validateFormData([pair], [], { f060: {} })).toEqual({
      errors: [],
      warnings: [],
    });
  });

  it('规则参数残缺（缺字段或 op 不认识）不触发、不抛错', () => {
    const broken = {
      ...baseRule(),
      id: 'broken',
      params: { leftField: 'f020', op: 'gt', rightField: 'f003' },
    } as CrossFieldRule;
    expect(
      validateFormData(
        [],
        [broken, { ...baseRule(), params: {} as CrossFieldRule['params'] }],
        { f003: '2024-05-06', f020: '2024-05-01' },
      ),
    ).toEqual({ errors: [], warnings: [] });
  });
});

describe('e11-t04 compareValues（值编码口径比较）', () => {
  it('数值比较与相等', () => {
    expect(compareValues(110, 100)).toBe(1);
    expect(compareValues(100, 110)).toBe(-1);
    expect(compareValues(1.5, 1.5)).toBe(0);
  });

  it('日期按当日 00:00 归一，分钟 / 秒两种写法等价', () => {
    expect(compareValues('2024-05-06', '2024-05-06')).toBe(0);
    expect(compareValues('2024-05-06', '2024-05-07')).toBe(-1);
    expect(compareValues('2024-05-06T08:30', '2024-05-06T08:30:00')).toBe(0);
    expect(compareValues('2024-05-06', '2024-05-06T00:00')).toBe(0);
    expect(compareValues('2024-05-06', '2024-05-06T08:30')).toBe(-1);
  });

  it('不可比返回 null：数值 × 日期、普通文本、非法日期、一侧为空', () => {
    expect(compareValues(170, '2024-05-06')).toBeNull();
    expect(compareValues('文字（示例）', '2024-05-06')).toBeNull();
    expect(compareValues('2024-02-30', '2024-05-06')).toBeNull();
    expect(compareValues(undefined, '2024-05-06')).toBeNull();
    expect(compareValues('2024-05-06', null)).toBeNull();
  });
});

function baseRule(): CrossFieldRule {
  return {
    id: 'birth_le_surgery',
    severity: 'HARD',
    categoryKey: 'surgery',
    key: 'f020',
    params: { leftField: 'f003', op: 'lte', rightField: 'f020' },
    message: '手术日期不能早于出生日期（示例）',
  };
}
