/**
 * E10-T04 / A-FU-025：文件预览（docs/api/report.md §1）。
 * 预览短时链接每次预览重新获取（≤ 5 分钟、写审计），前端不缓存；
 * 取流地址是相对路径，拼 API 基地址后给 pdf.js / docx-preview / 原生 audio 直用。
 */
import type {
  FileDownloadGrant,
  FilePreviewGrant,
  FileUploadPolicy,
} from '@crdp/api';

import { apiPath, requestClient } from '#/api/request';

/**
 * E10-T01 上传结果（POST /api/hospital/files；真实类型与大小由后端按内容校验）。
 * 录音上传后拿 fileId，随沟通记录创建一并提交（audioFileIds，E15-T04）。
 */
export interface FileUploadResult {
  fileId: string;
  format: string;
  mime: string;
  originalName: string;
  pages?: null | number;
  sha256: string;
  size: number;
}

/** 上传单个文件（multipart 字段 file；需要 followup:scan:upload）。 */
export function uploadFile(file: File) {
  const form = new FormData();
  form.append('file', file);
  return requestClient.post<FileUploadResult>(apiPath('/files'), form);
}

/**
 * 本院上传策略（E10-T01 / PLT-CFG-010，登录即可）：格式白名单、各类别大小上限与
 * 每份报告单文件数上限——上传对话框做前置提示与数量拦截（E16-T03 / A-ATT-004）。
 */
export function fetchUploadPolicy() {
  return requestClient.get<FileUploadPolicy>(apiPath('/files/policy'));
}

/** 取预览短时链接（登录即可 + followup 模块开通；文件不存在 / 已删除 40400）。 */
export function getFilePreviewUrl(fileId: number | string) {
  return requestClient.get<FilePreviewGrant>(
    apiPath(`/files/${fileId}/preview-url`),
  );
}

/**
 * 取下载签名链接（E10-T05，followup:scan:download 敏感权限；5 分钟有效，后端写
 * DOWNLOAD 审计）。录音下载（AMR 只保存与下载）与报告单下载共用本入口。
 */
export function getFileDownloadUrl(fileId: number | string) {
  return requestClient.post<FileDownloadGrant>(
    apiPath(`/files/${fileId}/download-url`),
    {},
  );
}
