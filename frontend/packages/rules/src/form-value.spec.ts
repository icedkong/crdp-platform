import { describe, expect, it } from 'vitest';

import { copyFormValue, sameFormValue } from './form-value';

describe('表单 JSON 值比较', () => {
  it('快照与嵌套数组独立，后续修改不改变已保存基准', () => {
    const value = { choices: [1], meta: { touched: true } };
    const copy = copyFormValue(value);
    expect(sameFormValue(value, copy)).toBe(true);
    value.choices.push(2);
    expect(copy.choices).toEqual([1]);
    expect(sameFormValue(value, copy)).toBe(false);
  });
  it('多选与成对数值按内容比较，保持数组顺序语义', () => {
    expect(sameFormValue([1, 2], [1, 2])).toBe(true);
    expect(sameFormValue([1, 2], [2, 1])).toBe(false);
    expect(sameFormValue([1, 2], [1])).toBe(false);
  });
  it('对象字段顺序不制造虚假修改，嵌套修改能够识别', () => {
    expect(sameFormValue({ a: [1], b: 2 }, { b: 2, a: [1] })).toBe(true);
    expect(sameFormValue({ a: [1] }, { a: [2] })).toBe(false);
  });
  it('区分清空、空串、数值和字符串编码', () => {
    expect(sameFormValue(null, undefined)).toBe(false);
    expect(sameFormValue('', null)).toBe(false);
    expect(sameFormValue(1, '1')).toBe(false);
    expect(sameFormValue({}, [])).toBe(false);
  });
});
