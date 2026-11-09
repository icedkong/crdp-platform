import type { PreviewMode } from './typings';

// E24-T06 / NFR-RESP-003：双指缩放纯逻辑（components.md §12「手机 / 平板：全屏，
// 双指缩放（图片）」）——两指距离的比例乘进当前 scale（与工具栏按钮共用上下限
// 50%–500%），旋转 / 翻转保持不变；只对图片启用（PDF / DOCX 用工具栏按钮缩放）。
import { describe, expect, it } from 'vitest';

import { INITIAL_VIEWER_TRANSFORM, VIEWER_TRANSFORM } from './fullscreen';
import {
  clampPinchRatio,
  pinchEnabledFor,
  pinchScale,
  touchDistance,
} from './pinch';

describe('e24-t06 pinch（双指缩放纯逻辑）', () => {
  it('两指触点距离：欧氏距离（勾股）', () => {
    expect(
      touchDistance({ clientX: 0, clientY: 0 }, { clientX: 30, clientY: 40 }),
    ).toBe(50);
    expect(
      touchDistance(
        { clientX: 100, clientY: 50 },
        { clientX: 100, clientY: 50 },
      ),
    ).toBe(0);
    expect(
      touchDistance({ clientX: 10, clientY: 10 }, { clientX: 13, clientY: 14 }),
    ).toBeCloseTo(5);
  });

  it('距离比例 → 缩放：捏开 2 倍距离 = 2 倍 scale；捏拢减半', () => {
    const opened = pinchScale(INITIAL_VIEWER_TRANSFORM, 100, 200);
    expect(opened.scale).toBeCloseTo(2);
    const closed = pinchScale(
      { ...INITIAL_VIEWER_TRANSFORM, scale: 2 },
      200,
      100,
    );
    expect(closed.scale).toBeCloseTo(1);
  });

  it('缩放夹在上下限（50%–500%，与工具栏按钮同口径）', () => {
    const maxed = pinchScale(INITIAL_VIEWER_TRANSFORM, 10, 10_000);
    expect(maxed.scale).toBe(VIEWER_TRANSFORM.maxScale);
    const minned = pinchScale(
      { ...INITIAL_VIEWER_TRANSFORM, scale: 0.6 },
      1000,
      1,
    );
    expect(minned.scale).toBe(VIEWER_TRANSFORM.minScale);
  });

  it('旋转 / 翻转随双指缩放保留（先旋转再捏开，画面仍旋转着放大）', () => {
    const rotated = { ...INITIAL_VIEWER_TRANSFORM, rotateDeg: 90, flipX: true };
    const next = pinchScale(rotated, 100, 150);
    expect(next.scale).toBeCloseTo(1.5);
    expect(next.rotateDeg).toBe(90);
    expect(next.flipX).toBe(true);
  });

  it('起手距离为 0（同点两指）时不动（避免除零）', () => {
    const next = pinchScale(INITIAL_VIEWER_TRANSFORM, 0, 100);
    expect(next.scale).toBe(1);
  });

  it('clampPinchRatio：比例非法（NaN / 负数）按 1 处理', () => {
    expect(clampPinchRatio(Number.NaN)).toBe(1);
    expect(clampPinchRatio(-2)).toBe(1);
    expect(clampPinchRatio(1.5)).toBe(1.5);
  });

  it('只对图片启用双指缩放（PDF / DOCX 用工具栏按钮；音频 / 仅下载不参与）', () => {
    const expected: Array<[PreviewMode, boolean]> = [
      ['image', true],
      ['pdf', false],
      ['docx', false],
      ['audio', false],
      ['download-only', false],
    ];
    for (const [mode, enabled] of expected) {
      expect(pinchEnabledFor(mode)).toBe(enabled);
    }
  });
});
