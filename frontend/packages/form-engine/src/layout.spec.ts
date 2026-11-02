import type { FormSchema } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  anchorId,
  choiceOptions,
  controlKind,
  flattenRendererFields,
  layoutCategories,
} from './layout';

/** 最小字段工厂（缺省列与后端 FormSchema 对齐）。 */
function field(partial: Record<string, unknown> & { key: string }) {
  return {
    code: partial.key,
    label: `字段${partial.key}`,
    required: false,
    sensitive: false,
    storage: 'json',
    type: 'text',
    ...partial,
  } as FormSchema['categories'][number]['fields'][number];
}

/** A 骨架的迷你模板：分类顺序打乱传入，验证按 order 排（C-FLD-010 顺序只来自模板）。 */
const schema: Pick<FormSchema, 'categories'> = {
  categories: [
    {
      key: 'echo',
      label: '超声检查',
      order: 30,
      fields: [
        field({ key: 'f021', type: 'number', unit: '%', label: 'EF 射血分数' }),
      ],
    },
    {
      key: 'basic',
      label: '基本信息',
      order: 10,
      fields: [
        field({
          key: 'f001',
          type: 'radio',
          label: '吸烟史',
          optionSet: [
            { code: 0, label: '否' },
            { code: 1, label: '是' },
          ],
        }),
        field({ key: 'f002', type: 'boolean', label: 'CABG' }),
        field({
          key: 'f003',
          type: 'text',
          label: '姓名',
          sensitive: true,
          storage: 'encrypted',
        }),
      ],
    },
    {
      key: 'surgery',
      label: '手术信息',
      order: 20,
      fields: [
        field({ key: 'f010', type: 'date', label: '手术日期' }),
        field({
          key: 'f011',
          type: 'select',
          label: 'NYHA 心功能分级',
          optionSet: [
            { code: 1, label: 'Ⅰ 级' },
            { code: 2, label: 'Ⅱ 级' },
            { code: 3, label: 'Ⅲ 级' },
            { code: 4, label: 'Ⅳ 级' },
          ],
        }),
        field({
          key: 'f012',
          type: 'select',
          label: '再住院类型',
          optionSet: [
            { code: 1, label: '心血管疾病' },
            { code: 2, label: '脑血管疾病' },
            { code: 3, label: '外周血管疾病' },
            { code: 4, label: '非心血管原因' },
            { code: 5, label: '肿瘤' },
            { code: 9, label: '其他', needsDetail: true },
          ],
        }),
        field({
          key: 'f013',
          type: 'multiselect',
          label: '症状',
          optionSet: [
            { code: 0, label: '无', exclusive: true },
            { code: 1, label: '心悸' },
            { code: 9, label: '其他', needsDetail: true },
          ],
        }),
        field({
          key: 'f014',
          type: 'derived',
          derived: { fn: 'BMI', args: ['f020', 'f021'] },
        }),
        field({ key: 'f015', type: 'pair_number', label: '室壁瘤大小' }),
      ],
    },
  ],
};

describe('e11-t07 分类布局（A-FLD-007 / C-FLD-010）', () => {
  it('分类按模板 order 排序，字段保持模板顺序（顺序由租户管理员在模板里调整，渲染器不自行排序）', () => {
    const laid = layoutCategories(schema);
    expect(laid.map((c) => c.key)).toEqual(['basic', 'surgery', 'echo']);
    // 字段顺序 = 模板数组顺序，稳定键原样
    expect(laid[1]?.fields.map((f) => f.key)).toEqual([
      'f010',
      'f011',
      'f012',
      'f013',
      'f014',
      'f015',
    ]);
  });

  it('分类带稳定锚点键（crdp-anchor-<key>），字段平铺后携带 categoryKey 供校验挂靠', () => {
    const laid = layoutCategories(schema);
    expect(laid.map((c) => anchorId(c.key))).toEqual([
      'crdp-anchor-basic',
      'crdp-anchor-surgery',
      'crdp-anchor-echo',
    ]);
    const flat = flattenRendererFields(laid);
    expect(flat.find((f) => f.key === 'f021')?.categoryKey).toBe('echo');
    expect(flat.map((f) => f.key)).toHaveLength(10);
  });

  it('order 相同时保持输入顺序（稳定排序）', () => {
    const laid = layoutCategories({
      categories: [
        { key: 'b', label: 'B', order: 10, fields: [] },
        { key: 'a', label: 'A', order: 10, fields: [] },
      ],
    });
    expect(laid.map((c) => c.key)).toEqual(['b', 'a']);
  });
});

describe('e11-t07 控件形态（components.md §10.3）', () => {
  it('敏感字段渲染脱敏只读；派生字段只读；其余按类型', () => {
    const laid = layoutCategories(schema);
    const byKey = new Map(flattenRendererFields(laid).map((f) => [f.key, f]));
    const kindOfKey = (key: string) => {
      const field = byKey.get(key);
      if (!field) throw new Error(`fixture 缺字段 ${key}`);
      return controlKind(field);
    };
    expect(kindOfKey('f003')).toBe('sensitive');
    expect(kindOfKey('f014')).toBe('derived');
    expect(kindOfKey('f001')).toBe('radio');
    expect(kindOfKey('f002')).toBe('radio');
    expect(kindOfKey('f011')).toBe('radio');
    expect(kindOfKey('f012')).toBe('select');
    expect(kindOfKey('f013')).toBe('multiselect');
    expect(kindOfKey('f021')).toBe('number');
    expect(kindOfKey('f015')).toBe('pair-number');
  });

  it('是否类无选项集时按 否 / 是（0=否、1=是 兜底），选项值映射 false / true', () => {
    const options = choiceOptions({
      key: 'f002',
      code: 'f002',
      label: 'CABG',
      required: false,
      sensitive: false,
      storage: 'json',
      type: 'boolean',
    });
    expect(options).toEqual([
      { label: '否', value: false },
      { label: '是', value: true },
    ]);
  });

  it('是否类带选项集时用模板显示名（如 无 / 有）', () => {
    const options = choiceOptions({
      key: 'f002',
      code: 'f002',
      label: '体外循环',
      required: false,
      sensitive: false,
      storage: 'json',
      type: 'boolean',
      optionSet: [
        { code: 0, label: '无' },
        { code: 1, label: '有' },
      ],
    });
    expect(options).toEqual([
      { label: '无', value: false },
      { label: '有', value: true },
    ]);
  });

  it('单选选项只数未停用的：停用后剩 ≤4 个时用单选按钮组', () => {
    const laid = layoutCategories(schema);
    const byKey = new Map(flattenRendererFields(laid).map((f) => [f.key, f]));
    const five = byKey.get('f012');
    if (!five) throw new Error('fixture 缺字段 f012');
    expect(controlKind(five)).toBe('select');
    // 停用两项后剩 4 个启用选项 → radio
    five.optionSet = five.optionSet?.map((o) =>
      [5, 9].includes(o.code) ? { ...o, disabled: true } : o,
    );
    expect(controlKind(five)).toBe('radio');
  });
});
