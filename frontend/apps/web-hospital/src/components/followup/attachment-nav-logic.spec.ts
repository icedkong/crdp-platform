/**
 * E16-T14 / A-PAT-020、C-PAT-017（components.md §5 AttachmentNav、§4.2 / §4.5 内联缩略图）：
 * 左栏附件导航与各位置内联缩略图的纯逻辑——患者内清单行 → 附件导航条目（home 语义：
 * 无位置的报告单在基线 / 随访两个页签都进「未指定位置」组）、按位置取条目（基线分类 /
 * 随访记录整条 / 分组），同一报告单在每个所在位置各出现一次；首附件只有图片格式才值得
 * 换预览短时链接出缩略图（其他格式显示文件图标，不请求）。
 */
import type { ReportLocationTag, ReportRow } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  itemsAtBaselineAnchor,
  itemsAtImplant,
  itemsAtRecord,
  thumbnailFileOf,
  toNavItems,
} from './attachment-nav-logic';

function tag(patch: Partial<ReportLocationTag> = {}): ReportLocationTag {
  return {
    anchorKey: undefined,
    anchorLabel: undefined,
    anchorType: undefined,
    linkRecordId: undefined,
    linkType: 'baseline',
    recordDate: undefined,
    recordLabel: undefined,
    templateVersion: 3,
    ...patch,
  };
}

function row(patch: Partial<ReportRow> = {}): ReportRow {
  return {
    conclusion: null,
    createdAt: '2026-10-28 10:00:00',
    creatorName: '张医生（示例）',
    digitizeStatus: null,
    fileCount: 1,
    findings: null,
    firstFile: { fileId: '501', format: 'png' },
    id: '900',
    locations: [],
    name: '经胸超声心动图（示例）',
    note: null,
    patientId: '301',
    reportDate: '2026-10-28',
    typeCode: 'ECHO',
    typeName: '心脏超声',
    version: 0,
    ...patch,
  };
}

describe('toNavItems（患者内清单行 → 左栏附件导航条目）', () => {
  it('映射基础字段；无位置报告单 home=both（两个页签都进「未指定位置」组）', () => {
    const [item] = toNavItems([row()]);
    expect(item).toMatchObject({
      fileCount: 1,
      home: 'both',
      name: '经胸超声心动图（示例）',
      reportId: '900',
      typeCode: 'ECHO',
      typeName: '心脏超声',
    });
    expect(item?.locations).toEqual([]);
    expect(item?.thumbnailUrl).toBeUndefined();
  });

  it('位置标签按页签侧带出（templateVersion 不进前端导航结构，adjusted 保留）', () => {
    const [item] = toNavItems([
      row({
        locations: [
          tag({
            anchorKey: 'case',
            anchorLabel: '病例基本信息',
            anchorType: 'category',
          }),
          tag({
            anchorKey: 'echo',
            anchorLabel: '超声复查',
            anchorType: 'section',
            linkRecordId: '701',
            linkType: 'followup_record',
            recordLabel: '第2年随访',
          }),
        ],
      }),
    ]);
    expect(item?.locations).toEqual([
      {
        anchorKey: 'case',
        anchorLabel: '病例基本信息',
        anchorType: 'category',
        linkRecordId: undefined,
        linkType: 'baseline',
        recordDate: undefined,
        recordLabel: undefined,
      },
      {
        adjusted: undefined,
        anchorKey: 'echo',
        anchorLabel: '超声复查',
        anchorType: 'section',
        linkRecordId: '701',
        linkType: 'followup_record',
        recordDate: undefined,
        recordLabel: '第2年随访',
      },
    ]);
  });

  it('home 按所在侧推导（仅基线 → baseline；仅随访 → followup）；名称空回退类型名', () => {
    const [baselineOnly, followupOnly, unnamed] = toNavItems([
      row({
        id: '1',
        locations: [tag({ anchorType: 'category', anchorKey: 'case' })],
      }),
      row({
        id: '2',
        locations: [tag({ linkRecordId: '701', linkType: 'followup_record' })],
      }),
      row({ id: '3', name: null }),
    ]);
    expect(baselineOnly?.home).toBe('baseline');
    expect(followupOnly?.home).toBe('followup');
    expect(unnamed?.name).toBe('心脏超声');
  });

  it('电子化状态映射显示文案（C-SCAN-011；A 租户 null 不带）', () => {
    const [pending, none] = toNavItems([
      row({ digitizeStatus: 'PENDING' }),
      row({ digitizeStatus: null }),
    ]);
    expect(pending?.digitizeStatus).toBe('待录入');
    expect(none?.digitizeStatus).toBeUndefined();
  });
});

describe('itemsAtBaselineAnchor（基线分类末尾的内联缩略图与 📎 计数）', () => {
  const items = toNavItems([
    row({
      id: '900',
      locations: [
        tag({ anchorKey: 'case', anchorType: 'category' }),
        tag({ anchorKey: 'echo', anchorType: 'category' }),
      ],
    }),
    row({
      id: '901',
      locations: [tag({ anchorKey: 'case', anchorType: 'category' })],
    }),
    row({
      id: '902',
      locations: [tag({ linkRecordId: '701', linkType: 'followup_record' })],
    }),
  ]);

  it('只取挂在该分类下的报告单；同一报告单在每个所在位置各出现一次', () => {
    expect(itemsAtBaselineAnchor(items, 'case').map((i) => i.reportId)).toEqual(
      ['900', '901'],
    );
    expect(itemsAtBaselineAnchor(items, 'echo').map((i) => i.reportId)).toEqual(
      ['900'],
    );
    expect(itemsAtBaselineAnchor(items, 'surgery')).toEqual([]);
  });

  it('条目只带该侧基线位置（左栏口径一致，供条目内位置标签复用）', () => {
    const [atEcho] = itemsAtBaselineAnchor(items, 'echo');
    expect(atEcho?.locations).toHaveLength(1);
    expect(atEcho?.locations[0]?.anchorKey).toBe('echo');
  });
});

describe('itemsAtRecord（随访记录整条 / 分组末尾的内联缩略图）', () => {
  const items = toNavItems([
    // 整条记录
    row({
      id: '900',
      locations: [tag({ linkRecordId: '701', linkType: 'followup_record' })],
    }),
    // 该记录某分组
    row({
      id: '901',
      locations: [
        tag({
          anchorKey: 'echo',
          anchorType: 'section',
          linkRecordId: '701',
          linkType: 'followup_record',
        }),
      ],
    }),
    // 另一条记录
    row({
      id: '902',
      locations: [tag({ linkRecordId: '702', linkType: 'followup_record' })],
    }),
  ]);

  it('anchorKey 缺省 = 整条记录：只取无分组锚点的位置（分组附件显示在各自分组末尾）', () => {
    expect(itemsAtRecord(items, '701').map((i) => i.reportId)).toEqual(['900']);
    expect(itemsAtRecord(items, '702').map((i) => i.reportId)).toEqual(['902']);
    expect(itemsAtRecord(items, '703')).toEqual([]);
  });

  it('anchorKey 给定 = 该分组末尾（同一报告单可同时挂整条与分组，两处都显示）', () => {
    const both = toNavItems([
      row({
        id: '903',
        locations: [
          tag({ linkRecordId: '701', linkType: 'followup_record' }),
          tag({
            anchorKey: 'lab',
            anchorType: 'section',
            linkRecordId: '701',
            linkType: 'followup_record',
          }),
        ],
      }),
    ]);
    const all = [...items, ...both];
    expect(itemsAtRecord(all, '701', 'echo').map((i) => i.reportId)).toEqual([
      '901',
    ]);
    expect(itemsAtRecord(all, '701', 'lab').map((i) => i.reportId)).toEqual([
      '903',
    ]);
    expect(itemsAtRecord(all, '701').map((i) => i.reportId)).toEqual([
      '900',
      '903',
    ]);
  });
});

describe('thumbnailFileOf（首附件缩略图候选：只有图片格式换预览链接）', () => {
  it('jpg / png 出缩略图候选；pdf / docx 等非图片与无附件返回 null', () => {
    expect(thumbnailFileOf(row())).toEqual({ fileId: '501' });
    expect(
      thumbnailFileOf(row({ firstFile: { fileId: '502', format: 'jpg' } })),
    ).toEqual({ fileId: '502' });
    expect(
      thumbnailFileOf(row({ firstFile: { fileId: '503', format: 'pdf' } })),
    ).toBeNull();
    expect(thumbnailFileOf(row({ firstFile: null }))).toBeNull();
  });
});

describe('e21-T32 / C-PAT-017：植入记录位置（基线资料页签侧）', () => {
  const implantRow = row({
    id: '901',
    locations: [
      tag({
        anchorType: 'section',
        anchorKey: 'device',
        anchorLabel: '器械信息',
        linkRecordId: '41',
        linkType: 'implant_record',
        recordLabel: '植入记录#2 2025-01-10',
      }),
      tag({
        linkRecordId: '41',
        linkType: 'implant_record',
        recordLabel: '植入记录#2 2025-01-10',
      }),
    ],
  });

  it('home 推导：仅植入位置 → baseline（植入记录在基线资料页签）', () => {
    const [item] = toNavItems([implantRow]);
    expect(item?.home).toBe('baseline');
  });

  it('itemsAtImplant：整条（anchorKey 缺省）只取无分组锚点的位置，device / lead 各取各自锚点', () => {
    const items = toNavItems([implantRow]);
    expect(itemsAtImplant(items, '41')).toHaveLength(1);
    expect(itemsAtImplant(items, '41', 'device')).toHaveLength(1);
    expect(itemsAtImplant(items, '41', 'lead')).toHaveLength(0);
    // 同一报告单可同时挂整条与分组，两处各显示一次（与随访记录同口径）
    expect(itemsAtImplant(items, '42')).toHaveLength(0);
  });

  it('条目只带该位置的植入位置标签（左栏口径一致）', () => {
    const [item] = itemsAtImplant(toNavItems([implantRow]), '41', 'device');
    expect(item?.locations).toHaveLength(1);
    expect(item?.locations[0]?.anchorKey).toBe('device');
  });
});
