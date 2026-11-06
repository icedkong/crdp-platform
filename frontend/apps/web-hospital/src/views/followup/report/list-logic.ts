/**
 * E16-T05 / A-ATT-016、C-SCAN-014 · 全院报告单列表页（pages.md §6.4）的纯逻辑。
 *
 * - 检索请求装配：筛选（患者关键词、类型、报告时间、上传日期、上传人、位置含「未指定位置」、
 *   电子化状态、病区）→ POST /followup/reports/search body（空筛选项不提交）；
 * - 位置筛选选项（全部 / 含基线位置 / 含随访记录位置 / 未指定位置）；
 * - 缩略图列：首附件为图片（jpg / png）走预览短时链接，非图片显示文件类型图标，
 *   无附件显示「—」；
 * - 时间显示：后端 DATETIME 串（空格分隔）与 ISO（T 分隔）统一为「YYYY-MM-DD HH:mm」；
 * - 响应式列集：平板保留主要列（pages.md §6.4），手机卡片列表由页面渲染；
 * - 行点击 → 患者档案报告单页签（/followup/patient/:id?tab=reports，新标签页）。
 */
import type {
  ReportLocationScope,
  ReportSearchFirstFile,
  ReportSearchRequest,
  ReportSearchRow,
} from '@crdp/api';

import { sizes } from '@crdp/tokens';

/** 页面筛选状态（表单双向绑定；range 值为 [from, to] ISO 日期）。 */
export interface ReportListFilters {
  digitizeStatus: null | string;
  keyword: string;
  locationScope: null | ReportLocationScope;
  reportRange: [string, string] | null;
  typeCode: null | string;
  uploadedRange: [string, string] | null;
  uploaderId: null | string;
  wardIds: string[];
}

export const EMPTY_FILTERS: ReportListFilters = {
  digitizeStatus: null,
  keyword: '',
  locationScope: null,
  reportRange: null,
  typeCode: null,
  uploadedRange: null,
  uploaderId: null,
  wardIds: [],
};

/** 位置筛选选项（A-ATT-016「位置（含『未指定位置』）」）。 */
export const LOCATION_SCOPE_OPTIONS: Array<{
  label: string;
  value: ReportLocationScope;
}> = [
  { label: '含基线位置', value: 'BASELINE' },
  { label: '含随访记录位置', value: 'FOLLOWUP' },
  { label: '未指定位置', value: 'UNASSIGNED' },
];

/** 电子化状态选项（C-SCAN-014；A 租户行值为 null，筛选结果为空属预期）。 */
export const DIGITIZE_STATUS_OPTIONS = [
  { label: '待录入', value: 'PENDING' },
  { label: '已电子化', value: 'DONE' },
  { label: '无需录入', value: 'NO_ENTRY' },
];

/** 筛选 → 检索请求（空项不提交，交给后端缺省口径；分页由页面另附）。 */
export function buildSearchPayload(
  filters: ReportListFilters,
): Omit<ReportSearchRequest, 'pageNo' | 'pageSize'> {
  const keyword = filters.keyword.trim();
  return {
    creatorId: filters.uploaderId ?? undefined,
    digitizeStatus:
      (filters.digitizeStatus as ReportSearchRequest['digitizeStatus']) ??
      undefined,
    keyword: keyword || undefined,
    locationScope: filters.locationScope ?? undefined,
    reportDateFrom: filters.reportRange?.[0] || undefined,
    reportDateTo: filters.reportRange?.[1] || undefined,
    typeCode: filters.typeCode ?? undefined,
    uploadedFrom: filters.uploadedRange?.[0] || undefined,
    uploadedTo: filters.uploadedRange?.[1] || undefined,
    wardIds: filters.wardIds.length > 0 ? filters.wardIds : undefined,
  };
}

/** 缩略图渲染形态：图片走预览短时链接（懒加载），其他显示文件类型，无附件 null。 */
export type ThumbnailKind =
  | null
  | { file: ReportSearchFirstFile; kind: 'image' }
  | { kind: 'file'; label: string };

export function thumbnailOf(row: ReportSearchRow): ThumbnailKind {
  if (!row.firstFile) return null;
  const format = row.firstFile.format.toLowerCase();
  if (format === 'jpg' || format === 'png') {
    return { kind: 'image', file: row.firstFile };
  }
  return { kind: 'file', label: row.firstFile.format.toUpperCase() };
}

/** 后端时间串（「2026-10-28 09:30:00[.fff]」或 ISO T 分隔）→「YYYY-MM-DD HH:mm」。 */
export function formatUploadedAt(value: string): string {
  const normalized = value.replaceAll('T', ' ').replaceAll(' ', 'T');
  return normalized.slice(0, 16).replaceAll('T', ' ');
}

/** 桌面列（pages.md §6.4 线框：缩略图 / 患者 / 类型 / 名称 / 时间 / 位置 / 电子化 / 上传人 / 上传时间 / 操作）。 */
export const DESKTOP_COLUMNS = [
  { dataIndex: 'thumb', title: '缩略图', width: 84 },
  { dataIndex: 'patientName', title: '患者' },
  { dataIndex: 'typeName', title: '类型', width: 110 },
  { dataIndex: 'name', title: '名称' },
  { dataIndex: 'reportDate', title: '时间', width: 110 },
  { dataIndex: 'locations', title: '位置' },
  { dataIndex: 'digitizeStatus', title: '电子化', width: 96 },
  { dataIndex: 'creatorName', title: '上传人', width: 110 },
  { dataIndex: 'createdAtText', title: '上传时间', width: 140 },
  { dataIndex: 'action', fixed: 'right' as const, title: '操作', width: 88 },
];

/** 平板保留主要列（pages.md §6.4 响应式；缩略图 / 患者 / 类型 / 时间 / 位置 / 电子化）。 */
export const TABLET_COLUMNS = [
  { dataIndex: 'thumb', title: '缩略图', width: 84 },
  { dataIndex: 'patientName', title: '患者' },
  { dataIndex: 'typeName', title: '类型', width: 110 },
  { dataIndex: 'reportDate', title: '时间', width: 110 },
  { dataIndex: 'locations', title: '位置' },
  { dataIndex: 'digitizeStatus', title: '电子化', width: 96 },
];

/** 行点击 → 患者档案报告单页签（A-ATT-016「点击进入该患者的报告单页签」，新标签页）。 */
export function patientReportsPath(patientId: string): string {
  return `/followup/patient/${patientId}?tab=reports`;
}

/** 表格每页条数（AGENTS §7「表格每页 20 条」）。 */
export const PAGE_SIZE = sizes.tablePageSize;

/** 附件数显示（0 →「—」）。 */
export function fileCountText(row: ReportSearchRow): string {
  return row.fileCount > 0 ? String(row.fileCount) : '—';
}

/** 患者副行（病案号脱敏值 + 随访编号，C-SCAN-014 按「姓名、病历号、随访编号」识别患者）。 */
export function patientSubText(row: ReportSearchRow): string {
  return row.visitNo ? `${row.mrn} · ${row.visitNo}` : row.mrn;
}
