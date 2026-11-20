import type { TenantListItem } from '@crdp/api';

import type { LoadedConfigPreview } from './apply';

import { describe, expect, it } from 'vitest';

import {
  applyResultSummaryText,
  canSubmitConfigPreview,
  IMPORT_RULE_NOTES,
  targetTenantOptions,
  toApplyFormModel,
  validateApplyModel,
} from './apply';

function tenant(overrides: Partial<TenantListItem> = {}): TenantListItem {
  return {
    code: 'demob',
    createdAt: '2027-01-01T08:00:00+08:00',
    expireAt: null,
    expireType: 'LONG_TERM',
    id: 2,
    modules: [],
    name: '示例医院B',
    shortName: null,
    status: 'ENABLED',
    userCount: 5,
    viewEnterable: false,
    ...overrides,
  };
}

describe('e12-T04 目标租户选项与确认校验', () => {
  const tenants = [
    tenant({ id: 1, code: 'demoa', name: '示例医院A' }),
    tenant({ id: 2 }),
    tenant({ id: 3, status: 'CANCELLING' }),
    tenant({ id: 4, status: 'CANCELLED' }),
  ];

  it('目标选项排除源租户；注销中 / 已注销置灰并给原因', () => {
    const options = targetTenantOptions(tenants, 1);
    expect(options.map((option) => option.value)).toEqual([2, 3, 4]);
    expect(options[0]).toEqual({
      disabled: false,
      disabledReason: '',
      label: '示例医院B（demob）',
      value: 2,
    });
    expect(options[1]?.disabled).toBe(true);
    expect(options[1]?.disabledReason).toBe('该租户已注销或注销中');
    expect(options[2]?.disabled).toBe(true);
  });

  it('应用前校验：未选租户、选了源租户、未勾选确认都要拦下', () => {
    expect(validateApplyModel(toApplyFormModel(), 1)).toEqual([
      '请选择目标租户',
      '请先阅读导入预览并勾选确认',
    ]);
    expect(validateApplyModel({ confirmed: true, tenantId: 1 }, 1)).toEqual([
      '目标租户不能是模板的源租户',
    ]);
    expect(validateApplyModel({ confirmed: true, tenantId: 2 }, 1)).toEqual([]);
  });

  it('应用结果汇总文案：四类计数齐全', () => {
    expect(
      applyResultSummaryText({
        droppedRows: 3,
        insertedRows: 100,
        mergedRows: 20,
        skippedRows: 5,
      }),
    ).toBe('已应用：新增 100 行、覆盖 20 行、跳过 5 行、丢弃 3 行。');
  });
});

describe('oqr-02 预检绑定与重新确认', () => {
  const preview: LoadedConfigPreview = {
    applicable: true,
    changes: [],
    mode: 'UPDATE',
    planHash: 'example-hash',
    tables: [],
    templateId: 9,
    tenantId: 2,
  };
  it('未确认、预检失败或冲突时禁止提交', () => {
    expect(
      canSubmitConfigPreview({ confirmed: false, tenantId: 2 }, preview, 9),
    ).toBe(false);
    expect(
      canSubmitConfigPreview({ confirmed: true, tenantId: 2 }, null, 9),
    ).toBe(false);
    expect(
      canSubmitConfigPreview(
        { confirmed: true, tenantId: 2 },
        { ...preview, applicable: false },
        9,
      ),
    ).toBe(false);
    expect(
      canSubmitConfigPreview(
        { confirmed: true, tenantId: 2 },
        { ...preview, planHash: '' },
        9,
      ),
    ).toBe(false);
  });
  it('目标或模板切换后不能提交旧预检', () => {
    expect(
      canSubmitConfigPreview({ confirmed: true, tenantId: 3 }, preview, 9),
    ).toBe(false);
    expect(
      canSubmitConfigPreview({ confirmed: true, tenantId: 2 }, preview, 10),
    ).toBe(false);
    expect(
      canSubmitConfigPreview({ confirmed: true, tenantId: 2 }, preview, 9),
    ).toBe(true);
  });
  it('提示实际差异和旧版保护', () => {
    const text = IMPORT_RULE_NOTES.join('\n');
    expect(text).toContain('逐行显示');
    expect(text).toContain('未发布的新版本');
    expect(text).toContain('重新预览');
    expect(text).not.toContain('整组替换');
  });
});
