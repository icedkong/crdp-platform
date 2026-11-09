/**
 * E16-T10 / A-ATT-015、C-SCAN-008：电脑端「扫码用手机拍照上传」弹窗的纯逻辑。
 *
 * 二维码 15 分钟有效（timings.mobileUploadQrMinutes）：弹窗展示倒计时并每 3 秒轮询
 * 令牌状态——PENDING 继续等待、SUBMITTED 手机已生成报告单（带 reportId，随即刷新患者
 * 内清单）、EXPIRED 过期（提示重新生成，验收「二维码过期失效」）。
 */
import type { MobileUploadStatus } from '@crdp/api';

/** 弹窗状态机：签发中 → 等待扫码 / 提交（含倒计时）→ 已生成 / 已过期；失败可重签。 */
export type QrPhase = 'error' | 'expired' | 'issuing' | 'submitted' | 'waiting';

/** 按轮询结果推进状态（SUBMITTED / EXPIRED 为终态；PENDING 维持原状）。 */
export function nextQrPhase(
  current: QrPhase,
  status: MobileUploadStatus['status'],
): QrPhase {
  switch (status) {
    case 'EXPIRED': {
      return 'expired';
    }
    case 'SUBMITTED': {
      return 'submitted';
    }
    default: {
      return current === 'submitted' || current === 'expired'
        ? current
        : 'waiting';
    }
  }
}

/** 剩余时间展示（分:秒；过期 00:00）。与手机端 upload-flow 同口径。 */
export function qrCountdownText(
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

/** 倒计时归零即过期（后端令牌 TTL 同一时钟，前端先到点只是提前收口）。 */
export function countdownExpired(
  expiresAtEpochSecond: number,
  nowEpochSecond: number,
): boolean {
  return nowEpochSecond >= expiresAtEpochSecond;
}

/** 状态文案（design-system §15 简体中文口径）。 */
export function qrPhaseText(phase: QrPhase): string {
  switch (phase) {
    case 'expired': {
      return '二维码已过期，请在电脑上重新生成';
    }
    case 'issuing': {
      return '正在生成二维码…';
    }
    case 'submitted': {
      return '手机端已上传成功';
    }
    case 'waiting': {
      return '请用手机扫码拍照上传';
    }
    default: {
      return '二维码生成失败，请重试';
    }
  }
}
