/**
 * @crdp/viewer：报告单 / 录音的页内预览内核（E10-T04）。
 *
 * 提供：可见水印（不可关闭、防篡改）、按格式选择预览方式（pdf.js / docx-preview / 原生 audio）、 FilePreview
 * 组件。内容 URL 一律来自后端预览代理短时链接（GET /api/hospital/files/{fileId}/preview-url， report.md §1），
 * 前端不缓存。全屏外壳（缩放 / 旋转 / 翻转工具栏）随 E16-T06。
 */
export { default as FilePreview } from './file-preview.vue';
export { downloadOnlyReason, previewModeOf } from './preview';
export type { PreviewDecision } from './preview';
export { renderDocxDocument, renderPdfDocument } from './renderers';
export type { PreviewableFile, PreviewMode, WatermarkViewer } from './typings';
export {
  createWatermarkGuard,
  renderWatermarkTile,
  WATERMARK_STYLE,
  watermarkText,
} from './watermark';
export type { WatermarkGuard, WatermarkGuardOptions } from './watermark';
