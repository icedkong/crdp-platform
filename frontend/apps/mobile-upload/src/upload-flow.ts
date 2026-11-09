/**
 * E16-T10 / A-ATT-015、C-SCAN-008（pages.md §6.2）：手机上传页的纯逻辑。
 *
 * 流程：扫码进入（#/u/{令牌}）→ 引导（登录要求）→ 患者脱敏 + 选类型与位置
 * （下拉、至少 1 个、二维码预选）→ 拍照 / 选图（可连续多张、压缩后上传）→
 * 提交 → 「上传成功」；令牌过期 / 已用显示「二维码已过期，请在电脑上重新生成」。
 * 图片压缩在 @crdp/photo（长边 ≤ 2560、转 JPEG、去 EXIF，C-NFR-006 的 2400 为
 * 建议值，按 tokens 定稿 2560 执行，OQ-25）。
 */
import type { H5Info, H5LocationOption, H5TypeOption } from './api';

/** 从地址片段取令牌：#/u/{token}（后端 qrUrl 只把令牌放 URL 片段，不进服务端日志）。 */
export function tokenFromHash(hash: string): null | string {
  const match = /^#\/u\/([0-9a-f]{16,128})$/.exec(hash.trim());
  return match?.[1] ?? null;
}

/** 表单状态（类型 / 名称 / 报告时间 / 位置多选下拉）。 */
export interface UploadFormState {
  typeCode: null | string;
  nameId: null | string;
  reportDate: null | string;
  locationKeys: string[];
}

/** 初始表单：报告时间缺省今天、位置预选二维码带来的位置（A-ATT-015「自动预选该位置」）。 */
export function initialForm(info: H5Info): UploadFormState {
  return {
    typeCode: info.types.length === 1 ? (info.types[0]?.code ?? null) : null,
    nameId: null,
    reportDate: info.today,
    locationKeys: info.presetKey ? [info.presetKey] : [],
  };
}

/** 逐字段校验（与电脑端同口径：类型必选、名称必填类型须选名称、位置至少 1 个、报告时间必填）。 */
export function formErrors(
  form: UploadFormState,
  types: H5TypeOption[],
): Partial<Record<'locations' | 'nameId' | 'reportDate' | 'typeCode', string>> {
  const errors: Partial<
    Record<'locations' | 'nameId' | 'reportDate' | 'typeCode', string>
  > = {};
  if (!form.typeCode) {
    errors.typeCode = '请选择资料类型';
  }
  const type = types.find((item) => item.code === form.typeCode);
  if (type?.nameRequired && !form.nameId) {
    errors.nameId = `该类型必须选择名称（${type.name}）`;
  }
  if (!form.reportDate) {
    errors.reportDate = '请选择报告时间';
  }
  if (form.locationKeys.length === 0) {
    errors.locations = '请至少选择 1 个位置';
  }
  return errors;
}

/** 提交载荷：位置取所选下拉项的 value（五元组原样回传，锚点模板版本随选项）。 */
export function submitLocations(
  form: UploadFormState,
  options: H5LocationOption[],
): H5LocationOption['value'][] {
  return form.locationKeys
    .map((key) => options.find((option) => option.key === key))
    .filter(Boolean)
    .map((option) => option.value);
}

// ---------------------------------------------------------------- 拍照队列

/** 队列项状态：待压缩 → 压缩中 → 上传中（进度）→ 完成 / 失败（可重试，从压缩重新开始）。 */
export type PhotoStatus =
  | 'compressing'
  | 'done'
  | 'failed'
  | 'pending'
  | 'uploading';

export interface PhotoItem {
  id: number;
  name: string;
  status: PhotoStatus;
  /** 上传进度 0–100（压缩阶段为 0）。 */
  progress: number;
  fileId: null | string;
  error: null | string;
}

let photoSeq = 0;

export function createPhotoItem(name: string): PhotoItem {
  photoSeq += 1;
  return {
    id: photoSeq,
    name,
    status: 'pending',
    progress: 0,
    fileId: null,
    error: null,
  };
}

export function queueSummary(items: PhotoItem[]): {
  done: number;
  failed: number;
  total: number;
} {
  return {
    total: items.length,
    done: items.filter((item) => item.status === 'done').length,
    failed: items.filter((item) => item.status === 'failed').length,
  };
}

/** 可提交：至少 1 张、全部成功（失败项须移除或重试成功）。 */
export function canSubmit(items: PhotoItem[]): boolean {
  return items.length > 0 && items.every((item) => item.status === 'done');
}

export function photoStatusText(status: PhotoStatus): string {
  switch (status) {
    case 'compressing': {
      return '压缩中…';
    }
    case 'done': {
      return '已上传';
    }
    case 'failed': {
      return '上传失败';
    }
    case 'pending': {
      return '待压缩';
    }
    case 'uploading': {
      return '上传中…';
    }
    default: {
      return '';
    }
  }
}

/** 二维码剩余时间展示（分:秒；过期显示 00:00）。 */
export function countdownText(
  expiresAtEpochSecond: number,
  nowEpochSecond: number,
): string {
  const seconds = Math.max(
    0,
    Math.floor(expiresAtEpochSecond - nowEpochSecond),
  );
  const minutes = Math.floor(seconds / 60);
  const rest = seconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(rest).padStart(2, '0')}`;
}

/** 40101 的统一文案（OQ-25：二维码过期 / 已用同一句）。 */
export const QR_EXPIRED_MESSAGE = '二维码已过期，请在电脑上重新生成';
