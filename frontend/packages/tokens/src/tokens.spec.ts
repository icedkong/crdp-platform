import { describe, expect, it } from 'vitest';

/**
 * G4（M3 审查）/ design-system.md §1.1：业务四色（主色 / 成功 / 警告 / 错误）锁定
 * docs/design/tokens.ts 的设计值——Vben 外壳 CSS 变量（色板 500 档 hsl(var(--primary)) 等）
 * 不得回写。锁定 mergeShellTokens 的过滤行为与 app.vue 的合并结果（快照）。
 */
import {
  antdTheme,
  colors,
  mergeShellTokens,
} from '../../../../docs/design/tokens';

/** 模拟 Vben useAntdDesignTokens 的输出（CSS 变量驱动，色板 500 档）。 */
const SHELL_TOKENS: Record<string, unknown> = {
  borderRadius: 8,
  colorBgBase: 'hsl(0 0% 100%)',
  colorBgContainer: 'hsl(0 0% 100%)',
  colorBorder: 'hsl(240 6% 87%)',
  colorError: 'hsl(0 84% 60%)',
  colorInfo: 'hsl(221 83% 53%)',
  colorPrimary: 'hsl(221 83% 53%)',
  colorSuccess: 'hsl(142 76% 36%)',
  colorWarning: 'hsl(32 95% 44%)',
  colorTextBase: 'hsl(0 0% 3.9%)',
  emptyString: '',
};

describe('g4 mergeShellTokens（外壳 CSS 变量过滤）', () => {
  it('剔除业务四色与 info 别名（锁定 tokens.ts 设计值）', () => {
    const merged = mergeShellTokens(SHELL_TOKENS);
    expect(merged).not.toHaveProperty('colorPrimary');
    expect(merged).not.toHaveProperty('colorInfo');
    expect(merged).not.toHaveProperty('colorSuccess');
    expect(merged).not.toHaveProperty('colorWarning');
    expect(merged).not.toHaveProperty('colorError');
  });

  it('外壳其余令牌照常透传（背景 / 边框 / 圆角保持可主题化），空值剔除', () => {
    const merged = mergeShellTokens(SHELL_TOKENS);
    expect(merged.borderRadius).toBe(8);
    expect(merged.colorBgContainer).toBe('hsl(0 0% 100%)');
    expect(merged.colorBorder).toBe('hsl(240 6% 87%)');
    expect(merged).not.toHaveProperty('emptyString');
  });
});

describe('g4 app.vue 合并结果（colorPrimary=#3A75FF 等四色实际生效）', () => {
  it('色板 500 档不覆盖设计值（tokens.ts 快照）', () => {
    const merged = { ...antdTheme.token, ...mergeShellTokens(SHELL_TOKENS) };
    expect(merged.colorPrimary).toBe(colors.primary); // #3A75FF
    expect(merged.colorPrimary).toBe('#3A75FF');
    expect(merged.colorInfo).toBe('#3A75FF');
    expect(merged.colorSuccess).toBe(colors.success); // #1DC29E
    expect(merged.colorSuccess).toBe('#1DC29E');
    expect(merged.colorWarning).toBe(colors.warning); // #FA8C16
    expect(merged.colorWarning).toBe('#FA8C16');
    expect(merged.colorError).toBe(colors.error); // #FF4D4F
    expect(merged.colorError).toBe('#FF4D4F');
  });

  it('antdTheme 基线本身即设计值（防止令牌漂移）', () => {
    expect(antdTheme.token.colorPrimary).toBe('#3A75FF');
    expect(antdTheme.token.colorSuccess).toBe('#1DC29E');
    expect(antdTheme.token.colorWarning).toBe('#FA8C16');
    expect(antdTheme.token.colorError).toBe('#FF4D4F');
  });
});
