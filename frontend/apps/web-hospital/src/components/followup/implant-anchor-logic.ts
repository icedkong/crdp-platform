/**
 * E21-T32 / C-PAT-017、C-PAT-018 · 基线资料页签植入记录区块的锚点与分组条纯逻辑：
 *
 * - 锚点 key（`#crdp-anchor-<key>`，与基线分类 / 随访分组同一约定）：整条植入记录
 *   `implant-<id>`、器械信息 `implant-<id>-device`、电极 `implant-<id>-lead`；
 *   左栏位置标签跳转与分组条点击共用（anchor-nav 的 jumpToAnchor / categoryHash）；
 * - 分组条条目（C-PAT-018：患者登记各分组 + 植入记录的器械信息与电极，显示已填 / 应填）：
 *   器械信息 = 展示字段中非空的个数 ÷ 总数；电极 = 已录根数 ÷ 应录根数
 *   （非无导线器械至少 1 根——无导线（LEADLESS）不录电极为 0/0 不橙，口径见 OQ-151）；
 * - 📎 计数与基线分类同源（挂在该锚点的报告单数，AttachmentNav 清单）。
 */
import type { ImplantRecordView } from '@crdp/api';

import type { CategoryProgress } from '#/reference/types';

/** C_IMPLANT 表单分组键（V90 预置：器械信息 device / 电极 lead；与后端位置锚点一致）。 */
export const IMPLANT_ANCHOR_DEVICE = 'device';

export const IMPLANT_ANCHOR_LEAD = 'lead';

/** 器械信息展示字段（pt_implant 列的显示名槽位；已填 = 非空个数）。 */
const DEVICE_FIELDS: Array<keyof ImplantRecordView> = [
  'implantTypeLabel',
  'implantDate',
  'deviceTypeLabel',
  'brandLabel',
  'model',
  'serialNo',
  'pacingModeLabel',
  'hospital',
  'doctor',
  'indicationLabel',
  'note',
];

/** 整条植入记录锚点 key（整条与器械信息行本体共用一处 DOM，各自分键便于 hash 重放）。 */
export function implantRecordAnchorKey(implantId: string): string {
  return `implant-${implantId}`;
}

/** 器械信息分组锚点 key。 */
export function implantDeviceAnchorKey(implantId: string): string {
  return `implant-${implantId}-${IMPLANT_ANCHOR_DEVICE}`;
}

/** 电极分组锚点 key。 */
export function implantLeadAnchorKey(implantId: string): string {
  return `implant-${implantId}-${IMPLANT_ANCHOR_LEAD}`;
}

/** 位置锚点（LocationTag.linkRecordId + anchorKey）→ 锚点 key：整条 / device / lead。 */
export function implantAnchorKeyOf(
  implantId: string,
  anchorKey?: null | string,
): string {
  if (anchorKey === IMPLANT_ANCHOR_LEAD) return implantLeadAnchorKey(implantId);
  if (anchorKey === IMPLANT_ANCHOR_DEVICE)
    return implantDeviceAnchorKey(implantId);
  return implantRecordAnchorKey(implantId);
}

/** 器械信息进度：展示字段非空个数 ÷ 总数（C-PAT-018 已填 / 应填）。 */
export function deviceProgress(record: ImplantRecordView): {
  filled: number;
  total: number;
} {
  let filled = 0;
  for (const key of DEVICE_FIELDS) {
    const value = record[key];
    if (value !== null && value !== undefined && String(value).length > 0) {
      filled += 1;
    }
  }
  return { filled, total: DEVICE_FIELDS.length };
}

/**
 * 电极进度：已录根数 ÷ 应录根数。应录口径（OQ-151 暂行）：无导线起搏器
 * （LEADLESS）不录电极为 0/0（不橙）；其余器械至少 1 根（0 根 → 0/1 橙），
 * 已录 ≥1 即视为应填满足（每根电极的必填项由保存校验保证）。
 */
export function leadProgress(record: ImplantRecordView): {
  filled: number;
  total: number;
} {
  if (record.deviceType === 'LEADLESS') return { filled: 0, total: 0 };
  const count = record.leads.length;
  return { filled: Math.min(count, 1), total: 1 };
}

/**
 * 分组条条目（每条植入记录两枚 chip，顺序 = 区块展示序——当前器械置顶）：
 * label 前缀「第 N 次」与植入列表序号一致；📎 计数由调用方按（记录, 分组）并入
 * （与基线分类同源，挂在该锚点的报告单数）。
 */
export function implantBarItems(
  records: ImplantRecordView[],
  attachmentCountOf: (
    record: ImplantRecordView,
    anchorKey?: 'device' | 'lead',
  ) => number,
): CategoryProgress[] {
  const items: CategoryProgress[] = [];
  for (const record of records) {
    const device = deviceProgress(record);
    const lead = leadProgress(record);
    const prefix = `第${record.implantNo}次植入`;
    items.push(
      {
        attachmentCount: attachmentCountOf(record, IMPLANT_ANCHOR_DEVICE),
        filled: device.filled,
        key: implantDeviceAnchorKey(record.id),
        label: `${prefix}·器械信息`,
        total: device.total,
      },
      {
        attachmentCount: attachmentCountOf(record, IMPLANT_ANCHOR_LEAD),
        filled: lead.filled,
        key: implantLeadAnchorKey(record.id),
        label: `${prefix}·电极`,
        total: lead.total,
      },
    );
  }
  return items;
}
