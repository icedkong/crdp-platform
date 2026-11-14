// E24-T06 / NFR-RESP-003（design-system §12、pages.md §6.1）：拍照多页上传的
// 队列状态机（纯逻辑）——每页（张）压缩中 → 上传中（按字节百分比）→ 已上传 /
// 失败（可重试，重试从压缩重新开始）；顺序即提交 sortNo 顺序。数量上限复用
// attachmentOverLimit（A-ATT-004 / C-SCAN-006，40001 同口径）。
import { describe, expect, it } from 'vitest';

import {
  applyProgress,
  createPhotoItem,
  markCompressing,
  markDone,
  markFailed,
  markUploading,
  photoStatusText,
  progressPercent,
  retryItem,
  undecodableNoteText,
  updatePhotoItem,
} from './photo-upload-queue';
import { attachmentOverLimit } from './report-dialog-logic';

describe('e24-t06 photo-upload-queue（多页上传状态机）', () => {
  it('新建项：pending、进度 0、展示名带入', () => {
    const item = createPhotoItem('p1', 'IMG_0001.jpg');
    expect(item).toMatchObject({ id: 'p1', name: 'IMG_0001.jpg', progress: 0 });
    expect(item.status).toBe('pending');
  });

  it('状态流转：压缩中 → 上传中 → 已上传（带 fileId / format）', () => {
    let items = [createPhotoItem('p1', 'a.jpg')];
    items = markCompressing(items, 'p1');
    const compressing = items[0];
    expect(compressing?.status).toBe('compressing');
    expect(compressing ? photoStatusText(compressing) : '').toBe('压缩中…');

    items = markUploading(items, 'p1');
    expect(items[0]?.status).toBe('uploading');

    items = markDone(items, 'p1', 'f-9001', 'jpg');
    const done = items[0];
    expect(done).toMatchObject({ fileId: 'f-9001', format: 'jpg' });
    expect(done?.status).toBe('done');
    expect(done ? photoStatusText(done) : '').toBe('已上传');
  });

  it('失败与重试：failed 保留页序，重试回到 pending 且进度清零、错误清除', () => {
    let items = [
      createPhotoItem('p1', 'a.jpg'),
      createPhotoItem('p2', 'b.jpg'),
    ];
    items = markFailed(items, 'p1', '网络异常');
    const failed = items[0];
    expect(failed?.status).toBe('failed');
    expect(failed ? photoStatusText(failed) : '').toContain('上传失败');
    expect(failed ? photoStatusText(failed) : '').toContain('重试');

    const before = items;
    items = retryItem(items, 'p1');
    expect(items.map((item) => item.id)).toEqual(['p1', 'p2']);
    expect(items[0]).toMatchObject({ status: 'pending', progress: 0 });
    expect(items[0]?.error).toBeUndefined();
    // 纯函数不改原数组
    expect(before[0]?.status).toBe('failed');
    expect(before[0]?.progress).toBeGreaterThan(-1);
  });

  it('上传进度：字节换算百分比并夹在 0–100；total 未知按 0', () => {
    expect(progressPercent(50, 100)).toBe(50);
    expect(progressPercent(0, 100)).toBe(0);
    expect(progressPercent(120, 100)).toBe(100);
    expect(progressPercent(1, 0)).toBe(0);

    let items = [createPhotoItem('p1', 'a.jpg')];
    items = updatePhotoItem(items, 'p1', { status: 'uploading' });
    items = applyProgress(items, 'p1', progressPercent(456_789, 1_000_000));
    const uploading = items[0];
    expect(uploading?.progress).toBe(46);
    expect(uploading ? photoStatusText(uploading) : '').toBe('上传中 46%');
  });

  it('数量上限沿用 attachmentOverLimit 文案（已上传 + 队列在途都计入）', () => {
    // 已上传 9 个 + 队列在途 1 个 + 再拍 1 张 = 11 > 10 → 拦截
    expect(attachmentOverLimit(9 + 1, 1, 10)).toBe(
      '附件数量超过上限（每份报告单最多 10 个）',
    );
    // 已上传 9 + 再拍 1 = 10 → 放行
    expect(attachmentOverLimit(9, 1, 10)).toBeNull();
  });

  it('解不开的提示口径（HEIC 与普通图片都按原文件上传，后端白名单兜底）', () => {
    expect(undecodableNoteText(true)).toContain('HEIC');
    expect(undecodableNoteText(true)).toContain('按原文件上传');
    expect(undecodableNoteText(false)).not.toContain('HEIC');
    expect(undecodableNoteText(false)).toContain('按原文件上传');
  });
});

// E22-T16 / 等保 D-01 前端面：failed 页可携带病毒扫描类原因（病毒检出 / 扫描不可用），
// 行状态文案不变（design-system §12「弱网失败可重试」），原因由队列模板内联展示。
describe('e22-t16 失败原因摘要（病毒扫描类失败在队列内可见）', () => {
  it('failed 带 error：字段保留原文（模板内联展示），photoStatusText 口径不变', () => {
    const reason =
      '文件未通过病毒检测（检出 Win.Test.EICAR.HDB-1），已拒绝上传';
    let items = [createPhotoItem('p1', 'a.jpg')];
    items = markFailed(items, 'p1', reason);
    expect(items[0]?.error).toBe(reason);
    expect(items[0] ? photoStatusText(items[0]) : '').toBe('上传失败，可重试');
  });

  it('failed 不带 error（普通弱网）：行为与既有口径一致', () => {
    let items = [createPhotoItem('p1', 'a.jpg')];
    items = markFailed(items, 'p1');
    expect(items[0]?.error).toBeUndefined();
    expect(items[0] ? photoStatusText(items[0]) : '').toBe('上传失败，可重试');
  });
});
