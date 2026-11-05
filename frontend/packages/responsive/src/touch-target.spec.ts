import { breakpoints, deviceOf, globalCss, sizes } from '@crdp/tokens';
/**
 * E24-T01 · 断点与触控令牌单测（验收：768 / 1280 断点、可点区域 ≥ 40 px）。
 *
 * 锁定 tokens.ts（唯一令牌来源）：breakpoints 数值、deviceOf 三档边界、
 * globalCss 手机媒体查询里的 ≥40px 触控规则（按钮 / 图标按钮 / 分页）。
 */
import { describe, expect, it } from 'vitest';

describe('断点令牌（NFR-RESP-001）', () => {
  it('breakpoints 为 768 / 1280', () => {
    expect(breakpoints.tablet).toBe(768);
    expect(breakpoints.desktop).toBe(1280);
  });

  it.each([
    [767, 'mobile'],
    [768, 'tablet'],
    [1279, 'tablet'],
    [1280, 'desktop'],
  ])('deviceOf(%d) → %s', (width, device) => {
    expect(deviceOf(width)).toBe(device);
  });
});

describe('触控规范（可点区域 ≥ 40 px）', () => {
  it('touchTarget 令牌为 40', () => {
    expect(sizes.touchTarget).toBe(40);
  });

  it('globalCss 手机媒体查询内按钮（含图标按钮）与分页 ≥ 40px', () => {
    const mobile = globalCss.slice(
      globalCss.indexOf('@media (max-width: 767px)'),
    );
    expect(mobile).toContain(
      `.ant-btn { min-height: ${sizes.touchTarget}px; }`,
    );
    expect(mobile).toContain(
      `.ant-btn.ant-btn-icon-only { min-width: ${sizes.touchTarget}px; }`,
    );
    expect(mobile).toContain(
      `.ant-pagination .ant-pagination-item { min-width: ${sizes.touchTarget}px; min-height: ${sizes.touchTarget}px; }`,
    );
  });

  it('globalCss 提供 .crdp-touch 工具类（触控可点区域兜底）', () => {
    expect(globalCss).toContain(
      `.crdp-touch { min-width: ${sizes.touchTarget}px; min-height: ${sizes.touchTarget}px; }`,
    );
  });
});
