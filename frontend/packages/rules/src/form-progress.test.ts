import type { ConditionTree } from './form-condition';

import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

import { describe, expect, it } from 'vitest';

import { computeCategoryProgress } from './form-progress';

/**
 * E11-T11 / A-PAT-021、C-PAT-018（口径 A-PAT-019）：表单引擎「已填 / 应填」进度用例（前端执行器）。
 *
 * 验收原文：前后端结果一致。同一份
 * backend/crdp-form/src/test/resources/shared-cases/form-progress-cases.json 由后端
 * FormProgressCaseTest 与本文件各跑一遍，每个分类的 key / label / filled / total 与输出
 * 顺序逐条一致（components.md §10.5）。
 *
 * 口径（form.md §1.3）：应填 total = 显示中（visibleWhen 求值）且有效必填
 * （required || requiredWhen 成立）且 storage=json 且非派生的字段；已填 filled = 其中
 * 值非空的字段（空值口径与条件 DSL 同：null / 缺键 / 空串 / 空数组 / 分量全缺的
 * pair_number 对象为空，0 与 false 是已答）；条件不显示的字段不计入分母
 * （A-PAT-019「因条件不满足而不显示的字段不计入分母」）。
 */

/** 进度用例字段（FormSchemaField 子集）。 */
interface ProgressField {
  categoryKey: string;
  key: string;
  required?: boolean;
  requiredWhen?: ConditionTree | null;
  storage?: string;
  type: string;
  visibleWhen?: ConditionTree | null;
}

interface SharedCase {
  categories?: Array<{
    key: string;
    label?: null | string;
    sortNo?: number;
  }>;
  context?: Record<string, unknown>;
  data: Record<string, unknown>;
  expected: Array<{
    filled: number;
    key: string;
    label: string;
    total: number;
  }>;
  fields: ProgressField[];
  name: string;
}

/**
 * 共享用例文件路径：按运行目录解析（happy-dom 环境下 import.meta.url 不保证是 file://）。
 * `pnpm test` 在 frontend/ 运行 → ../backend/...；在仓库根运行 → backend/...。
 */
const SHARED_CASES_CANDIDATES = [
  resolve(
    process.cwd(),
    '../backend/crdp-form/src/test/resources/shared-cases/form-progress-cases.json',
  ),
  resolve(
    process.cwd(),
    'backend/crdp-form/src/test/resources/shared-cases/form-progress-cases.json',
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

describe('e11-t11 已填 / 应填进度（与后端同一份用例、同一结果：A-PAT-019 口径）', () => {
  const cases = loadSharedCases();

  it('共享用例文件可读且不少于既有用例数（增删须两端同步）', () => {
    expect(cases.length).toBeGreaterThanOrEqual(8);
  });

  it('全部分类进度：key / label / filled / total 与输出顺序与后端逐条一致', () => {
    for (const item of cases) {
      const progress = computeCategoryProgress(
        item.fields,
        item.categories ?? [],
        item.data,
        item.context,
      );
      expect(progress, `${item.name} → 分类进度`).toEqual(item.expected);
    }
  });
});
