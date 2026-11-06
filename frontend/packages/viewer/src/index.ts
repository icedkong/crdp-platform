/**
 * @crdp/viewer：报告单 / 录音的预览内核与全屏查看器（E10-T04 / E16-T06）。
 *
 * 提供：可见水印（不可关闭、防篡改）、按格式选择预览方式（pdf.js / docx-preview /
 * 原生 audio）、PreviewContent（内容面）与 FilePreview（带水印页内小预览）、
 * FullscreenViewer（全屏外壳：缩放 / 旋转 / 翻转 / 多文件翻页 + 水印，A-ATT-005、
 * C-SCAN-015）。内容 URL 一律来自后端预览代理短时链接
 * （GET /api/hospital/files/{fileId}/preview-url，report.md §1），前端不缓存。
 */
export { default as FilePreview } from './file-preview.vue';
export {
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
  zoomPercentText,
} from './fullscreen';
export type { ViewerTransformState } from './fullscreen';
export { default as FullscreenViewer } from './fullscreen-viewer.vue';
export { downloadOnlyReason, previewModeOf } from './preview';
export type { PreviewDecision } from './preview';
export { default as PreviewContent } from './preview-content.vue';
export { renderDocxDocument, renderPdfDocument } from './renderers';
export type { PreviewableFile, PreviewMode, WatermarkViewer } from './typings';
export {
  createWatermarkGuard,
  renderWatermarkTile,
  WATERMARK_STYLE,
  watermarkText,
} from './watermark';
export type { WatermarkGuard, WatermarkGuardOptions } from './watermark';
