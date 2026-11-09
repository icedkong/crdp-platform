/**
 * E24-T06 / NFR-RESP-003：拍照多页上传的队列状态机（纯逻辑，无 DOM）。
 *
 * 每页（张）一张一条：pending → compressing（长边 ≤ 2560 / 转 JPEG / 去 EXIF，
 * image-compress）→ uploading（按字节百分比）→ done（fileId 进附件清单，页序即
 * 提交 sortNo 顺序）/ failed（弱网可重试，重试从压缩重新开始）。全部返回新数组
 * （组件侧 ref 替换），数量上限沿用 attachmentOverLimit（A-ATT-004 / C-SCAN-006，
 * 与服务端 40001 同口径：已上传附件 + 队列在途都计入）。
 */

export type PhotoUploadStatus =
  | 'compressing'
  | 'done'
  | 'failed'
  | 'pending'
  | 'uploading';

export interface PhotoUploadItem {
  /** failed 时的原因（提示由统一拦截器弹出，这里只留摘要）。 */
  error?: string;
  /** done 时的服务端 fileId（随提交进 fileIds）。 */
  fileId?: string;
  /** done 时的后端真实格式（预览方式判定用）。 */
  format?: string;
  /** 本地自增 id（非服务端标识）。 */
  id: string;
  /** 展示名（压缩后为 .jpg 名）。 */
  name: string;
  /** 上传进度百分比 0–100（其余阶段 0）。 */
  progress: number;
  status: PhotoUploadStatus;
}

/** 新建一页（等待处理）。 */
export function createPhotoItem(id: string, name: string): PhotoUploadItem {
  return { id, name, progress: 0, status: 'pending' };
}

/** 就地更新一页（返回新数组；找不到原样返回）。 */
export function updatePhotoItem(
  items: PhotoUploadItem[],
  id: string,
  patch: Partial<PhotoUploadItem>,
): PhotoUploadItem[] {
  return items.map((item) => (item.id === id ? { ...item, ...patch } : item));
}

export function markCompressing(
  items: PhotoUploadItem[],
  id: string,
): PhotoUploadItem[] {
  return updatePhotoItem(items, id, { status: 'compressing' });
}

export function markUploading(
  items: PhotoUploadItem[],
  id: string,
): PhotoUploadItem[] {
  return updatePhotoItem(items, id, { status: 'uploading' });
}

export function markDone(
  items: PhotoUploadItem[],
  id: string,
  fileId: string,
  format: string,
): PhotoUploadItem[] {
  return updatePhotoItem(items, id, {
    fileId,
    format,
    status: 'done',
  });
}

export function markFailed(
  items: PhotoUploadItem[],
  id: string,
  error?: string,
): PhotoUploadItem[] {
  return updatePhotoItem(items, id, { error, status: 'failed' });
}

/** 写入上传进度（百分比；只对 uploading 中的页生效）。 */
export function applyProgress(
  items: PhotoUploadItem[],
  id: string,
  percent: number,
): PhotoUploadItem[] {
  return items.map((item) =>
    item.id === id && item.status === 'uploading'
      ? { ...item, progress: percent }
      : item,
  );
}

/** 重试一页：回到 pending、进度清零、错误清除（重跑压缩 → 上传）。 */
export function retryItem(
  items: PhotoUploadItem[],
  id: string,
): PhotoUploadItem[] {
  return items.map((item) =>
    item.id === id && item.status === 'failed'
      ? { ...item, error: undefined, progress: 0, status: 'pending' }
      : item,
  );
}

/** 上传进度：字节 → 百分比（四舍五入、夹 0–100；total 未知按 0）。 */
export function progressPercent(loaded: number, total?: number): number {
  if (!total || total <= 0 || loaded <= 0) return 0;
  return Math.min(100, Math.max(0, Math.round((loaded / total) * 100)));
}

/** 队列行状态文案（design-system §12：显示进度、弱网失败可重试）。 */
export function photoStatusText(item: PhotoUploadItem): string {
  switch (item.status) {
    case 'compressing': {
      return '压缩中…';
    }
    case 'done': {
      return '已上传';
    }
    case 'failed': {
      return '上传失败，可重试';
    }
    case 'pending': {
      return '等待处理';
    }
    case 'uploading': {
      return `上传中 ${item.progress}%`;
    }
  }
}

/** HEIC / 图片解不开时的提示（按原文件上传，后端真实类型白名单兜底）。 */
export function undecodableNoteText(isHeic: boolean): string {
  return isHeic
    ? '当前浏览器无法转换 HEIC，已按原文件上传（如被拒请改用拍照或 JPG）'
    : '该图片无法在当前浏览器解析，已按原文件上传';
}
