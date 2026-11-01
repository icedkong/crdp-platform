import type { LoginLogView } from './brand';

import { describe, expect, it } from 'vitest';

import {
  isValidAssetUrl,
  loginLogResultText,
  THEME_COLOR_OPTIONS,
  themeColorHexOf,
} from './brand';

/**
 * E06-T11 / PLT-CFG-008：预设色板与素材地址放行口径（纯逻辑单测；后端同源枚举见 BrandThemeColor）。
 */
describe('e06-T11 租户个性化常量与校验', () => {
  it('预设色板四色来自 tokens，主色固定为默认项（OQ-23）', () => {
    expect(THEME_COLOR_OPTIONS).toHaveLength(4);
    expect(THEME_COLOR_OPTIONS[0]).toEqual({
      hex: '#3A75FF',
      key: 'BLUE',
      label: '平台蓝（默认）',
    });
    expect(THEME_COLOR_OPTIONS.map((color) => color.key)).toEqual([
      'BLUE',
      'TEAL',
      'ORANGE',
      'RED',
    ]);
  });

  it('themeColorHexOf 未知或空值回落默认主色', () => {
    expect(themeColorHexOf('TEAL')).toBe('#1DC29E');
    expect(themeColorHexOf('BLUE')).toBe('#3A75FF');
    expect(themeColorHexOf(null)).toBe('#3A75FF');
    expect(themeColorHexOf('PURPLE')).toBe('#3A75FF');
  });

  it('素材地址只放行 http(s) 与站内路径（拒绝 data:/javascript: 注入登录页）', () => {
    expect(isValidAssetUrl('https://assets.example.com/logo.png')).toBe(true);
    expect(isValidAssetUrl('http://cdn.example.com/bg.svg')).toBe(true);
    expect(isValidAssetUrl('/assets/tenant/logo.svg')).toBe(true);
    expect(isValidAssetUrl('')).toBe(true);
    expect(isValidAssetUrl(null)).toBe(true);

    expect(isValidAssetUrl('javascript:alert(1)')).toBe(false);
    expect(isValidAssetUrl('data:image/png;base64,xxx')).toBe(false);
    expect(isValidAssetUrl('ftp://x/y.png')).toBe(false);
    expect(isValidAssetUrl('assets/logo.png')).toBe(false);
  });

  it('登录记录结果文案只有成功 / 失败，不展示原因代码', () => {
    const ok: LoginLogView = {
      createdAt: '2027-01-04T09:00:00+08:00',
      message: 'ok',
      success: true,
    };
    const bad: LoginLogView = {
      createdAt: '2027-01-04T09:01:00+08:00',
      message: 'bad-password',
      success: false,
    };
    expect(loginLogResultText(ok)).toBe('成功');
    expect(loginLogResultText(bad)).toBe('失败');
  });
});
