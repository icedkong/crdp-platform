// E24-T07 真机兼容（iOS Safari）：100vh 按「大视口」取值（含地址栏收起后的
// 高度），iPhone 上 Safari 工具栏可见时比可视区域高一截——弹窗 maxHeight 用
// calc(100vh - Npx) 时底部内容被工具栏盖住。支持 dvh（iOS Safari 15.4+、
// Chromium 108+）的内核用 100dvh 跟随可视区域，旧内核（微信 XWeb 等）回退
// 100vh 不劣化。
import { afterEach, describe, expect, it, vi } from 'vitest';

import { viewportHeightUnit } from './viewport-units';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('viewportHeightUnit（E24-T07：dvh 可用则用 dvh，否则回退 vh）', () => {
  it('cSS.supports 认 dvh 时返回 dvh（iOS Safari 15.4+ / Chromium 108+）', () => {
    vi.stubGlobal('CSS', {
      supports: (feature: string, value?: string) =>
        feature === 'max-height' && value === '100dvh',
    });
    expect(viewportHeightUnit()).toBe('dvh');
  });

  it('旧内核不认 dvh（微信 XWeb）时回退 vh（与原行为一致，不劣化）', () => {
    vi.stubGlobal('CSS', {
      supports: () => false,
    });
    expect(viewportHeightUnit()).toBe('vh');
  });

  it('无 CSS.supports（极旧内核 / SSR）时回退 vh', () => {
    vi.stubGlobal('CSS', undefined);
    expect(viewportHeightUnit()).toBe('vh');
  });
});
