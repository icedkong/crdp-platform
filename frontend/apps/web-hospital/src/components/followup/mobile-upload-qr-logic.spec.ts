/**
 * E16-T10 / A-ATT-015、C-SCAN-008 单测：扫码弹窗状态机与倒计时。
 */
import { describe, expect, it } from 'vitest';

import {
  countdownExpired,
  nextQrPhase,
  qrCountdownText,
  qrPhaseText,
} from './mobile-upload-qr-logic';

describe('e16-t10 nextQrPhase（轮询推进）', () => {
  it('submitted / expired 为终态', () => {
    expect(nextQrPhase('waiting', 'SUBMITTED')).toBe('submitted');
    expect(nextQrPhase('waiting', 'EXPIRED')).toBe('expired');
    // 终态不再回落（后端状态行保留剩余 TTL，重复轮询不翻转）
    expect(nextQrPhase('submitted', 'PENDING')).toBe('submitted');
    expect(nextQrPhase('expired', 'PENDING')).toBe('expired');
  });

  it('pending 维持等待（首次从 issuing 进入 waiting）', () => {
    expect(nextQrPhase('issuing', 'PENDING')).toBe('waiting');
    expect(nextQrPhase('waiting', 'PENDING')).toBe('waiting');
  });
});

describe('e16-t10 qrCountdownText / countdownExpired（15 分钟倒计时）', () => {
  it('分:秒展示与归零判定', () => {
    expect(qrCountdownText(1_000_900, 1_000_000)).toBe('15:00');
    expect(qrCountdownText(1_000_001, 1_000_000)).toBe('00:01');
    expect(qrCountdownText(1_000_000, 1_000_030)).toBe('00:00');
    expect(countdownExpired(1_000_000, 1_000_000)).toBe(true);
    expect(countdownExpired(1_000_001, 1_000_000)).toBe(false);
  });
});

describe('e16-t10 qrPhaseText（简体中文文案）', () => {
  it('过期文案与后端 40101 同句（OQ-25）', () => {
    expect(qrPhaseText('expired')).toBe('二维码已过期，请在电脑上重新生成');
    expect(qrPhaseText('waiting')).toBe('请用手机扫码拍照上传');
    expect(qrPhaseText('submitted')).toBe('手机端已上传成功');
  });
});
