import type { CompareRule } from './compare-rule';

import { describe, expect, it } from 'vitest';

import {
  conditionText,
  toCompareRuleFormModel,
  toCompareRuleUpdate,
  validateCompareRuleModel,
} from './compare-rule';

/**
 * E17-T01 / A-FU-027：对比规则类型与配置页纯函数测试（与后端 CompareRuleType 同口径）。
 * 全部虚构数据。
 */

/** 迁移 V67 种子形状（EF 下降 ≥ 10 个百分点，回落基线 f102）。 */
const efDrop: CompareRule = {
  baselineField: 'f102',
  baselineFieldLabel: '出院前最后一次EF',
  baselineFormCode: 'A_BASELINE',
  categoryKey: 'fu_echo',
  enabled: true,
  fieldLabel: 'EF 射血分数',
  fieldUnit: '%',
  formCode: 'A_ANNUAL_FU',
  formName: '年度随访记录',
  id: 11,
  message: 'EF 比上次（或出院前）下降 ≥ 10 个百分点',
  name: '射血分数下降',
  params: { direction: 'DROP', threshold: 10 },
  ruleCode: 'ef_drop',
  ruleType: 'DIFF',
  ruleTypeLabel: '差值下降 / 上升',
  targetField: 'f248',
  updatedAt: '2027-05-17T10:00:00',
};

describe('e17-T01 对比规则（A-FU-027）', () => {
  it('条件文案：差值 / 变化率 / 序数 / 范围 / 只可开关类型', () => {
    expect(conditionText(efDrop)).toBe('下降 ≥ 10 %');
    expect(
      conditionText({
        ...efDrop,
        fieldUnit: null,
        name: '起搏阈值升高',
        params: { direction: 'RISE', percent: 50 },
        ruleType: 'CHANGE_PCT',
      }),
    ).toBe('上升 > 50%');
    expect(
      conditionText({
        ...efDrop,
        fieldUnit: null,
        params: { grades: 2, reachCodes: ['3'] },
        ruleType: 'ORDINAL_RISE',
      }),
    ).toBe('程度升高 ≥ 2 级，或达到预设档位');
    expect(
      conditionText({
        ...efDrop,
        params: { op: 'LT', value: 40 },
        ruleType: 'OUT_OF_RANGE',
      }),
    ).toBe('＜ 40 %');
    expect(
      conditionText({
        ...efDrop,
        fieldUnit: null,
        params: {},
        ruleType: 'FLIP_TO_YES',
      }),
    ).toBe('由否变是');
  });

  it('无阈值类型（新发事件 / 由否变是）只可开关，表单模型阈值为 null', () => {
    const flip: CompareRule = {
      ...efDrop,
      params: {},
      ruleType: 'FLIP_TO_YES',
    };
    expect(
      toCompareRuleUpdate(flip, { enabled: false, threshold: null }),
    ).toEqual({
      enabled: false,
      params: {},
    });
    expect(
      validateCompareRuleModel(flip, { enabled: false, threshold: null }),
    ).toEqual([]);
  });

  it('编辑表单 → PUT：只替换阈值键、保留预置方向等其余键', () => {
    const update = toCompareRuleUpdate(efDrop, {
      enabled: true,
      threshold: 15,
    });
    expect(update).toEqual({
      enabled: true,
      params: { direction: 'DROP', threshold: 15 },
    });
    expect((update.params as Record<string, unknown>).direction).toBe('DROP');
  });

  it('e21-T20：C 起搏阈值规则（含脉宽参考注键）调阈值时 noteField / noteText 原样保留', () => {
    const raThresholdRise: CompareRule = {
      ...efDrop,
      baselineField: null,
      baselineFieldLabel: null,
      baselineFormCode: null,
      categoryKey: 'threshold',
      fieldLabel: null,
      fieldUnit: 'V',
      formCode: 'C_PROGRAM_FU',
      formName: null,
      message: '心房起搏阈值比上次升高超过 50%',
      name: '心房起搏阈值升高',
      params: {
        direction: 'RISE',
        noteField: 'f309',
        noteText: '脉宽不同，仅供参考',
        percent: 50,
      },
      ruleCode: 'ra_threshold_rise',
      ruleType: 'CHANGE_PCT',
      ruleTypeLabel: '变化率',
      targetField: 'f308',
    };
    const model = toCompareRuleFormModel(raThresholdRise);
    expect(model).toEqual({ enabled: true, threshold: 50 });
    const update = toCompareRuleUpdate(raThresholdRise, {
      enabled: true,
      threshold: 60,
    });
    expect(update.params).toEqual({
      direction: 'RISE',
      noteField: 'f309',
      noteText: '脉宽不同，仅供参考',
      percent: 60,
    });
    expect(
      validateCompareRuleModel(raThresholdRise, {
        enabled: true,
        threshold: 60,
      }),
    ).toEqual([]);
  });

  it('表单校验与后端同口径：空值 / 越界 / 小数位', () => {
    expect(
      validateCompareRuleModel(efDrop, { enabled: true, threshold: null }),
    ).toEqual(['阈值不能为空']);
    expect(
      validateCompareRuleModel(efDrop, { enabled: true, threshold: 0 }).length,
    ).toBeGreaterThan(0);
    expect(
      validateCompareRuleModel(efDrop, { enabled: true, threshold: 10_000 })
        .length,
    ).toBeGreaterThan(0);
    expect(
      validateCompareRuleModel(efDrop, { enabled: true, threshold: 10.123 })
        .length,
    ).toBeGreaterThan(0);
    expect(
      validateCompareRuleModel(efDrop, { enabled: true, threshold: 10 }),
    ).toEqual([]);
  });

  it('序数升级阈值须为 1–5 整数', () => {
    const reflux: CompareRule = {
      ...efDrop,
      fieldUnit: null,
      params: { grades: 2 },
      ruleType: 'ORDINAL_RISE',
    };
    expect(
      validateCompareRuleModel(reflux, { enabled: true, threshold: 2 }),
    ).toEqual([]);
    expect(
      validateCompareRuleModel(reflux, { enabled: true, threshold: 6 }).length,
    ).toBeGreaterThan(0);
    expect(
      validateCompareRuleModel(reflux, { enabled: true, threshold: 2.5 })
        .length,
    ).toBeGreaterThan(0);
  });
});
