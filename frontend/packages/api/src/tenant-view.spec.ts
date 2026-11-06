/**
 * E20-T01 / PLT-VIEW-001、004 + E20-T04 / PLT-VIEW-002、003 租户视角前端纯逻辑测试：
 * 进入表单校验（目的 + 说明，与后端同口径）、目的 / 结束方式文案、剩余时长倒计时文案；
 * 进入凭据解析与入口地址、横幅标题、水印文案、退出原因文案。
 */
import { describe, expect, it } from 'vitest';

import {
  parseTenantViewEntryTicket,
  TENANT_VIEW_PURPOSE_OPTIONS,
  tenantViewBannerTitle,
  tenantViewEndMethodText,
  tenantViewEntryUrl,
  tenantViewExitReasonText,
  tenantViewPurposeText,
  tenantViewRemainingText,
  tenantViewWatermarkText,
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

// ---------------------------------------------------------------- E20-T04 / PLT-VIEW-002、003：医院端视角外壳

const shellSession = {
  accountDisplayName: '平台管理员（示例）',
  accountName: 'platform.admin',
  expiresAt: '2026-11-06T12:00:00+08:00',
  purpose: 'TROUBLESHOOT',
  remainingSeconds: 4200,
  sessionId: 1,
  sessionNo: 'a1b2c3d4e5f60718',
  startedAt: '2026-11-06T10:00:00+08:00',
  tenantCode: 'demoa',
  tenantId: 1,
  tenantName: '虚构示例医院甲',
} as const;

describe('parseTenantViewEntryTicket（PLT-VIEW-002 进入凭据）', () => {
  it('只认 URL 片段里的 ticket（不进服务端日志）', () => {
    expect(parseTenantViewEntryTicket('#ticket=abc123_-X')).toBe('abc123_-X');
    expect(parseTenantViewEntryTicket('ticket=abc123')).toBe('abc123');
  });

  it('无凭据 / 杂质片段返回 null（不猜默认值）', () => {
    expect(parseTenantViewEntryTicket('#')).toBeNull();
    expect(parseTenantViewEntryTicket('#ticket=')).toBeNull();
    expect(parseTenantViewEntryTicket('#foo=1&ticket=abc')).toBeNull();
    expect(parseTenantViewEntryTicket('')).toBeNull();
  });
});

describe('tenantViewEntryUrl / tenantViewBannerTitle / tenantViewWatermarkText', () => {
  it('入口地址：片段携带一次性凭据，去掉医院端地址末尾斜杠', () => {
    expect(tenantViewEntryUrl('http://127.0.0.1:14173', 'tk1')).toBe(
      'http://127.0.0.1:14173/view/entry#ticket=tk1',
    );
    expect(tenantViewEntryUrl('http://127.0.0.1:14173//', 'tk 2')).toBe(
      'http://127.0.0.1:14173/view/entry#ticket=tk%202',
    );
  });

  it('横幅标题（PLT-VIEW-003）：「租户视角：租户名称」', () => {
    expect(tenantViewBannerTitle(shellSession)).toBe(
      '租户视角：虚构示例医院甲',
    );
  });

  it('全页水印：平台账号（显示名 + 账号）+ 会话号（PLT-VIEW-003）', () => {
    expect(tenantViewWatermarkText(shellSession)).toBe(
      '平台管理员（示例）（platform.admin）· 视角会话 a1b2c3d4e5f60718',
    );
    // 显示名缺失时回落账号，水印不出现「（）」
    expect(
      tenantViewWatermarkText({ ...shellSession, accountDisplayName: '' }),
    ).toBe('platform.admin（platform.admin）· 视角会话 a1b2c3d4e5f60718');
  });
});

describe('tenantViewExitReasonText', () => {
  it('退出原因文案与台账结束方式对应；令牌失效兜底', () => {
    expect(tenantViewExitReasonText('LOGOUT')).toBe('你已退出租户视角');
    expect(tenantViewExitReasonText('TIMEOUT')).toContain('单次最长 2 小时');
    expect(tenantViewExitReasonText('IDLE')).toContain('空闲超时');
    expect(tenantViewExitReasonText('PERM_CHANGE')).toContain('权限已变更');
    expect(tenantViewExitReasonText('EXPIRED')).toContain('重新进入');
    expect(tenantViewExitReasonText(null)).toContain('重新进入');
  });
});
