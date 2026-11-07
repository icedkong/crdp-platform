import type { ReportRow } from '@crdp/api';

// E16-T09 / A-ATT-007、C-SCAN-016：报告单删除纯逻辑（弹窗标题标识与后果告知文案）
import { describe, expect, it } from 'vitest';

import { reportDeleteLabel, reportDeleteNote } from './report-delete-logic';

function row(overrides: Partial<ReportRow> = {}): ReportRow {
  return {
    conclusion: null,
    createdAt: '2026-10-28 10:00:00',
    creatorName: '王医生（示例）',
    digitizeStatus: null,
    fileCount: 2,
    findings: null,
    id: '901',
    locations: [],
    name: '经胸超声心动图（示例）',
    note: null,
    patientId: '301',
    reportDate: '2026-10-28',
    typeCode: 'ECHO',
    typeName: '心脏超声',
    version: 0,
    ...overrides,
  };
}

describe('reportDeleteLabel（E16-T09 弹窗标题标识）', () => {
  it('类型 · 名称', () => {
    expect(reportDeleteLabel(row())).toBe('心脏超声 · 经胸超声心动图（示例）');
  });

  it('无名称（选填类型）只显示类型，不出现「· null」', () => {
    expect(reportDeleteLabel(row({ name: null }))).toBe('心脏超声');
  });
});

describe('reportDeleteNote（E16-T09 后果告知）', () => {
  it('带附件数与软删除口径（数据保留可审计）', () => {
    expect(reportDeleteNote(row())).toBe(
      '删除后「心脏超声 · 经胸超声心动图（示例）」不再显示、附件 2 个一并移除；数据保留可审计，须填写原因。',
    );
  });

  it('无附件的指标型报告单注明（无附件）', () => {
    expect(reportDeleteNote(row({ fileCount: 0 }))).toContain('（无附件）');
  });
});

// ---------------------------------------------------------------- E16-T08 / A-ATT-017 通话记录

describe('reportDeleteNote（关联沟通记录的通话记录，A-ATT-017）', () => {
  it('带 contactLogId 的行追加「两处同时消失」提示（pages.md §4.8 确认弹窗要写明）', () => {
    const note = reportDeleteNote(
      row({
        contactLogId: '5001',
        fileCount: 1,
        name: '随访电话录音（示例）',
        typeCode: 'CALL',
        typeName: '通话记录',
      }),
    );
    expect(note).toContain('两处同时消失');
    expect(note).toContain('附件 1 个一并移除');
  });

  it('普通报告单文案不变（不提通话记录联动）', () => {
    expect(reportDeleteNote(row())).not.toContain('两处同时消失');
  });
});
