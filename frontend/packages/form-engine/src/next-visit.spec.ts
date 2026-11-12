import type { NextVisitAffordance } from './next-visit';

import { describe, expect, it } from 'vitest';

import {
  NEXT_VISIT_FIELD_CODE,
  NEXT_VISIT_PLACEHOLDER,
  nextVisitHintOf,
  nextVisitQuickOptions,
} from './next-visit';

/* ----------------------------------------------------------------------
 * E21-T08 / C-FU-005（Q05）：「下次随访时间」字段（next_visit_date，C 程控表单 f333）
 * 的录入辅助纯函数——间隔快捷（+1/+2/+3/+6/+12 个月）与窗口预览 / 补充提示。
 * 日期全部虚构。
 */

describe('e21-t08 下次随访时间：间隔快捷（C 规格附录 A 随访 #33）', () => {
  it('值 = 基准（本次随访日期）+ N 个月，五个档位', () => {
    const affordance: NextVisitAffordance = {
      baseDate: '2026-11-13',
      note: null,
      windowPreview: null,
    };
    expect(nextVisitQuickOptions(affordance, () => '2030-01-01')).toEqual([
      { label: '+1 个月', value: '2026-12-13' },
      { label: '+2 个月', value: '2027-01-13' },
      { label: '+3 个月', value: '2027-02-13' },
      { label: '+6 个月', value: '2027-05-13' },
      { label: '+12 个月', value: '2027-11-13' },
    ]);
  });

  it('基准缺省回落今天（today 可注入）；月末与后端 LocalDate.plusMonths 同口径', () => {
    const affordance: NextVisitAffordance = {
      baseDate: null,
      note: null,
      windowPreview: null,
    };
    expect(nextVisitQuickOptions(affordance, () => '2027-01-31')[0]).toEqual({
      label: '+1 个月',
      value: '2027-02-28',
    });
  });
});

describe('e21-t08 下次随访时间：字段旁提示（pages.md §5.1）', () => {
  it('已填且带窗口预览 → 「= 下次计划日期；窗口 X ~ Y」；补充提示用「；」连接', () => {
    const affordance: NextVisitAffordance = {
      baseDate: '2026-11-13',
      note: '补录：只保存',
      windowPreview: { start: '2026-12-16', end: '2027-02-14' },
    };
    expect(nextVisitHintOf(affordance, true)).toBe(
      '= 下次计划日期；窗口 2026-12-16 ~ 2027-02-14；补录：只保存',
    );
  });

  it('未填不出窗口预览；只有补充提示时只出提示；全空返回空串', () => {
    const affordance: NextVisitAffordance = {
      baseDate: null,
      note: '补录：只保存',
      windowPreview: { start: '2026-12-16', end: '2027-02-14' },
    };
    expect(nextVisitHintOf(affordance, false)).toBe('补录：只保存');
    expect(
      nextVisitHintOf(
        { baseDate: null, note: null, windowPreview: null },
        true,
      ),
    ).toBe('');
  });

  it('字段编码与占位文案（pages.md §5.1 照抄）', () => {
    expect(NEXT_VISIT_FIELD_CODE).toBe('next_visit_date');
    expect(NEXT_VISIT_PLACEHOLDER).toBe('不填则按计划默认（ERI 期优先）');
  });
});
