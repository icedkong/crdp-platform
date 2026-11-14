/**
 * E21-T16 / C-SCAN-009 · 待归档区列表页（/followup/report-inbox，pages.md §6.4
 * 「报告单与资料」的 P1 扩展，开关 report_inbox）的纯逻辑。
 *
 * - 检索请求装配：筛选（上传日期、上传人）→ POST /followup/report-inbox/search body
 *   （空筛选项不提交）；分页由页面另附；
 * - 缩略图列：图片（jpg / png）走预览短时链接，非图片显示文件类型图标；
 * - 时间显示：后端 DATETIME 串（空格分隔）与 ISO（T 分隔）统一为「YYYY-MM-DD HH:mm」；
 * - 上传队列（先扫描）：N 个文件全部上传完成后才允许「放入待归档区」（一次 1–50 个，
 *   与后端 40001 口径一致）；
 * - 归档入口显隐：followup:scan:upload 权限（归档 / 放入动作同码，前端隐藏不能代替
 *   后端校验，AGENTS §5.9）。
 */
import type { ReportInboxRow, ReportInboxSearchRequest } from '@crdp/api';

import { sizes } from '@crdp/tokens';

/** 页面筛选状态（表单双向绑定；range 值为 [from, to] ISO 日期）。 */
export interface InboxListFilters {
  uploadedRange: [string, string] | null;
  uploaderId: null | string;
}

export const EMPTY_FILTERS: InboxListFilters = {
  uploadedRange: null,
  uploaderId: null,
};

/** 筛选 → 检索请求（空项不提交，交给后端缺省口径；分页由页面另附）。 */
export function buildInboxSearchPayload(
  filters: InboxListFilters,
): Omit<ReportInboxSearchRequest, 'pageNo' | 'pageSize'> {
  return {
    creatorId: filters.uploaderId ?? undefined,
    uploadedFrom: filters.uploadedRange?.[0] || undefined,
    uploadedTo: filters.uploadedRange?.[1] || undefined,
  };
}

/** 缩略图渲染形态：图片走预览短时链接（懒加载），其他显示文件类型；无文件 null（防御）。 */
export type InboxThumbKind =
  | null
  | { fileId: string; kind: 'image' }
  | { kind: 'file'; label: string };

export function inboxThumbOf(row: ReportInboxRow): InboxThumbKind {
  const format = row.format.toLowerCase();
  if (format === 'jpg' || format === 'png') {
    return { kind: 'image', fileId: row.fileId };
  }
  return { kind: 'file', label: row.format.toUpperCase() };
}

/** 后端时间串（「2026-10-28 09:30:00[.fff]」或 ISO T 分隔）→「YYYY-MM-DD HH:mm」。 */
export function formatUploadedAt(value: string): string {
  const normalized = value.replaceAll('T', ' ').replaceAll(' ', 'T');
  return normalized.slice(0, 16).replaceAll('T', ' ');
}

/** 桌面列（文件 / 格式 / 大小 / 上传人 / 上传时间 / 操作）。 */
export const DESKTOP_COLUMNS = [
  { dataIndex: 'thumb', title: '文件', width: 84 },
  { dataIndex: 'fileName', title: '文件名' },
  { dataIndex: 'formatText', title: '格式', width: 90 },
  { dataIndex: 'sizeText', title: '大小', width: 90 },
  { dataIndex: 'uploaderName', title: '上传人', width: 110 },
  { dataIndex: 'createdAtText', title: '上传时间', width: 140 },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 130 },
];

/** 表格每页条数（AGENTS §7「表格每页 20 条」）。 */
export const PAGE_SIZE = sizes.tablePageSize;

/** 进区一次的文件数上限（与后端 MAX_INTAKE_FILES 同口径：一次 ≤ 50 个）。 */
export const MAX_INTAKE_FILES = 50;

/** 待进区文件（先扫描）：上传完成才计入 fileIds；失败可移除后重选。 */
export interface InboxUploadItem {
  fileId: string;
  name: string;
  status: 'done' | 'failed' | 'uploading';
}

/** 「放入待归档区」是否可提交：至少 1 个上传完成、无在途、总数 ≤ 50。 */
export function intakeReady(items: InboxUploadItem[]): {
  fileIds: string[];
  problem: null | string;
} {
  if (items.some((item) => item.status === 'uploading')) {
    return { fileIds: [], problem: '仍有文件在上传，请稍候' };
  }
  const fileIds = items
    .filter((item) => item.status === 'done')
    .map((item) => item.fileId);
  if (fileIds.length === 0) {
    return { fileIds: [], problem: '请先选择并上传扫描件' };
  }
  if (items.length > MAX_INTAKE_FILES) {
    return { fileIds: [], problem: `一次最多放入 ${MAX_INTAKE_FILES} 个文件` };
  }
  return { fileIds, problem: null };
}
