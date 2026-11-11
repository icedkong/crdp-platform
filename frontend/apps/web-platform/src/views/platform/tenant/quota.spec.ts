import type { TenantQuotaView } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  EMPTY_QUOTA_FORM,
  gbToBytes,
  quotaAlerts,
  storageUsageText,
  toQuotaFormModel,
  toQuotaRequest,
  userUsageText,
  validateQuotaForm,
} from './quota';

/** E05-T09 / PLT-TEN-014：配额表单转换与校验、用量与预警文案（纯逻辑）。 */
describe('tenant quota form', () => {
  it('unlimited form submits nulls and passes validation', () => {
    expect(validateQuotaForm(EMPTY_QUOTA_FORM)).toEqual({});
    expect(toQuotaRequest(EMPTY_QUOTA_FORM)).toEqual({
      storageLimitBytes: null,
      userLimit: null,
    });
  });

  it('limited form converts storage GB to bytes', () => {
    const form = {
      ...EMPTY_QUOTA_FORM,
      storageLimitGb: 1.5,
      storageLimited: true,
      userLimit: 20,
      userLimited: true,
    };
    expect(validateQuotaForm(form)).toEqual({});
    expect(toQuotaRequest(form)).toEqual({
      storageLimitBytes: gbToBytes(1.5),
      userLimit: 20,
    });
  });

  it('rejects non-integer users and sub-0.1GB storage', () => {
    expect(
      validateQuotaForm({
        ...EMPTY_QUOTA_FORM,
        userLimit: 0,
        userLimited: true,
      }).userLimit,
    ).toContain('不小于 1 的整数');
    expect(
      validateQuotaForm({
        ...EMPTY_QUOTA_FORM,
        storageLimitGb: 0.01,
        storageLimited: true,
      }).storageLimitGb,
    ).toContain('0.1 GB');
    // 设置上限但未填值
    expect(
      validateQuotaForm({ ...EMPTY_QUOTA_FORM, userLimited: true }).userLimit,
    ).toContain('必须填写');
  });

  it('echoes view limits back into the form', () => {
    const form = toQuotaFormModel(
      quota({ storageLimitBytes: gbToBytes(5), userLimit: 10 }),
    );
    expect(form).toEqual({
      storageLimitGb: 5,
      storageLimited: true,
      userLimit: 10,
      userLimited: true,
    });
    expect(toQuotaFormModel(quota({}))).toEqual(EMPTY_QUOTA_FORM);
  });
});

describe('tenant quota display', () => {
  it('shows usage over limit and dashes when tenant db unreachable', () => {
    expect(userUsageText(quota({ enabledUserCount: 18, userLimit: 20 }))).toBe(
      '18 / 20',
    );
    expect(userUsageText(quota({ userLimit: 20 }))).toBe('— / 20');
    expect(userUsageText(quota({}))).toBe('— / 不限');
    expect(
      storageUsageText(
        quota({
          storageLimitBytes: gbToBytes(2),
          storageUsedBytes: gbToBytes(1.24),
        }),
      ),
    ).toBe('1.2 GB / 2.0 GB');
  });

  it('escalates alerts from warning at 80% to error at limit', () => {
    // 默认不限：无任何预警
    expect(quotaAlerts(quota({}))).toEqual([]);
    // 80% 预警（warning）
    const warning = quotaAlerts(
      quota({
        enabledUserCount: 8,
        storageLimitBytes: gbToBytes(1),
        storageUsedBytes: gbToBytes(0.9),
        storageWarning: true,
        userLimit: 10,
        userWarning: true,
      }),
    );
    expect(warning.map((item) => item.level)).toEqual(['warning', 'warning']);
    // 满额（error）：文案指明拦截行为
    const full = quotaAlerts(
      quota({
        enabledUserCount: 10,
        storageLimitBytes: gbToBytes(1),
        storageLimitReached: true,
        storageUsedBytes: gbToBytes(1),
        storageWarning: true,
        userLimit: 10,
        userLimitReached: true,
        userWarning: true,
      }),
    );
    expect(full.map((item) => item.level)).toEqual(['error', 'error']);
    const [userAlert, storageAlert] = full;
    expect(userAlert?.text).toContain('新建与启用用户将被拒绝');
    expect(storageAlert?.text).toContain('文件上传将被拒绝');
  });
});

function quota(overrides: Partial<TenantQuotaView>): TenantQuotaView {
  return {
    enabledUserCount: null,
    storageLimitBytes: null,
    storageLimitReached: false,
    storageUsedBytes: null,
    storageWarning: false,
    userLimit: null,
    userLimitReached: false,
    userWarning: false,
    ...overrides,
  };
}
