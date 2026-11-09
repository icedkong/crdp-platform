/**
 * E24-T06 / NFR-RESP-003：双指缩放纯逻辑（无 DOM）。components.md §12「手机 / 平板：
 * 全屏，双指缩放（图片）」——两指触点的距离比例乘进当前 scale，与工具栏「放大 /
 缩小」共用同一上下限（VIEWER_TRANSFORM 50%–500%）；旋转 / 翻转保持不变。
 * 只对图片启用（PDF / DOCX 仍用工具栏按钮；音频 / 仅下载不参与变换）。
 */

import type { ViewerTransformState } from './fullscreen';
import type { PreviewMode } from './typings';

import { VIEWER_TRANSFORM } from './fullscreen';

export type { PreviewMode };

/** 触点（Touch 的最小面：距离只用到 clientX / clientY）。 */
export interface TouchPoint {
  clientX: number;
  clientY: number;
}

/** 两指距离（欧氏）：双指起手与移动各取一次，比例即缩放倍数。 */
export function touchDistance(a: TouchPoint, b: TouchPoint): number {
  return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
}

/** 距离比例归一：非法值（NaN / 负数）按 1 处理（不动）。 */
export function clampPinchRatio(ratio: number): number {
  return Number.isFinite(ratio) && ratio > 0 ? ratio : 1;
}

/**
 * 双指缩放：state.scale × (current / start)，夹在 [minScale, maxScale]；
 * 起手距离为 0（同点两指）时不动（避免除零）。旋转 / 翻转原样保留。
 */
export function pinchScale(
  state: ViewerTransformState,
  startDistance: number,
  currentDistance: number,
): ViewerTransformState {
  if (startDistance <= 0) {
    return state;
  }
  const ratio = clampPinchRatio(currentDistance / startDistance);
  const scale = Math.min(
    VIEWER_TRANSFORM.maxScale,
    Math.max(VIEWER_TRANSFORM.minScale, state.scale * ratio),
  );
  return { ...state, scale };
}

/** 双指缩放只对图片启用（components.md §12「双指缩放（图片）」）。 */
export function pinchEnabledFor(mode: PreviewMode): boolean {
  return mode === 'image';
}
