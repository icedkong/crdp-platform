/**
 * E20-T01 / PLT-VIEW-001、004 租户视角前端纯逻辑测试：
 * 进入表单校验（目的 + 说明，与后端同口径）、目的 / 结束方式文案、剩余时长倒计时文案。
 */
import { describe, expect, it } from 'vitest';

import {
  TENANT_VIEW_PURPOSE_OPTIONS,
  tenantViewEndMethodText,
  tenantViewPurposeText,
  tenantViewRemainingText,
  validateTenantViewEnter,
} from './tenant-view';

describe('validateTenantViewEnter', () => {
  it('目的必选、说明必填', () => {
    expect(validateTenantViewEnter({ note: '', purpose: null })).toEqual([
      '请选择查看目的',
      '请填写查看说明（必填）',
    ]);
    expect(
      validateTenantViewEnter({ note: '  ', purpose: 'TROUBLESHOOT' }),
    ).toEqual(['请填写查看说明（必填）']);
    expect(
      validateTenantViewEnter({ note: '排查（示例）', purpose: null }),
    ).toEqual(['请选择查看目的']);
  });

  it('目的只允许四个枚举值；说明 ≤500', () => {
    expect(
      validateTenantViewEnter({ note: 'x', purpose: 'AUDIT' as never }),
    ).toContain('查看目的只允许 排障 / 配置核对 / 培训支持 / 其他');
    expect(
      validateTenantViewEnter({ note: '长'.repeat(501), purpose: 'OTHER' }),
    ).toContain('查看说明过长（最多 500 个字符）');
    expect(
      validateTenantViewEnter({
        note: ' 排查登录失败（示例） ',
        purpose: 'OTHER',
      }),
    ).toEqual([]);
  });
});

describe('文案', () => {
  it('目的选项与文案同口径', () => {
    expect(TENANT_VIEW_PURPOSE_OPTIONS.map((option) => option.value)).toEqual([
      'TROUBLESHOOT',
      'CONFIG_CHECK',
      'TRAINING',
      'OTHER',
    ]);
    expect(tenantViewPurposeText('TROUBLESHOOT')).toBe('排障');
    expect(tenantViewPurposeText('CONFIG_CHECK')).toBe('配置核对');
    expect(tenantViewPurposeText('TRAINING')).toBe('培训支持');
    expect(tenantViewPurposeText('OTHER')).toBe('其他');
    expect(tenantViewPurposeText('UNKNOWN')).toBe('UNKNOWN');
  });

  it('结束方式文案（platform-spec §8）', () => {
    expect(tenantViewEndMethodText('LOGOUT')).toBe('主动退出');
    expect(tenantViewEndMethodText('TIMEOUT')).toBe('到时');
    expect(tenantViewEndMethodText('IDLE')).toBe('空闲超时');
    expect(tenantViewEndMethodText('PERM_CHANGE')).toBe('权限变更');
  });
});

describe('tenantViewRemainingText', () => {
  it('按分钟 + 秒展示；不足一分钟按秒；到时提示可重新进入', () => {
    expect(tenantViewRemainingText(7200)).toBe('120 分 0 秒');
    expect(tenantViewRemainingText(7199)).toBe('119 分 59 秒');
    expect(tenantViewRemainingText(60)).toBe('1 分 0 秒');
    expect(tenantViewRemainingText(42)).toBe('42 秒');
    expect(tenantViewRemainingText(0)).toBe('已到时');
  });
});
