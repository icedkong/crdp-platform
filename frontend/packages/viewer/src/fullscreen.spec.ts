// E16-T06 / A-ATT-005、C-SCAN-015：全屏查看器的纯变换逻辑（缩放 / 旋转 / 翻转 /
// 多文件翻页）——不碰 DOM，供 FullscreenViewer 与后续移动端双指缩放（E24-T06）复用。
import { describe, expect, it } from 'vitest';

import {
  canZoomIn,
  canZoomOut,
  clampFileIndex,
  fileIndicatorText,
  flipHorizontally,
  INITIAL_VIEWER_TRANSFORM,
  rotateClockwise,
  transformEnabledFor,
  VIEWER_TRANSFORM,
  viewerTransformCss,
  zoomIn,
  zoomOut,
} from './fullscreen';

describe('e16-t06 全屏查看器变换逻辑', () => {
  it('初始变换：100% 不旋转不翻转，CSS 为 none', () => {
    expect(INITIAL_VIEWER_TRANSFORM).toEqual({
      flipX: false,
      rotateDeg: 0,
      scale: 1,
    });
    expect(viewerTransformCss(INITIAL_VIEWER_TRANSFORM)).toBe('none');
  });

  it('缩放：按步长倍增 / 倍减，并夹在上下限内', () => {
    const once = zoomIn(INITIAL_VIEWER_TRANSFORM);
    expect(once.scale).toBeCloseTo(1.25, 10);
    const twice = zoomIn(zoomIn(INITIAL_VIEWER_TRANSFORM));
    expect(twice.scale).toBeCloseTo(1.25 * 1.25, 10);
    const out = zoomOut(INITIAL_VIEWER_TRANSFORM);
    expect(out.scale).toBeCloseTo(0.8, 10);

    // 连点放大封顶 maxScale，不再增长且 canZoomIn=false
    let state = INITIAL_VIEWER_TRANSFORM;
    for (let i = 0; i < 30; i++) state = zoomIn(state);
    expect(state.scale).toBe(VIEWER_TRANSFORM.maxScale);
    expect(canZoomIn(state)).toBe(false);
    expect(zoomIn(state)).toBe(state);

    // 连点缩小触底 minScale
    let small = INITIAL_VIEWER_TRANSFORM;
    for (let i = 0; i < 30; i++) small = zoomOut(small);
    expect(small.scale).toBe(VIEWER_TRANSFORM.minScale);
    expect(canZoomOut(small)).toBe(false);
    expect(zoomOut(small)).toBe(small);
  });

  it('旋转：每次顺时针 90°，360° 归零', () => {
    expect(rotateClockwise(INITIAL_VIEWER_TRANSFORM).rotateDeg).toBe(90);
    expect(
      rotateClockwise(rotateClockwise(INITIAL_VIEWER_TRANSFORM)).rotateDeg,
    ).toBe(180);
    let quarter = INITIAL_VIEWER_TRANSFORM;
    for (let i = 0; i < 4; i++) {
      quarter = rotateClockwise(quarter);
    }
    expect(quarter.rotateDeg).toBe(0);
  });

  it('翻转：水平镜像来回切换', () => {
    const flipped = flipHorizontally(INITIAL_VIEWER_TRANSFORM);
    expect(flipped.flipX).toBe(true);
    expect(flipHorizontally(flipped).flipX).toBe(false);
  });

  it('cSS 变换串：镜像在最左（对当前画面做水平翻转），缩放与旋转在内层', () => {
    const css = viewerTransformCss({
      flipX: true,
      rotateDeg: 90,
      scale: 1.25,
    });
    expect(css).toBe('scaleX(-1) scale(1.25) rotate(90deg)');
    // 只缩放时不含镜像 / 旋转片段
    expect(
      viewerTransformCss({ flipX: false, rotateDeg: 0, scale: 1.25 }),
    ).toBe('scale(1.25)');
    expect(viewerTransformCss({ flipX: true, rotateDeg: 0, scale: 1 })).toBe(
      'scaleX(-1)',
    );
  });

  it('变换适用范围：图片 / PDF / DOCX 可变换，音频与仅下载不适用', () => {
    expect(transformEnabledFor('image')).toBe(true);
    expect(transformEnabledFor('pdf')).toBe(true);
    expect(transformEnabledFor('docx')).toBe(true);
    expect(transformEnabledFor('audio')).toBe(false);
    expect(transformEnabledFor('download-only')).toBe(false);
  });

  it('翻页：索引夹取与指示文案', () => {
    expect(clampFileIndex(-1, 3)).toBe(0);
    expect(clampFileIndex(2, 3)).toBe(2);
    expect(clampFileIndex(5, 3)).toBe(2);
    expect(clampFileIndex(0, 0)).toBe(0);
    expect(fileIndicatorText(0, 1)).toBe('第 1 / 1 份');
    expect(fileIndicatorText(1, 3)).toBe('第 2 / 3 份');
  });
});
