import { describe, expect, it } from 'vitest';

import {
  fieldDictStatusText,
  fieldRangeText,
  fieldStorageText,
  formFieldTypeText,
  formUsageText,
  isOptionFieldType,
  isValidFieldRange,
  matchFieldKeyword,
} from './form';

/** E11-T01 / A-FLD-002：显示名与编码分离的展示与筛选逻辑。 */
describe('e11-t01 字段字典类型与助手', () => {
  it('状态与类型文案：启停、派生只读、加密列', () => {
    expect(fieldDictStatusText('ENABLED')).toBe('启用');
    expect(fieldDictStatusText('DISABLED')).toBe('已停用');
    expect(formFieldTypeText('radio')).toBe('单选');
    expect(formFieldTypeText('derived')).toBe('派生（自动计算）');
    expect(fieldStorageText('encrypted')).toBe('加密列（敏感，不进 JSON）');
    expect(formUsageText('BASELINE')).toBe('基线');
  });

  it('选项型字段判定：下拉 / 单选 / 多选可维护选项，是否类与文本类不可', () => {
    expect(isOptionFieldType('select')).toBe(true);
    expect(isOptionFieldType('radio')).toBe(true);
    expect(isOptionFieldType('multiselect')).toBe(true);
    expect(isOptionFieldType('boolean')).toBe(false);
    expect(isOptionFieldType('text')).toBe(false);
    expect(isOptionFieldType('number')).toBe(false);
  });

  it('范围展示：区间、单端、空', () => {
    expect(fieldRangeText({ max: 210, min: 130 })).toBe('130–210');
    expect(fieldRangeText({ min: 0 })).toBe('≥ 0');
    expect(fieldRangeText({ max: 250 })).toBe('≤ 250');
    expect(fieldRangeText(null)).toBe('');
    expect(fieldRangeText({ max: null, min: null })).toBe('');
  });

  it('范围校验：min ≤ max 才合法', () => {
    expect(isValidFieldRange({ max: 210, min: 130 })).toBe(true);
    expect(isValidFieldRange({ max: 130, min: 210 })).toBe(false);
    expect(isValidFieldRange(null)).toBe(true);
    expect(isValidFieldRange({ min: 1 })).toBe(true);
  });

  it('搜索命中显示名 / 字段编码 / 稳定键，大小写不敏感', () => {
    const smoking = { code: 'smoking_history', key: 'f010', label: '吸烟史' };
    expect(matchFieldKeyword(smoking, '吸烟')).toBe(true);
    expect(matchFieldKeyword(smoking, 'SMOKING')).toBe(true);
    expect(matchFieldKeyword(smoking, 'f010')).toBe(true);
    expect(matchFieldKeyword(smoking, ' ')).toBe(true);
    expect(matchFieldKeyword(smoking, '体重')).toBe(false);
  });
});
