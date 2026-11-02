import { describe, expect, it } from 'vitest';

import { downloadOnlyReason, previewModeOf } from './preview';

describe('e10-t04 预览方式判定（A-FU-025 / A-ATT-005）', () => {
  it('mP3 / M4A / WAV 用浏览器原生 audio 播放（不转码）', () => {
    expect(previewModeOf({ format: 'mp3' }).mode).toBe('audio');
    expect(previewModeOf({ format: 'm4a' }).mode).toBe('audio');
    expect(previewModeOf({ format: 'wav' }).mode).toBe('audio');
  });

  it('aMR 浏览器不能直接播放：只下载，文案说明原因', () => {
    const decision = previewModeOf({ format: 'amr' });
    expect(decision.mode).toBe('download-only');
    expect(decision.reason).toContain('AMR');
    expect(decision.reason).toContain('下载');
  });

  it('图片 / PDF / DOCX 页内预览', () => {
    expect(previewModeOf({ format: 'jpg' }).mode).toBe('image');
    expect(previewModeOf({ format: 'png' }).mode).toBe('image');
    expect(previewModeOf({ format: 'pdf' }).mode).toBe('pdf');
    expect(previewModeOf({ format: 'docx' }).mode).toBe('docx');
  });

  it('xLSX 只能下载查看（A-ATT-005）', () => {
    const decision = previewModeOf({ format: 'xlsx' });
    expect(decision.mode).toBe('download-only');
    expect(decision.reason).toContain('下载');
  });

  it('缺 format 时按 MIME 判定；再缺按文件扩展名兜底', () => {
    expect(previewModeOf({ mime: 'audio/mpeg' }).mode).toBe('audio');
    expect(previewModeOf({ mime: 'audio/amr' }).mode).toBe('download-only');
    expect(previewModeOf({ mime: 'application/pdf' }).mode).toBe('pdf');
    expect(
      previewModeOf({
        mime: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      }).mode,
    ).toBe('docx');
    expect(previewModeOf({ name: '通话录音（示例）.mp3' }).mode).toBe('audio');
    expect(previewModeOf({ name: '程控报告（示例）.amr' }).mode).toBe(
      'download-only',
    );
  });

  it('未知格式给出通用下载提示（不猜测可预览）', () => {
    const decision = previewModeOf({});
    expect(decision.mode).toBe('download-only');
    expect(decision.reason).toContain('下载');
  });

  it('downloadOnlyReason 与判定结果同口径（下载态文案单一来源）', () => {
    expect(downloadOnlyReason('amr')).toBe(
      previewModeOf({ format: 'amr' }).reason,
    );
    expect(downloadOnlyReason('xlsx')).toBe(
      previewModeOf({ format: 'xlsx' }).reason,
    );
  });
});
