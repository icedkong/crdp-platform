/**
 * E10-T04：@crdp/viewer 对外类型（预览内核 + 水印）。
 * PreviewableFile 与 packages/api 的上传 / 预览契约同构（format = 后端真实类型目录编码）。
 */

export type PreviewMode = 'audio' | 'docx' | 'download-only' | 'image' | 'pdf';

export interface PreviewableFile {
  fileId: number | string;
  format?: null | string;
  mime?: null | string;
  name?: null | string;
}

export interface WatermarkViewer {
  account: string;
  displayName: string;
  tenantName: null | string;
}
