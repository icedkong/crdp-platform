import type { InboxUploadItem } from './inbox-logic';

// E21-T16 / C-SCAN-009 · 待归档区列表页纯逻辑（检索装配、缩略图形态、进区就绪判定）
import { describe, expect, it } from 'vitest';

import {
  buildInboxSearchPayload,
  EMPTY_FILTERS,
  formatUploadedAt,
  inboxThumbOf,
  intakeReady,
  MAX_INTAKE_FILES,
} from './inbox-logic';

describe('e21-t16 buildInboxSearchPayload（检索装配）', () => {
  it('空筛选项不提交（交给后端缺省口径）', () => {
    expect(buildInboxSearchPayload({ ...EMPTY_FILTERS })).toEqual({});
  });

  it('上传日期区间与上传人提交；未选全的一端不提交', () => {
    expect(
      buildInboxSearchPayload({
        uploadedRange: ['2026-11-01', '2026-11-30'],
        uploaderId: '7',
      }),
    ).toEqual({
      creatorId: '7',
      uploadedFrom: '2026-11-01',
      uploadedTo: '2026-11-30',
    });
    expect(
      buildInboxSearchPayload({ uploadedRange: null, uploaderId: null }),
    ).toEqual({});
  });
});

describe('e21-t16 inboxThumbOf（缩略图形态）', () => {
  const row = (format: string): Parameters<typeof inboxThumbOf>[0] => ({
    createdAt: '2026-11-14 09:30:00',
    fileId: '501',
    fileName: '扫描件（示例）',
    format,
    id: '61',
    pages: 1,
    sizeBytes: 1024,
    uploaderName: '张医生（示例）',
  });

  it('图片走预览链接，其他显示文件类型', () => {
    expect(inboxThumbOf(row('png'))).toEqual({ fileId: '501', kind: 'image' });
    expect(inboxThumbOf(row('jpg'))).toEqual({ fileId: '501', kind: 'image' });
    expect(inboxThumbOf(row('JPG'))).toEqual({ fileId: '501', kind: 'image' });
    expect(inboxThumbOf(row('pdf'))).toEqual({ kind: 'file', label: 'PDF' });
  });
});

describe('e21-t16 formatUploadedAt（时间显示）', () => {
  it('dATETIME 串与 ISO 统一为 YYYY-MM-DD HH:mm', () => {
    expect(formatUploadedAt('2026-11-14 09:30:00')).toBe('2026-11-14 09:30');
    expect(formatUploadedAt('2026-11-14T09:30:00.123')).toBe(
      '2026-11-14 09:30',
    );
  });
});

describe('e21-t16 intakeReady（放入待归档区就绪判定）', () => {
  const item = (
    fileId: string,
    status: InboxUploadItem['status'],
  ): InboxUploadItem => ({ fileId, name: `${fileId}.png`, status });

  it('全部上传完成且 ≥ 1 个 → 给出 fileIds', () => {
    expect(intakeReady([item('11', 'done'), item('12', 'done')])).toEqual({
      fileIds: ['11', '12'],
      problem: null,
    });
    expect(intakeReady([])).toEqual({
      fileIds: [],
      problem: '请先选择并上传扫描件',
    });
  });

  it('仍有在途或全部失败 → 阻止提交', () => {
    expect(intakeReady([item('11', 'uploading')]).problem).toBe(
      '仍有文件在上传，请稍候',
    );
    expect(intakeReady([item('11', 'failed')]).problem).toBe(
      '请先选择并上传扫描件',
    );
  });

  it('超过一次上限（50）→ 阻止提交（与后端 40001 同口径）', () => {
    const tooMany = Array.from({ length: MAX_INTAKE_FILES + 1 }, (_, index) =>
      item(String(20 + index), 'done'),
    );
    expect(intakeReady(tooMany).problem).toBe('一次最多放入 50 个文件');
  });
});
