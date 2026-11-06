/**
 * E20-T01 租户视角进入弹窗纯逻辑测试（views/platform/tenant/view.ts）：
 * 表单模型默认值、倒计时递减、时间列展示。
 */
import { describe, expect, it } from 'vitest';

import { tickRemaining, toEnterViewModel, viewTimeText } from './view';

describe('toEnterViewModel', () => {
  it('默认无目的、说明为空（弹窗打开即校验拦提交）', () => {
    expect(toEnterViewModel()).toEqual({ note: '', purpose: null });
  });
});

describe('tickRemaining', () => {
  it('每秒递减，到 0 停住（刷新由调用方负责）', () => {
    expect(tickRemaining(120)).toBe(119);
    expect(tickRemaining(1)).toBe(0);
    expect(tickRemaining(0)).toBe(0);
  });
});

describe('viewTimeText', () => {
  it('iSO 时间精确到分钟，空值显示占位', () => {
    expect(viewTimeText('2026-11-01T08:30:00+08:00')).toBe('2026-11-01 08:30');
    expect(viewTimeText(null)).toBe('—');
  });
});
