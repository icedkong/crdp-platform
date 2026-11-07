import type { CompareRule } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  applyUpdate,
  isSwitchOnly,
  rowKeyOf,
  savedMessage,
  thresholdControl,
} from './logic';

/** E17-T01 / A-FU-027：对比规则页纯函数测试。全部虚构数据。 */
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

describe('对比规则页逻辑（E17-T01）', () => {
  it('阈值控件：差值 / 变化率 / 序数 / 范围有标签与提示；只可开关类型为 null', () => {
    expect(thresholdControl('DIFF')?.label).toBe('差值阈值');
    expect(thresholdControl('CHANGE_PCT')?.label).toBe('变化率阈值（%）');
    expect(thresholdControl('ORDINAL_RISE')?.label).toBe('升级级数');
    expect(thresholdControl('OUT_OF_RANGE')?.label).toBe('边界值');
    expect(thresholdControl('NEW_EVENT')).toBeNull();
    expect(thresholdControl('FLIP_TO_YES')).toBeNull();
    expect(thresholdControl('VALUE_HIT')).toBeNull();
    expect(isSwitchOnly('FLIP_TO_YES')).toBe(true);
    expect(isSwitchOnly('DIFF')).toBe(false);
  });

  it('保存后原位替换列表行、行键稳定', () => {
    const list: CompareRule[] = [
      efDrop,
      { ...efDrop, id: 12, name: '左室扩大', ruleCode: 'lvdd_rise' },
    ];
    const saved: CompareRule = {
      ...efDrop,
      enabled: false,
      params: { direction: 'DROP', threshold: 15 },
    };
    const next = applyUpdate(list, saved);
    expect(next[0]).toEqual(saved);
    expect(next[1]?.ruleCode).toBe('lvdd_rise');
    expect(rowKeyOf(saved)).toBe('11');
  });

  it('保存成功提示带规则名与审计口径', () => {
    expect(savedMessage('射血分数下降')).toBe(
      '已保存「射血分数下降」对比规则，并记录操作日志',
    );
  });
});
