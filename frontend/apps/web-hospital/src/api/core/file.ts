/**
 * E10-T04 / A-FU-025：文件预览（docs/api/report.md §1）。
 * 预览短时链接每次预览重新获取（≤ 5 分钟、写审计），前端不缓存；
 * 取流地址是相对路径，拼 API 基地址后给 pdf.js / docx-preview / 原生 audio 直用。
 */
import type { FilePreviewGrant } from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/** 取预览短时链接（登录即可 + followup 模块开通；文件不存在 / 已删除 40400）。 */
export function getFilePreviewUrl(fileId: number | string) {
  return requestClient.get<FilePreviewGrant>(
    apiPath(`/files/${fileId}/preview-url`),
  );
}
