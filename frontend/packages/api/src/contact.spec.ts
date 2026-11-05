/**
 * E15-T04 / A-FU-006、A-FU-025、C-FU-019 · 沟通记录枚举与提示的纯逻辑锁定。
 *
 * 联系结果字典完整（tasks.csv 验收①）：六值与后端 ContactResult 枚举、平台库 V45 预置
 * 字典、components.md §2 contact 域一致；文案与提示（未接通「系统次日自动生成跟进」、
 * 告知已故死亡终止口径）照 components.md §13 原文。
 */
import { describe, expect, it } from 'vitest';

import {
  CONTACT_METHOD_OPTIONS,
  CONTACT_RESULT_OPTIONS,
  CONTACT_RESULT_TEXT,
  contactFollowupHint,
  contactMethodText,
  contactResultText,
} from './contact';

describe('contactResultText（联系结果字典完整，components.md §2 contact 域）', () => {
  it('六值逐一映射中文（A-FU-006 顺序）', () => {
    expect(CONTACT_RESULT_OPTIONS).toEqual([
      { label: '已联系', value: 'REACHED' },
      { label: '未接通', value: 'NOT_REACHED' },
      { label: '号码错误', value: 'WRONG_NUMBER' },
      { label: '拒访', value: 'REFUSED' },
      { label: '家属代答', value: 'FAMILY' },
      { label: '告知已故', value: 'DECEASED' },
    ]);
    expect(Object.keys(CONTACT_RESULT_TEXT)).toHaveLength(6);
  });

  it('未知值原样返回、空值显示「—」（任务列表「最近联系」列无联系时）', () => {
    expect(contactResultText('REACHED')).toBe('已联系');
    expect(contactResultText('DECEASED')).toBe('告知已故');
    expect(contactResultText(null)).toBe('—');
    expect(contactResultText(undefined)).toBe('—');
    expect(contactResultText('CUSTOM')).toBe('CUSTOM');
  });
});

describe('contactMethodText（A-FU-006 电话 / 微信 / 面谈 / 其他）', () => {
  it('四值映射中文', () => {
    expect(CONTACT_METHOD_OPTIONS.map((item) => item.label)).toEqual([
      '电话',
      '微信',
      '面谈',
      '其他',
    ]);
    expect(contactMethodText('PHONE')).toBe('电话');
    expect(contactMethodText(null)).toBe('—');
  });
});

describe('contactFollowupHint（components.md §13 提示原文）', () => {
  it('未接通 / 号码错误提示系统次日自动生成跟进（A-FU-024）', () => {
    expect(contactFollowupHint('NOT_REACHED')).toBe('系统次日自动生成跟进');
    expect(contactFollowupHint('WRONG_NUMBER')).toBe('系统次日自动生成跟进');
  });

  it('告知已故提示死亡终止口径（A-FU-007）', () => {
    expect(contactFollowupHint('DECEASED')).toBe(
      '保存后患者标记为死亡，未完成任务自动终止（A-FU-007）',
    );
  });

  it('其余结果无提示', () => {
    expect(contactFollowupHint('REACHED')).toBe('');
    expect(contactFollowupHint('REFUSED')).toBe('');
    expect(contactFollowupHint('FAMILY')).toBe('');
    expect(contactFollowupHint(null)).toBe('');
  });
});
