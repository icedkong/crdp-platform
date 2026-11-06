/**
 * E16-T06 / A-ATT-005、C-SCAN-015：全屏查看器的纯变换与翻页逻辑（无 DOM）。
 *
 * 工具栏动作（components.md §12）：缩小 / 放大（几何步长，夹在上下限）、旋转（顺时针
 * 90°，360° 归零）、翻转（对**当前画面**做水平镜像——CSS 串里镜像放最左，最后应用）；
 * 翻页 = 多文件上一份 / 下一份（A-ATT-005「翻页」按 §12 的 files/index 契约）。
 * 变换只作用于图片 / PDF / DOCX；音频与仅下载（AMR / XLSX）不参与变换。
 */

import type { PreviewMode } from './typings';

export type { PreviewMode };

/** 缩放 / 旋转参数（设计未定具体档位：取常用几何步长 ×1.25，50%–500%）。 */
export const VIEWER_TRANSFORM = {
  maxScale: 5,
  minScale: 0.5,
  rotateStepDeg: 90,
  zoomStep: 1.25,
} as const;

export interface ViewerTransformState {
  flipX: boolean;
  rotateDeg: number;
  scale: number;
}

export const INITIAL_VIEWER_TRANSFORM: ViewerTransformState = {
  flipX: false,
  rotateDeg: 0,
  scale: 1,
};

const EPSILON = 1e-9;

/** 放大一步（结果夹在 maxScale：步长不会恰好落在边界上，越界一步截到边界）。 */
export function zoomIn(state: ViewerTransformState): ViewerTransformState {
  if (!canZoomIn(state)) {
    return state;
  }
  return {
    ...state,
    scale: Math.min(
      VIEWER_TRANSFORM.maxScale,
      state.scale * VIEWER_TRANSFORM.zoomStep,
    ),
  };
}

/** 缩小一步（结果截到 minScale）。 */
export function zoomOut(state: ViewerTransformState): ViewerTransformState {
  if (!canZoomOut(state)) {
    return state;
  }
  return {
    ...state,
    scale: Math.max(
      VIEWER_TRANSFORM.minScale,
      state.scale / VIEWER_TRANSFORM.zoomStep,
    ),
  };
}

export function canZoomIn(state: ViewerTransformState): boolean {
  return state.scale < VIEWER_TRANSFORM.maxScale - EPSILON;
}

export function canZoomOut(state: ViewerTransformState): boolean {
  return state.scale > VIEWER_TRANSFORM.minScale + EPSILON;
}

/** 顺时针旋转 90°，360° 归零（连点四次回到原样，不提供单独复位按钮）。 */
export function rotateClockwise(
  state: ViewerTransformState,
): ViewerTransformState {
  return {
    ...state,
    rotateDeg: (state.rotateDeg + VIEWER_TRANSFORM.rotateStepDeg) % 360,
  };
}

/** 水平翻转（再点一次还原）。 */
export function flipHorizontally(
  state: ViewerTransformState,
): ViewerTransformState {
  return { ...state, flipX: !state.flipX };
}

/**
 * CSS transform 串。镜像在最左（最后应用）：无论旋转到几度，「翻转」都对当前画面
 * 做左右镜像；uniform scale 与 rotate 交换次序等价。初始状态返回 'none'。
 */
export function viewerTransformCss(state: ViewerTransformState): string {
  const parts: string[] = [];
  if (state.flipX) parts.push('scaleX(-1)');
  if (Math.abs(state.scale - 1) > EPSILON) {
    parts.push(`scale(${trimFloat(state.scale)})`);
  }
  if (state.rotateDeg !== 0) parts.push(`rotate(${state.rotateDeg}deg)`);
  return parts.length > 0 ? parts.join(' ') : 'none';
}

/** 变换是否适用于该预览模式（音频播放器与「仅下载」页不参与缩放 / 旋转 / 翻转）。 */
export function transformEnabledFor(mode: PreviewMode): boolean {
  return mode === 'docx' || mode === 'image' || mode === 'pdf';
}

/** 翻页索引夹取（0 基；count ≤ 1 恒为 0）。 */
export function clampFileIndex(index: number, count: number): number {
  if (count <= 1) {
    return 0;
  }
  return Math.min(Math.max(index, 0), count - 1);
}

/** 翻页指示文案（1 基显示）：「第 2 / 5 份」。 */
export function fileIndicatorText(index: number, count: number): string {
  return `第 ${clampFileIndex(index, count) + 1} / ${Math.max(count, 1)} 份`;
}

/** 缩放百分比显示（125%）。 */
export function zoomPercentText(state: ViewerTransformState): string {
  return `${Math.round(state.scale * 100)}%`;
}

function trimFloat(value: number): string {
  return String(Number.parseFloat(value.toFixed(4)));
}
