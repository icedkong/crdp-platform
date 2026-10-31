import { describe, expect, it } from 'vitest';

import { canRecycle, canRetry } from './provision';

describe('开通进度页逻辑（E02-T08）', () => {
  it('仅开通未完成的租户可放弃回收', () => {
    expect(canRecycle('PROVISIONING')).toBe(true);
    expect(canRecycle('TRIAL')).toBe(false);
    expect(canRecycle('ENABLED')).toBe(false);
  });

  it('开通中或有失败步骤时可重试', () => {
    expect(canRetry('PROVISIONING', 0)).toBe(true);
    expect(canRetry('TRIAL', 1)).toBe(true);
    expect(canRetry('TRIAL', 0)).toBe(false);
  });
});
