/**
 * E21-T32 / C-PAT-017、C-PAT-018 · 植入记录锚点与分组条纯逻辑：
 * 锚点 key（整条 / 器械信息 device / 电极 lead → #crdp-anchor-<key>，与基线分类、
 * 随访分组同一约定）、位置锚点解析（左栏标签 → 锚点）、分组条条目（每条植入记录
 * 器械信息与电极两枚 chip，已填 / 应填口径见 OQ-151）。
 */
import type { ImplantRecordView } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  deviceProgress,
  IMPLANT_ANCHOR_DEVICE,
  IMPLANT_ANCHOR_LEAD,
  implantAnchorKeyOf,
  implantBarItems,
  implantDeviceAnchorKey,
  implantLeadAnchorKey,
  implantRecordAnchorKey,
  leadProgress,
} from './implant-anchor-logic';

function implant(patch: Partial<ImplantRecordView> = {}): ImplantRecordView {
  return {
    brand: 'BRAND_X',
    brandLabel: '示例品牌X',
    current: true,
    deviceType: 'DDD',
    deviceTypeLabel: '双腔(DDD)',
    doctor: '王医生（示例）',
    endDate: null,
    endReason: null,
    hospital: '示例医院',
    id: '41',
    implantDate: '2025-01-10',
    implantNo: 2,
    implantType: 'FIRST',
    implantTypeLabel: '首次植入',
    indication: 'SA',
    indicationLabel: '病态窦房结综合征',
    indicationNote: null,
    leads: [
      {
        chamber: 'RA',
        chamberLabel: '右心房',
        id: '51',
        implantDate: '2025-01-10',
        model: 'MN-RA',
        position: 'ra_appendage',
        positionLabel: '右心耳',
        status: 'ACTIVE',
        statusLabel: '在用',
        version: 0,
      },
    ],
    model: 'MN-1',
    note: null,
    pacingMode: 'DDD',
    pacingModeLabel: 'DDD',
    patientId: '301',
    serialNo: 'DEMO-SN-0001',
    status: 'ACTIVE',
    statusLabel: '在用',
    version: 0,
    ...patch,
  };
}

describe('锚点 key（与 #crdp-anchor-<key> 约定一致）', () => {
  it('整条 / 器械信息 / 电极各有独立 key；位置标签（anchorKey）解析到对应锚点', () => {
    expect(implantRecordAnchorKey('41')).toBe('implant-41');
    expect(implantDeviceAnchorKey('41')).toBe('implant-41-device');
    expect(implantLeadAnchorKey('41')).toBe('implant-41-lead');
    expect(implantAnchorKeyOf('41')).toBe('implant-41');
    expect(implantAnchorKeyOf('41', IMPLANT_ANCHOR_DEVICE)).toBe(
      'implant-41-device',
    );
    expect(implantAnchorKeyOf('41', IMPLANT_ANCHOR_LEAD)).toBe(
      'implant-41-lead',
    );
    // 未知分组锚点回落整条（记录已变 / 模板调整退回后的兜底）
    expect(implantAnchorKeyOf('41', 'gone')).toBe('implant-41');
  });
});

describe('已填 / 应填（C-PAT-018，口径 OQ-151）', () => {
  it('器械信息：展示字段非空个数 ÷ 总数（空字段少填即未填完）', () => {
    const full = deviceProgress(implant());
    expect(full.total).toBe(11);
    expect(full.filled).toBe(10); // note 为空
    const sparse = deviceProgress(
      implant({ brandLabel: undefined, serialNo: null, model: '' }),
    );
    expect(sparse.filled).toBe(7);
  });

  it('电极：已录 ≥1 根即 1/1；0 根且非无导线 0/1（未填完）；无导线（LEADLESS）0/0 不橙', () => {
    expect(leadProgress(implant())).toEqual({ filled: 1, total: 1 });
    expect(leadProgress(implant({ leads: [] }))).toEqual({
      filled: 0,
      total: 1,
    });
    expect(
      leadProgress(implant({ deviceType: 'LEADLESS', leads: [] })),
    ).toEqual({ filled: 0, total: 0 });
  });
});

describe('implantBarItems（基线分组条的植入条目）', () => {
  it('每条植入记录两枚 chip（第 N 次·器械信息 / 电极），📎 计数由调用方按（记录, 分组）并入', () => {
    const items = implantBarItems(
      [implant(), implant({ id: '40', implantNo: 1, current: false })],
      (record, anchorKey) =>
        record.id === '41' && anchorKey === 'device' ? 2 : 0,
    );
    expect(items).toHaveLength(4);
    expect(items[0]).toMatchObject({
      attachmentCount: 2,
      key: 'implant-41-device',
      label: '第2次植入·器械信息',
    });
    expect(items[1]).toMatchObject({
      key: 'implant-41-lead',
      label: '第2次植入·电极',
    });
    expect(items[2]?.key).toBe('implant-40-device');
    expect(items[3]?.key).toBe('implant-40-lead');
  });
});
