import type { FormSchemaField } from '@crdp/api';

import type { RendererField } from './layout';

import { describe, expect, it } from 'vitest';

import {
  applyMultiselectChange,
  exclusiveCodes,
  isOptionLocked,
  missingDetailIssues,
  needsDetailCodes,
  normalizeMultiselectValue,
  selectedNeedsDetailCodes,
} from './multiselect';

/** C-FLD-004 的症状字段形态（hospital-c-spec 随访 #7）：无 / 心悸 / 黑蒙 / 晕厥 / 气促 / 乏力 / 胸痛 / 水肿 / 其他。 */
const symptomField: RendererField = {
  categoryKey: 'symptom',
  code: 'symptom',
  key: 'f301',
  label: '症状',
  optionSet: [
    { code: 0, label: '无', exclusive: true },
    { code: 1, label: '心悸' },
    { code: 2, label: '黑蒙' },
    { code: 3, label: '晕厥' },
    { code: 9, label: '其他', needsDetail: true },
  ],
  required: false,
  sensitive: false,
  storage: 'json',
  type: 'multiselect',
};

function fieldOf(
  partial: Partial<RendererField> & { key: string },
): RendererField {
  return { ...symptomField, key: partial.key, ...partial };
}

describe('e11-t07 多选：互斥与补充说明（C-FLD-004 / Q04）', () => {
  it('勾选「无」后其他选项自动取消（互斥单向）', () => {
    expect(applyMultiselectChange(symptomField, [1, 2], [1, 2, 0])).toEqual([
      0,
    ]);
  });

  it('已选「无」时勾选其他选项：先取消「无」再选（互斥双向）', () => {
    expect(applyMultiselectChange(symptomField, [0], [0, 1])).toEqual([1]);
  });

  it('已选「无」时其他选项不可勾选（isOptionLocked）', () => {
    expect(isOptionLocked(symptomField, [0], 1)).toBe(true);
    expect(isOptionLocked(symptomField, [0], 0)).toBe(false);
    expect(isOptionLocked(symptomField, [1, 2], 0)).toBe(false);
  });

  it('停用选项同样不可勾选', () => {
    const disabledOther = fieldOf({
      key: 'f302',
      optionSet: symptomField.optionSet?.map((o) =>
        o.code === 2 ? { ...o, disabled: true } : o,
      ),
    });
    expect(isOptionLocked(disabledOther, [], 2)).toBe(true);
    expect(isOptionLocked(disabledOther, [], 1)).toBe(false);
  });

  it('取消勾选不影响其余选项；脏数据（互斥共存）在下次变更时收敛', () => {
    expect(applyMultiselectChange(symptomField, [1, 2], [1])).toEqual([1]);
    // 同时勾了「无」与「心悸」的脏数据：再勾一个普通选项时清掉互斥项
    expect(applyMultiselectChange(symptomField, [0, 1], [0, 1, 3])).toEqual([
      1, 3,
    ]);
  });

  it('值编码与选项元数据：数组存编码、互斥 / 补充说明编码可查', () => {
    expect(normalizeMultiselectValue([1, '2', 3])).toEqual([1, 2, 3]);
    expect(normalizeMultiselectValue(null)).toEqual([]);
    expect(normalizeMultiselectValue(undefined)).toEqual([]);
    expect(exclusiveCodes(symptomField)).toEqual([0]);
    expect(needsDetailCodes(symptomField)).toEqual([9]);
    expect(selectedNeedsDetailCodes(symptomField, [1, 9])).toEqual([9]);
    expect(selectedNeedsDetailCodes(symptomField, [0])).toEqual([]);
  });
});

describe('e11-t07 多选：选「其他」须填写说明（勾选未填说明时不能保存）', () => {
  const eventField = fieldOf({
    key: 'f310',
    categoryKey: 'arrhythmia',
    label: '心律失常事件',
  });

  it('选了「其他」且说明为空 → 阻止保存的命中项（rule=needsDetail）', () => {
    const issues = missingDetailIssues(
      [eventField],
      { f310: [1, 9] },
      {},
      undefined,
    );
    expect(issues).toEqual([
      {
        key: 'f310',
        categoryKey: 'arrhythmia',
        rule: 'needsDetail',
        message: '选“其他”须填写说明',
      },
    ]);
  });

  it('说明存该字段备注（data_meta.note，Q37 口径）：填了不再命中', () => {
    const issues = missingDetailIssues(
      [eventField],
      { f310: [1, 9] },
      { f310: { note: '起搏器综合征（示例）' } },
      undefined,
    );
    expect(issues).toEqual([]);
  });

  it('未选「其他」、空值、隐藏字段都不命中', () => {
    expect(
      missingDetailIssues([eventField], { f310: [1] }, {}, undefined),
    ).toEqual([]);
    expect(missingDetailIssues([eventField], {}, {}, undefined)).toEqual([]);

    const hidden = fieldOf({
      key: 'f311',
      categoryKey: 'icd',
      visibleWhen: { all: [{ field: 'f300', op: 'eq', value: 1 }] },
    });
    // 条件不成立（f300 未答）→ 隐藏不校验
    expect(missingDetailIssues([hidden], { f311: [9] }, {}, undefined)).toEqual(
      [],
    );
    // 条件成立（f300 = 1）→ 显示中才校验
    expect(
      missingDetailIssues([hidden], { f300: 1, f311: [9] }, {}, undefined),
    ).toHaveLength(1);
  });

  it('非多选字段与空 optionSet 不参与', () => {
    const text: RendererField = {
      categoryKey: 'x',
      code: 'x',
      key: 'f320',
      label: '备注',
      required: false,
      sensitive: false,
      storage: 'json',
      type: 'textarea',
    } satisfies FormSchemaField & { categoryKey: string };
    expect(missingDetailIssues([text], { f320: 'x' }, {}, undefined)).toEqual(
      [],
    );
  });
});
