import type { FormSchemaField } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  formatFieldValue,
  previousValueText,
  readonlyFieldValue,
} from './format-value';

const base: FormSchemaField = {
  code: 'x',
  key: 'f001',
  label: '字段',
  required: false,
  sensitive: false,
  storage: 'json',
  type: 'text',
};

describe('e11-t07 值显示格式（上次值 / 只读视图）', () => {
  it('单选 / 下拉显示选项显示名，未知编码回落编码本身', () => {
    const field: FormSchemaField = {
      ...base,
      type: 'radio',
      optionSet: [
        { code: 0, label: '否' },
        { code: 1, label: '是' },
      ],
    };
    expect(formatFieldValue(field, 1)).toBe('是');
    expect(formatFieldValue(field, 7)).toBe('7');
  });

  it('业务字典来源的字符串编码同样显示显示名（E23-T20 / OQ-69④ number | string）', () => {
    const field: FormSchemaField = {
      ...base,
      type: 'select',
      optionSet: [
        { code: 'SSI', label: '单腔起搏器(SSI)' },
        { code: 'CRT_D', label: 'CRT-D' },
      ],
    };
    expect(formatFieldValue(field, 'SSI')).toBe('单腔起搏器(SSI)');
    // 未知编码回落编码本身（停用项的历史值）
    expect(formatFieldValue(field, 'XXX')).toBe('XXX');
  });

  it('是否显示 是 / 否；多选用「、」连接显示名', () => {
    expect(formatFieldValue({ ...base, type: 'boolean' }, true)).toBe('是');
    expect(formatFieldValue({ ...base, type: 'boolean' }, false)).toBe('否');
    const multi: FormSchemaField = {
      ...base,
      type: 'multiselect',
      optionSet: [
        { code: 1, label: '心悸' },
        { code: 2, label: '黑蒙' },
        { code: 9, label: '其他' },
      ],
    };
    expect(formatFieldValue(multi, [2, 1])).toBe('黑蒙、心悸');
    expect(formatFieldValue(multi, [])).toBe('');
  });

  it('数值带单位（单位在文案里跟随）；数值 ×2 显示 长 × 宽', () => {
    expect(formatFieldValue({ ...base, type: 'number', unit: '%' }, 45)).toBe(
      '45 %',
    );
    expect(formatFieldValue({ ...base, type: 'number' }, 3.5)).toBe('3.5');
    expect(
      formatFieldValue(
        { ...base, type: 'pair_number' },
        { length: 3, width: 2 },
      ),
    ).toBe('3 × 2');
    expect(
      formatFieldValue({ ...base, type: 'pair_number' }, { length: 3 }),
    ).toBe('3');
  });

  it('日期 / 文本按原值显示；空值（null / 空串 / 空数组）显示为空', () => {
    expect(formatFieldValue({ ...base, type: 'date' }, '2026-11-02')).toBe(
      '2026-11-02',
    );
    expect(
      formatFieldValue({ ...base, type: 'textarea' }, '病情稳定（示例）'),
    ).toBe('病情稳定（示例）');
    expect(formatFieldValue(base, null)).toBe('');
    expect(formatFieldValue(base, '')).toBe('');
  });

  it('上次值文案「上次 xx」；无值不带', () => {
    const field: FormSchemaField = {
      ...base,
      type: 'number',
      unit: 'mm',
      label: 'LVDd 舒张末径',
    };
    expect(previousValueText(field, 52)).toBe('上次 52 mm');
    expect(previousValueText(field, null)).toBe('');
  });

  it('只读视图空值显示「未填写」', () => {
    expect(readonlyFieldValue(base, undefined)).toBe('未填写');
    expect(readonlyFieldValue(base, '有值')).toBe('有值');
  });
});
