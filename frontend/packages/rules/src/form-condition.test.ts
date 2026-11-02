import type { ConditionalField, RequiredIssue } from './form-condition';

import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import {
  evaluateCondition,
  hiddenFieldKeys,
  isEmptyFormValue,
  isFieldVisible,
  isRequiredField,
  missingRequiredFields,
  stripHiddenFieldValues,
} from './form-condition';

/**
 * E11-T03 / A-FLD-004、C-FLD-005：条件 DSL（条件显示与条件必填）前端单测。
 *
 * 验收原文：前后端同一套用例结果一致。「一致」由共享用例锁定——同一份
 * backend/crdp-form/src/test/resources/shared-cases/form-condition-cases.json 由后端
 * FormConditionEngineTest 与本文件各执行一遍，visible / missingRequired 逐条
 * （key、categoryKey、rule、message）完全一致（components.md §10.5）。
 */

interface SharedCase {
  context?: Record<string, unknown>;
  data: Record<string, unknown>;
  expectedMissingRequired: RequiredIssue[];
  expectedVisible: string[];
  fields: ConditionalField[];
  name: string;
}

/**
 * 共享用例文件路径：按运行目录解析（happy-dom 环境下 import.meta.url 不保证是 file://）。
 * `pnpm test` 在 frontend/ 运行 → ../backend/...；在仓库根运行 → backend/...。
 */
const SHARED_CASES_CANDIDATES = [
  resolve(
    process.cwd(),
    '../backend/crdp-form/src/test/resources/shared-cases/form-condition-cases.json',
  ),
  resolve(
    process.cwd(),
    'backend/crdp-form/src/test/resources/shared-cases/form-condition-cases.json',
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

describe('e11-t03 共享用例（与后端 FormConditionEngine 同一份用例、同一结果）', () => {
  const cases = loadSharedCases();

  it('共享用例文件可读且不少于既有用例数（增删须两端同步）', () => {
    expect(cases.length).toBeGreaterThanOrEqual(27);
  });

  it('全部共享用例：visible / missingRequired 与后端逐条一致（用例名见断言消息）', () => {
    for (const item of cases) {
      const visible = item.fields
        .filter((field) => isFieldVisible(field, item.data, item.context))
        .map((field) => field.key);
      expect(visible, `${item.name} → visible`).toEqual(item.expectedVisible);
      expect(
        missingRequiredFields(item.fields, item.data, item.context),
        `${item.name} → missingRequired`,
      ).toEqual(item.expectedMissingRequired);
    }
  });
});

describe('e11-t03 条件求值边界语义（防御与口径）', () => {
  const data = { f010: 0 };

  it('条件为空 / null / 非法结构 / 残缺叶子恒真（不隐藏、不加必填）', () => {
    expect(evaluateCondition(null, data)).toBe(true);
    expect(evaluateCondition(undefined, data)).toBe(true);
    expect(evaluateCondition({} as never, data)).toBe(true);
    expect(evaluateCondition({ all: [{ field: 'f010' } as never] }, data)).toBe(
      true,
    );
    expect(
      evaluateCondition(
        {
          all: [
            { field: 'f010', ctx: 'device.type', op: 'eq', value: 1 } as never,
          ],
        },
        data,
      ),
    ).toBe(true);
    expect(
      evaluateCondition({ all: [{ op: 'eq', value: 1 } as never] }, data),
    ).toBe(true);
    expect(
      evaluateCondition(
        { all: [{ field: 'f010', op: 'like', value: 1 } as never] },
        data,
      ),
    ).toBe(true);
  });

  it('{all: []} 恒真、{any: []} 恒假；data / context 缺省不抛错', () => {
    expect(evaluateCondition({ all: [] }, data)).toBe(true);
    expect(evaluateCondition({ any: [] }, data)).toBe(false);
    expect(
      evaluateCondition({ all: [{ field: 'f010', op: 'eq', value: 1 }] }, null),
    ).toBe(false);
    expect(
      evaluateCondition(
        { all: [{ ctx: 'device.type', op: 'empty' }] },
        {},
        undefined,
      ),
    ).toBe(true);
  });

  it('ctx 点路径断链返回缺失（不成立）；取到对象而非标量不成立', () => {
    const context = { device: { type: 'ICD' } };
    expect(
      evaluateCondition(
        { any: [{ ctx: 'device.type', op: 'eq', value: 'ICD' }] },
        {},
        context,
      ),
    ).toBe(true);
    expect(
      evaluateCondition(
        { any: [{ ctx: 'device.mode', op: 'eq', value: 'ICD' }] },
        {},
        context,
      ),
    ).toBe(false);
    expect(
      evaluateCondition(
        { any: [{ ctx: 'device', op: 'eq', value: 'ICD' }] },
        {},
        context,
      ),
    ).toBe(false);
  });

  it('空值口径：0 / false / 部分已填的 pair_number 不算空', () => {
    expect(isEmptyFormValue(null)).toBe(true);
    expect(isEmptyFormValue(undefined)).toBe(true);
    expect(isEmptyFormValue('')).toBe(true);
    expect(isEmptyFormValue([])).toBe(true);
    expect(isEmptyFormValue({ length: null, width: undefined })).toBe(true);
    expect(isEmptyFormValue(0)).toBe(false);
    expect(isEmptyFormValue(false)).toBe(false);
    expect(isEmptyFormValue({ length: 30 })).toBe(false);
    expect(isEmptyFormValue([1, 2])).toBe(false);
    expect(isEmptyFormValue('否')).toBe(false);
  });

  it('有效必填 = 显示 &&（required || requiredWhen）；requiredWhen 为空不追加必填', () => {
    const trigger: ConditionalField = { key: 'f010', categoryKey: 'basic' };
    const conditional: ConditionalField = {
      key: 'f216',
      categoryKey: 'endpoint',
      visibleWhen: { all: [{ field: 'f010', op: 'eq', value: 1 }] },
      requiredWhen: { all: [{ field: 'f010', op: 'eq', value: 1 }] },
    };
    expect(isRequiredField(trigger, {})).toBe(false); // 无 requiredWhen 不追加必填
    expect(isRequiredField(conditional, { f010: 1 })).toBe(true);
    expect(isRequiredField(conditional, { f010: 0 })).toBe(false); // 隐藏字段永不必填
    // 隐藏字段连基础必填也不参与
    expect(
      isRequiredField({ ...conditional, required: true }, { f010: 0 }),
    ).toBe(false);
  });

  it('hiddenFieldKeys 与 stripHiddenFieldValues：只清空有值的隐藏字段，不改入参', () => {
    const fields: Array<Pick<ConditionalField, 'key' | 'visibleWhen'>> = [
      { key: 'f010' },
      {
        key: 'f012',
        visibleWhen: { all: [{ field: 'f010', op: 'eq', value: 1 }] },
      },
    ];
    const data = { f010: 0, f012: 300, f011: 170 };
    expect(hiddenFieldKeys(fields, data)).toEqual(['f012']);

    const stripped = stripHiddenFieldValues(fields, data);
    expect(stripped.clearedKeys).toEqual(['f012']);
    expect(stripped.data).toEqual({ f010: 0, f011: 170 });
    expect(data).toEqual({ f010: 0, f012: 300, f011: 170 }); // 不改入参

    // 隐藏但无值：不产生清空记录
    const empty = stripHiddenFieldValues(fields, { f010: 0, f012: null });
    expect(empty.clearedKeys).toEqual([]);
    expect(empty.data).toEqual({ f010: 0 });
  });
});
