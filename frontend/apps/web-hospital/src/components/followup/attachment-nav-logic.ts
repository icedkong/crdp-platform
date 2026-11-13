/**
 * E16-T14 / A-PAT-020、C-PAT-017 · 左栏附件导航与各位置内联缩略图的纯逻辑
 * （components.md §5 AttachmentNav；pages.md §4.2 / §4.5）：
 *
 * - 患者内清单行（ReportRow）→ 附件导航条目（AttachmentNavItem）：无位置报告单
 *   home=both（两个页签都进「未指定位置」组，A-ATT-020）；缩略图 URL 由页面用
 *   首附件预览短时链接懒加载后回填（thumbnailUrl）；
 * - 按位置取条目：基线分类末尾、随访记录整条 / 分组末尾的内联缩略图条与分类 /
 *   分组条的 📎 计数共用（同一报告单在每个所在位置各出现一次）；
 * - 首附件只有图片格式（jpg / png，与全院列表 thumbnailOf 同口径）才换预览
 *   短时链接，其他格式显示文件图标、不发请求。
 */
import type { ReportRow } from '@crdp/api';

import type { AttachmentNavItem, ReportLocation } from '#/reference/types';

import { reportRowDigitizeStatusText } from '@crdp/api';

/** 后端位置标签 → 前端导航结构（不携带 templateVersion，展示不需要）。 */
function toLocation(tag: ReportRow['locations'][number]): ReportLocation {
  const location: ReportLocation = { linkType: tag.linkType };
  if (tag.linkRecordId !== undefined && tag.linkRecordId !== null) {
    location.linkRecordId = tag.linkRecordId;
  }
  if (tag.recordLabel) location.recordLabel = tag.recordLabel;
  if (tag.recordDate) location.recordDate = tag.recordDate;
  if (tag.anchorType !== undefined && tag.anchorType !== null) {
    location.anchorType = tag.anchorType;
  }
  if (tag.anchorKey) location.anchorKey = tag.anchorKey;
  if (tag.anchorLabel) location.anchorLabel = tag.anchorLabel;
  if (tag.adjusted) location.adjusted = true;
  return location;
}

/** 无位置报告单的归属页签（home）：「未指定位置」组在两侧页签都显示（A-PAT-020）。 */
function homeOf(locations: ReportLocation[]): AttachmentNavItem['home'] {
  if (locations.length === 0) return 'both';
  // E21-T32 / C-PAT-017：植入记录在基线资料页签——implant 位置计入 baseline 侧
  const hasBaseline = locations.some(
    (location) =>
      location.linkType === 'baseline' ||
      location.linkType === 'implant_record',
  );
  const hasFollowup = locations.some(
    (location) => location.linkType === 'followup_record',
  );
  if (hasBaseline && hasFollowup) return 'both';
  return hasBaseline ? 'baseline' : 'followup';
}

/** 电子化状态显示文案（C-SCAN-011；A 租户 null 不带）。 */
function digitizeOf(row: Pick<ReportRow, 'digitizeStatus'>) {
  const text = reportRowDigitizeStatusText(
    row.digitizeStatus as 'DONE' | 'NO_ENTRY' | 'PENDING' | null,
  );
  return text === '待录入' || text === '已电子化' || text === '无需录入'
    ? text
    : undefined;
}

/** 患者内清单行 → 附件导航条目（thumbnailUrl 由页面回填）。 */
export function toNavItems(rows: ReportRow[]): AttachmentNavItem[] {
  return rows.map((row) => ({
    digitizeStatus: digitizeOf(row),
    fileCount: row.fileCount,
    home: homeOf(row.locations.map((tag) => toLocation(tag))),
    locations: row.locations.map((tag) => toLocation(tag)),
    name: row.name ?? row.typeName,
    reportDate: row.reportDate,
    reportId: row.id,
    typeCode: row.typeCode,
    typeName: row.typeName,
  }));
}

/** 挂在基线某分类下的条目（分类末尾内联缩略图 / 分类条 📎 计数）。 */
export function itemsAtBaselineAnchor(
  items: AttachmentNavItem[],
  anchorKey: string,
): AttachmentNavItem[] {
  return items
    .filter((item) =>
      item.locations.some(
        (location) =>
          location.linkType === 'baseline' && location.anchorKey === anchorKey,
      ),
    )
    .map((item) => ({
      ...item,
      locations: item.locations.filter(
        (location) =>
          location.linkType === 'baseline' && location.anchorKey === anchorKey,
      ),
    }));
}

/**
 * 挂在某条随访记录下的条目：anchorKey 缺省 = 整条记录（无分组锚点的位置）；
 * 给定 = 该分组末尾。同一报告单可同时挂整条与分组，两处各显示一次。
 */
export function itemsAtRecord(
  items: AttachmentNavItem[],
  recordId: string,
  anchorKey?: string,
): AttachmentNavItem[] {
  const inRecord = (location: ReportLocation) =>
    location.linkType === 'followup_record' &&
    location.linkRecordId === recordId &&
    (anchorKey
      ? location.anchorKey === anchorKey
      : !location.anchorKey && location.anchorType !== 'section');
  return items
    .filter((item) => item.locations.some((loc) => inRecord(loc)))
    .map((item) => ({
      ...item,
      locations: item.locations.filter((loc) => inRecord(loc)),
    }));
}

/**
 * 挂在某条植入记录下的条目（E21-T32 / C-PAT-017）：anchorKey 缺省 = 整条植入记录，
 * 'device' = 器械信息、'lead' = 电极（与 C_IMPLANT 分类键一致）。同一报告单可同时挂
 * 整条与分组，两处各显示一次（与随访记录同口径）。
 */
export function itemsAtImplant(
  items: AttachmentNavItem[],
  implantId: string,
  anchorKey?: string,
): AttachmentNavItem[] {
  const inImplant = (location: ReportLocation) =>
    location.linkType === 'implant_record' &&
    location.linkRecordId === implantId &&
    (anchorKey
      ? location.anchorKey === anchorKey
      : !location.anchorKey && location.anchorType !== 'section');
  return items
    .filter((item) => item.locations.some((loc) => inImplant(loc)))
    .map((item) => ({
      ...item,
      locations: item.locations.filter((loc) => inImplant(loc)),
    }));
}

/** 首附件缩略图候选：只有图片格式（jpg / png）值得换预览短时链接；其余 null。 */
export function thumbnailFileOf(
  row: Pick<ReportRow, 'firstFile'>,
): null | { fileId: string } {
  if (!row.firstFile) return null;
  const format = row.firstFile.format.toLowerCase();
  return format === 'jpg' || format === 'png'
    ? { fileId: row.firstFile.fileId }
    : null;
}
