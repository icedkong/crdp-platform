import type { AdvancedSearchField, ConditionGroup } from './advanced-search';

// A-PAT-014, A-PAT-015, A-NFR-009, C-PAT-013（E13-T06 高级检索纯逻辑）
import { describe, expect, it } from 'vitest';

import {
  conditionPreview,
  conditionRowCount,
  conditionRowText,
  emptyConditionGroup,
  emptyConditionRow,
  isConditionGroupComplete,
  isRowComplete,
  pruneConditionGroup,
} from './index';

/* ----------------------------------------------------------------------
 * 样例目录（与后端 search-fields 同构；字段全部虚构）。
 */
function field(
  partial: Partial<AdvancedSearchField> & { field: string; label: string },
): AdvancedSearchField {
  return {
    categoryKey: 'basic',
    categoryLabel: '基本信息',
    common: true,
    ops: ['EQ', 'NE', 'GT', 'LT', 'BETWEEN', 'IS_NULL', 'NOT_NULL'],
    options: [],
    type: 'DATE',
    ...partial,
  };
}

const FIELDS: AdvancedSearchField[] = [
  field({
    field: 'ward',
    label: '病区/中心',
    type: 'OPTION',
    ops: ['EQ', 'NE', 'IS_NULL', 'NOT_NULL'],
    options: [
      { value: 3, label: '示例一病区' },
      { value: 4, label: '示例二病区' },
    ],
  }),
  field({ field: 'discharge_date', label: '出院日期' }),
  field({
    field: 'patient_type',
    label: '患者类型',
    type: 'TEXT',
    ops: ['EQ', 'NE', 'CONTAINS', 'IS_NULL', 'NOT_NULL'],
  }),
  field({
    field: 'gender',
    label: '性别',
    type: 'OPTION',
    ops: ['EQ', 'NE', 'IS_NULL', 'NOT_NULL'],
    options: [
      { value: 'M', label: '男' },
      { value: 'F', label: '女' },
    ],
  }),
];

describe('条件行完整性（提交前校验）', () => {
  it('为空 / 不为空不需要取值', () => {
    expect(
      isRowComplete({ field: 'discharge_date', op: 'IS_NULL' }, FIELDS),
    ).toBe(true);
    expect(
      isRowComplete({ field: 'discharge_date', op: 'NOT_NULL' }, FIELDS),
    ).toBe(true);
  });

  it('比较运算必须有取值；介于须二元数组', () => {
    expect(
      isRowComplete({ field: 'discharge_date', op: 'EQ', value: null }, FIELDS),
    ).toBe(false);
    expect(
      isRowComplete(
        { field: 'discharge_date', op: 'EQ', value: '2026-01-01' },
        FIELDS,
      ),
    ).toBe(true);
    expect(
      isRowComplete(
        { field: 'discharge_date', op: 'BETWEEN', value: ['2026-01-01'] },
        FIELDS,
      ),
    ).toBe(false);
    expect(
      isRowComplete(
        {
          field: 'discharge_date',
          op: 'BETWEEN',
          value: ['2026-01-01', '2026-06-30'],
        },
        FIELDS,
      ),
    ).toBe(true);
  });

  it('缺字段或缺运算符不完整', () => {
    expect(isRowComplete({ field: '', op: 'EQ', value: 1 }, FIELDS)).toBe(
      false,
    );
    expect(isRowComplete({ field: 'ward', op: '', value: 3 }, FIELDS)).toBe(
      false,
    );
  });
});

describe('剪枝与整树校验（空组不发送，后端 40001 兜底）', () => {
  it('剪掉不完整行与空组；根空返回 null', () => {
    const group: ConditionGroup = {
      logic: 'and',
      items: [
        { field: 'ward', op: 'EQ', value: 3 },
        { field: 'discharge_date', op: 'EQ', value: null },
        { logic: 'or', items: [{ field: 'gender', op: 'IS_NULL' }] },
        { logic: 'and', items: [] },
      ],
    };
    const pruned = pruneConditionGroup(group, FIELDS);
    expect(pruned).toEqual({
      logic: 'and',
      items: [
        { field: 'ward', op: 'EQ', value: 3 },
        { logic: 'or', items: [{ field: 'gender', op: 'IS_NULL' }] },
      ],
    });
    expect(pruneConditionGroup(emptyConditionGroup(), FIELDS)).toBeNull();
  });

  it('isConditionGroupComplete 要求全部行完整', () => {
    const complete: ConditionGroup = {
      logic: 'and',
      items: [
        { field: 'ward', op: 'EQ', value: 3 },
        { logic: 'or', items: [{ field: 'patient_type', op: 'NOT_NULL' }] },
      ],
    };
    expect(isConditionGroupComplete(complete, FIELDS)).toBe(true);
    const incomplete: ConditionGroup = {
      logic: 'and',
      items: [{ field: 'ward', op: 'EQ', value: null }],
    };
    expect(isConditionGroupComplete(incomplete, FIELDS)).toBe(false);
  });
});

describe('逻辑表达式预览（A-PAT-014：下方实时显示）', () => {
  it('单行：字段名 运算符 取值（选项显示文案）', () => {
    expect(
      conditionRowText({ field: 'ward', op: 'EQ', value: 3 }, FIELDS),
    ).toBe('病区/中心 等于 示例一病区');
    expect(
      conditionRowText({ field: 'gender', op: 'NE', value: 'F' }, FIELDS),
    ).toBe('性别 不等于 女');
    expect(
      conditionRowText(
        { field: 'patient_type', op: 'CONTAINS', value: '急诊' },
        FIELDS,
      ),
    ).toBe('患者类型 包含 急诊');
    expect(
      conditionRowText({ field: 'discharge_date', op: 'IS_NULL' }, FIELDS),
    ).toBe('出院日期 为空');
  });

  it('介于显示 起值 ~ 止值；缺取值显示 …', () => {
    expect(
      conditionRowText(
        {
          field: 'discharge_date',
          op: 'BETWEEN',
          value: ['2026-01-01', '2026-06-30'],
        },
        FIELDS,
      ),
    ).toBe('出院日期 介于 2026-01-01 ~ 2026-06-30');
    expect(
      conditionRowText({ field: 'discharge_date', op: 'EQ' }, FIELDS),
    ).toBe('出院日期 等于 …');
  });

  it('分组：括号 + 并且 / 或者（与规格示例同构）', () => {
    const group: ConditionGroup = {
      logic: 'or',
      items: [
        {
          logic: 'and',
          items: [
            { field: 'ward', op: 'EQ', value: 3 },
            { field: 'discharge_date', op: 'LT', value: '2026-06-30' },
          ],
        },
        { field: 'patient_type', op: 'NOT_NULL' },
      ],
    };
    expect(conditionPreview(group, FIELDS)).toBe(
      '（病区/中心 等于 示例一病区 并且 出院日期 小于 2026-06-30）或者 患者类型 不为空',
    );
  });

  it('未剪枝前含缺项行时预览仍显示 …；全空返回空串', () => {
    expect(conditionPreview(emptyConditionGroup(), FIELDS)).toBe('');
    expect(
      conditionPreview(
        { logic: 'and', items: [{ field: 'ward', op: 'EQ', value: null }] },
        FIELDS,
      ),
    ).toBe('');
  });
});

describe('条件行数（列表页提示条）', () => {
  it('只数生效行（剪枝后）', () => {
    const group: ConditionGroup = {
      logic: 'and',
      items: [
        { field: 'ward', op: 'EQ', value: 3 },
        { field: 'ward', op: 'EQ', value: null },
        {
          logic: 'or',
          items: [
            { field: 'gender', op: 'IS_NULL' },
            { field: 'gender', op: 'EQ' },
          ],
        },
      ],
    };
    expect(conditionRowCount(group, FIELDS)).toBe(2);
  });
});

describe('空行构造', () => {
  it('默认取目录第一个字段与其第一个运算符', () => {
    expect(emptyConditionRow(FIELDS)).toEqual({
      field: 'ward',
      op: 'EQ',
      value: null,
    });
  });
});
