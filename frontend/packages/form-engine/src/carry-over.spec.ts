import type { RendererField } from './layout';

import { describe, expect, it } from 'vitest';

import {
  applyCarryOver,
  canCarryOver,
  carryOverButtonText,
  carryOverPlan,
} from './carry-over';

/** A 随访「用药」等带入字段的迷你形态（A-FU-015：用药等字段可一键带入上一次随访的值再修改）。 */
function f(partial: Partial<RendererField> & { key: string }): RendererField {
  return {
    categoryKey: 'medication',
    code: partial.key,
    label: `字段${partial.key}`,
    required: false,
    sensitive: false,
    storage: 'json',
    type: 'radio',
    ...partial,
  };
}

const fields: RendererField[] = [
  // 用药设置项：可带入
  f({
    key: 'f401',
    label: '他汀类',
    carryOver: true,
    optionSet: [
      { code: 0, label: '未用' },
      { code: 1, label: '辛伐他汀' },
      { code: 2, label: '阿托伐他汀' },
    ],
  }),
  f({ key: 'f402', label: 'β 受体阻滞剂', carryOver: true, type: 'boolean' }),
  // 测量值：不带入（C-FU-016：测量值不带入，模板不加标记即可）
  f({ key: 'f403', label: 'EF 射血分数', type: 'number', unit: '%' }),
  // 标记了带入但上次没填
  f({
    key: 'f404',
    label: 'ACEI/ARB',
    carryOver: true,
    type: 'select',
    optionSet: [{ code: 1, label: '培哚普利' }],
  }),
  // 标记了带入但当前被条件隐藏（是否死亡=是 → 随访结局字段隐藏）
  f({
    key: 'f405',
    label: '用药备注',
    type: 'textarea',
    carryOver: true,
    visibleWhen: { all: [{ field: 'f400', op: 'ne', value: 1 }] },
  }),
  // 派生 / 敏感即使误配也不带入
  f({
    key: 'f406',
    label: 'BMI',
    type: 'derived',
    derived: { fn: 'BMI', args: [] },
    carryOver: true,
  }),
  f({
    key: 'f407',
    label: '联系电话',
    type: 'text',
    sensitive: true,
    storage: 'encrypted',
    carryOver: true,
  }),
];

const previous = {
  recordId: '9001',
  date: '2025-11-02',
  values: {
    f401: 2,
    f402: true,
    f403: 45,
    f405: '继续当前方案（示例）',
    f406: 24.5,
    f999: '不在模板里的键',
  } as Record<string, unknown>,
};

describe('e11-t07 带入上次值（A-FU-015 / C-FU-016）', () => {
  it('只带入模板标记的字段：设置项带入、测量值不带入、上次空值不带入', () => {
    const plan = carryOverPlan(fields, {}, undefined, previous);
    expect(plan.map((item) => item.key)).toEqual(['f401', 'f402']);
    expect(plan[0]).toMatchObject({ key: 'f401', label: '他汀类', value: 2 });
  });

  it('条件隐藏的字段不带入（带入后也不能把隐藏字段顶出来）', () => {
    const plan = carryOverPlan(fields, { f400: 1 }, undefined, previous);
    expect(plan.map((item) => item.key)).toEqual(['f401', 'f402']); // f405 隐藏
    const visible = carryOverPlan(fields, { f400: 0 }, undefined, previous);
    expect(visible.map((item) => item.key)).toEqual(['f401', 'f402', 'f405']);
  });

  it('派生字段与敏感（加密列）字段即使误配了标记也不带入', () => {
    const plan = carryOverPlan(fields, {}, undefined, previous);
    expect(
      plan.some((item) => item.key === 'f406' || item.key === 'f407'),
    ).toBe(false);
  });

  it('applyCarryOver 覆盖写入（带入后再修改），不改入参、数组 / 对象值深拷贝', () => {
    const data: Record<string, unknown> = { f401: 0, f403: 50 };
    const multi = f({
      key: 'f408',
      label: '合并用药',
      carryOver: true,
      type: 'multiselect',
      optionSet: [
        { code: 1, label: '阿司匹林' },
        { code: 2, label: '氯吡格雷' },
      ],
    });
    const plan = carryOverPlan([...fields, multi], data, undefined, {
      ...previous,
      values: { ...previous.values, f408: [1, 2] },
    });
    const next = applyCarryOver(data, plan);
    expect(next).toEqual({ f401: 2, f403: 50, f402: true, f408: [1, 2] });
    expect(data).toEqual({ f401: 0, f403: 50 }); // 不改入参
    const carried = next.f408 as number[];
    carried.push(9);
    expect((previous.values.f408 as number[]) ?? [1, 2]).toEqual([1, 2]); // 深拷贝，不共享引用
  });

  it('canCarryOver：无上次记录、无可带入项时为 false（按钮不出现）', () => {
    expect(canCarryOver(fields, {}, undefined, previous)).toBe(true);
    expect(canCarryOver(fields, {}, undefined, null)).toBe(false);
    expect(canCarryOver(fields, {}, undefined, undefined)).toBe(false);
    expect(canCarryOver([], {}, undefined, previous)).toBe(false);
    // 上次全部没填 → 无可带入项
    expect(
      canCarryOver(fields, {}, undefined, {
        recordId: '1',
        date: '2025-01-01',
        values: {},
      }),
    ).toBe(false);
  });

  it('按钮文案带上次随访日期', () => {
    expect(carryOverButtonText(previous)).toBe('带入上次值（2025-11-02）');
  });
});
