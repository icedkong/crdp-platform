import type { ConditionTree } from './form-condition';
import type { DerivedOutcome } from './form-derived';
import type { CrossFieldRule, FieldIssue } from './form-validation';

import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import {
  isEmptyFormValue,
  isFieldVisible,
  stripHiddenFieldValues,
} from './form-condition';
import { computeDerivedValues } from './form-derived';
import { validateFormData } from './form-validation';

/**
 * E11-T10 / A-FLD-004、A-FLD-005：表单引擎条件、校验与派生标准用例（前端执行器）。
 *
 * 验收原文：前后端同一用例集一致。E11-T03 / E11-T04 / E11-T05 的三份共享用例各锁定
 * 单引擎两端一致；本文件执行综合标准用例——同一份
 * backend/crdp-form/src/test/resources/shared-cases/form-standard-cases.json 由后端
 * FormStandardCaseTest 与本文件各跑一遍，把三个引擎按提交流程组成标准管线
 * （components.md §10.5）：
 *
 * ① 条件求值（isFieldVisible）→ visible（字段声明序）；
 * ② 隐藏字段残留值清空（stripHiddenFieldValues，渲染器 cleared-hidden 同语义）→ clearedHidden；
 * ③ 派生在清空后的数据上按拓扑重算（computeDerivedValues，渲染器清空后实时重算同口径）→ derived；
 * ④ 校验在「清空 + ok 计算值回写」的数据上执行（validateFormData；结果无效且带手工值
 *    追加 errors rule=derived 在最后，与渲染器 hardErrors 组装、后端写入闸门同口径）→ errors / warnings。
 *
 * 组合顺序本身是被测语义：清空先于派生（隐藏来源按缺失计）、派生先于校验（计算值参与
 * 跨字段比较）、掩蔽先于跨字段（残留值不比较）。任何一端顺序不同都会与期望结果不符。
 */

/** 标准用例字段：三个引擎输入的最小并集（FormSchemaField 子集）。 */
interface StandardField {
  categoryKey: string;
  derived?: null | { args: string[]; fn: string };
  hardRange?: null | { max?: number; min?: number };
  key: string;
  label: string;
  required?: boolean;
  requiredWhen?: ConditionTree | null;
  softRange?: null | { max?: number; min?: number };
  storage?: string;
  type: string;
  visibleWhen?: ConditionTree | null;
}

interface StandardRule {
  categoryKey: string;
  id: string;
  key: string;
  message: string;
  params: { leftField: string; op: 'lt' | 'lte'; rightField: string };
  severity: 'HARD' | 'SOFT';
}

interface SharedCase {
  context?: Record<string, unknown>;
  data: Record<string, unknown>;
  expected: {
    clearedHidden: string[];
    derived: Array<{
      key: string;
      message: null | string;
      status: string;
      value: null | number | string;
    }>;
    errors: FieldIssue[];
    visible: string[];
    warnings: FieldIssue[];
  };
  fields: StandardField[];
  name: string;
  rules?: StandardRule[];
  today?: string;
}

/**
 * 共享用例文件路径：按运行目录解析（happy-dom 环境下 import.meta.url 不保证是 file://）。
 * `pnpm test` 在 frontend/ 运行 → ../backend/...；在仓库根运行 → backend/...。
 */
const SHARED_CASES_CANDIDATES = [
  resolve(
    process.cwd(),
    '../backend/crdp-form/src/test/resources/shared-cases/form-standard-cases.json',
  ),
  resolve(
    process.cwd(),
    'backend/crdp-form/src/test/resources/shared-cases/form-standard-cases.json',
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

describe('e11-t10 标准用例（与后端同一份用例、同一结果：条件 → 清空 → 派生 → 校验）', () => {
  const cases = loadSharedCases();

  it('标准用例文件可读且不少于既有用例数（增删须两端同步）', () => {
    expect(cases.length).toBeGreaterThanOrEqual(12);
  });

  it('全部标准用例：visible / clearedHidden / derived / errors / warnings 与后端逐条一致', () => {
    for (const item of cases) {
      const fields = item.fields;
      const context = item.context;

      // ① 条件求值 → visible（字段声明序）
      const visible = fields
        .filter((field) => isFieldVisible(field, item.data, context))
        .map((field) => field.key);
      expect(visible, `${item.name} → visible`).toEqual(item.expected.visible);

      // ② 隐藏字段残留值清空（渲染器 cleared-hidden 同语义）
      const stripped = stripHiddenFieldValues(fields, item.data, context);
      expect(
        stripped.clearedKeys,
        `${item.name} → clearedHidden（只含有值的隐藏键）`,
      ).toEqual(item.expected.clearedHidden);

      // ③ 派生在清空后的数据上重算（today 固定日期保证两端确定性）
      const outcomes = computeDerivedValues(
        fields,
        stripped.data,
        item.today ? { today: item.today } : undefined,
      );
      expect(
        Object.keys(outcomes),
        `${item.name} → 派生结果键与顺序（只含派生字段）`,
      ).toEqual(item.expected.derived.map((expected) => expected.key));
      for (const expected of item.expected.derived) {
        const outcome: DerivedOutcome = outcomes[expected.key];
        expect(outcome, `${item.name} → ${expected.key}`).toEqual({
          message: expected.message,
          status: expected.status,
          value: expected.value,
        });
      }

      // ④ 校验在「清空 + ok 计算值回写」的数据上执行（写入闸门同口径）
      const effective: Record<string, unknown> = { ...stripped.data };
      for (const [key, outcome] of Object.entries(outcomes)) {
        if (outcome.status === 'ok') {
          effective[key] = outcome.value;
        }
      }
      const validation = validateFormData(
        fields,
        (item.rules ?? []) as CrossFieldRule[],
        effective,
        context,
      );
      const errors: FieldIssue[] = [...validation.errors];
      for (const field of fields) {
        const outcome = outcomes[field.key];
        if (!outcome || outcome.status !== 'invalid') continue;
        if (!isEmptyFormValue(effective[field.key])) {
          errors.push({
            key: field.key,
            categoryKey: field.categoryKey,
            rule: 'derived',
            message: outcome.message ?? '',
          });
        }
      }
      expect(errors, `${item.name} → errors`).toEqual(item.expected.errors);
      expect(validation.warnings, `${item.name} → warnings`).toEqual(
        item.expected.warnings,
      );
    }
  });
});
