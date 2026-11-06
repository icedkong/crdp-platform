/**
 * E20-T01 + E20-T04 租户视角进入弹窗纯逻辑测试（views/platform/tenant/view.ts）：
 * 表单模型默认值、倒计时递减、时间列展示、打开医院端视角页的入口地址（PLT-VIEW-002）。
 */
import { describe, expect, it } from 'vitest';

import {
  openHospitalView,
  tickRemaining,
  toEnterViewModel,
  viewTimeText,
} from './view';

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

// ---------------------------------------------------------------- E20-T04 / PLT-VIEW-002：打开医院端

describe('openHospitalView（打开医院端视角页）', () => {
  it('凭据只放 URL 片段：hospitalUrl + /view/entry#ticket=…', async () => {
    const opened: string[] = [];
    const url = await openHospitalView(
      { hospitalUrl: 'http://127.0.0.1:14173/', ticket: 'tk-1_2' },
      (target) => opened.push(target),
    );
    expect(url).toBe('http://127.0.0.1:14173/view/entry#ticket=tk-1_2');
    expect(opened).toEqual([url]);
  });
});
