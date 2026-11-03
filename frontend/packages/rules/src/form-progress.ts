/**
 * E11-T11 / A-PAT-021、C-PAT-018（口径 A-PAT-019）：表单引擎「已填 / 应填」进度——
 * 按分类 / 分节汇总已填必填 ÷ 应填必填，供渲染器 progress 事件（FormRenderer →
 * CategoryBar，E13-T11）与后端 `FormProgressEngine`（POST /forms/{code}/progress、
 * 完整度统计 A-DASH-010/011）共用同一口径。
 *
 * 共享用例 `backend/crdp-form/src/test/resources/shared-cases/form-progress-cases.json`
 * 两端各执行一遍（components.md §10.5），每个分类的 key / label / filled / total 与
 * 输出顺序逐条一致（验收「前后端结果一致」）。
 *
 * - 应填 total：显示中（visibleWhen 求值）且有效必填（required || requiredWhen 成立）
 *   且 storage=json 且非派生的字段——与提交校验的必填集合同一口径（missingRequiredFields），
 *   「应填 − 已填」恰为必填缺失；派生字段由 E11-T05 重算、实体 / 加密列的值不经表单
 *   JSON（必填随业务接口 E13），都不计入。
 * - 已填 filled：应填字段中值非空的字段。空值口径与条件 DSL 同（isEmptyFormValue）：
 *   null / 缺键 / 空串 / 空数组 / 分量全缺的 pair_number 对象为空，`0` 与 `false` 是已答。
 * - 条件不显示的字段不计入分母（A-PAT-019「因条件不满足而不显示的字段不计入分母」）——
 *   隐藏字段的残留值也不算已填。
 * - 分类按声明 order 升序（稳定排序）输出；字段引用未声明分类时按字段首现序兜底追加
 *   （label=key），与后端 FormService.buildSchema 的防御口径一致；无应填字段的分类输出
 *   0/0（分类条仍显示该分组）。
 */

import type { ConditionalField } from './form-condition';

import {
  isEmptyFormValue,
  isFieldVisible,
  isRequiredField,
} from './form-condition';

/** 分类 / 分节进度（components.md §6 CategoryProgress 的 E11-T11 子集：标红数、📎 数、未填必填数随后续任务）。 */
export interface CategoryProgress {
  /** 已填必填（只计实际显示的字段）。 */
  filled: number;
  /** 模板稳定锚点键（crdp-anchor-<key> 的 key 部分）。 */
  key: string;
  /** 分类显示名（基线「分类」/ 随访「分组」）。 */
  label: string;
  /** 应填必填。 */
  total: number;
}

/** 分类声明（FormSchemaCategory 子集；label 空 / 缺回落 key，order 缺省 0）。 */
export interface ProgressCategoryDecl {
  key: string;
  label?: null | string;
  order?: null | number;
}

/**
 * 按分类汇总已填 / 应填（A-PAT-019 口径，与后端 FormProgressEngine 同语义）。data /
 * context 为空（null / undefined）按空表单处理（无条件字段恒显示，ctx 叶子不成立）。
 * 返回数组本身有序：声明 order 升序 + 未声明分类按字段首现序兜底追加。
 */
export function computeCategoryProgress(
  fields: ConditionalField[],
  categories: ProgressCategoryDecl[],
  data: null | Record<string, unknown> | undefined,
  context?: null | Record<string, unknown>,
): CategoryProgress[] {
  const values = data ?? {};
  const counts = new Map<string, { filled: number; total: number }>(); // 首现序
  for (const field of fields) {
    if (!countable(field, values, context)) continue;
    const count = counts.get(field.categoryKey) ?? { filled: 0, total: 0 };
    count.total++; // 应填
    if (!isEmptyFormValue(values[field.key])) {
      count.filled++; // 已填（空值口径与条件 DSL 同）
    }
    counts.set(field.categoryKey, count);
  }

  const declared = new Set<string>();
  const items: CategoryProgress[] = [];
  const sorted = (categories ?? [])
    .filter((category) => Boolean(category?.key))
    .toSorted((a, b) => (a.order ?? 0) - (b.order ?? 0));
  for (const category of sorted) {
    declared.add(category.key);
    items.push({
      key: category.key,
      label: category.label || category.key,
      ...(counts.get(category.key) ?? { filled: 0, total: 0 }),
    });
  }
  for (const key of counts.keys()) {
    // 字段首现序兜底追加（label=key），不让字段的分组凭空消失
    if (!declared.has(key)) {
      items.push({
        key,
        label: key,
        ...(counts.get(key) as { filled: number; total: number }),
      });
    }
  }
  return items;
}

/** 应填判定（与 missingRequiredFields 同一过滤）：storage=json、非派生、显示中且有效必填。 */
function countable(
  field: ConditionalField,
  values: Record<string, unknown>,
  context?: null | Record<string, unknown>,
): boolean {
  if (field.storage !== undefined && field.storage !== 'json') return false; // 实体 / 加密列必填随 E13
  if (field.type === 'derived') return false; // 派生由 E11-T05 重算
  if (!isFieldVisible(field, values, context)) return false; // 条件不显示不计入（A-PAT-019）
  return isRequiredField(field, values, context);
}
