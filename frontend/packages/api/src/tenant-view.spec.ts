import type { TenantViewProxyCapabilities } from './tenant-view';

/**
 * E20-T01 / PLT-VIEW-001、004 + E20-T04 / PLT-VIEW-002、003 租户视角前端纯逻辑测试：
 * 进入表单校验（目的 + 说明，与后端同口径）、目的 / 结束方式文案、剩余时长倒计时文案；
 * 进入凭据解析与入口地址、横幅标题、水印文案、退出原因文案。
 */
import { describe, expect, it } from 'vitest';

import {
  encodeTenantViewReason,
  parseTenantViewEntryTicket,
  TENANT_VIEW_PURPOSE_OPTIONS,
  tenantViewBannerTitle,
  tenantViewEndMethodText,
  tenantViewEntryUrl,
  tenantViewExitReasonText,
  tenantViewPurposeText,
  tenantViewRemainingText,
  tenantViewWatermarkText,
  tenantViewWriteClass,
  tenantViewWritePermission,
  validateTenantViewEnter,
  validateTenantViewProxyReason,
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

// ---------------------------------------------------------------- E20-T06 / PLT-VIEW-013、017、018：代为操作

describe('tenantViewWriteClass（写接口分类，与后端 TenantViewProxyService.classify 同口径）', () => {
  it('base 模块（tenant:*）全部配置类', () => {
    expect(tenantViewWriteClass('tenant:user:edit')).toBe('CONFIG');
    expect(tenantViewWriteClass('tenant:dict:edit')).toBe('CONFIG');
    expect(tenantViewWriteClass('tenant:session:kick')).toBe('CONFIG');
  });

  it('followup 内配置动作与字段字典为配置类，其余为业务数据', () => {
    expect(tenantViewWriteClass('followup:abnormal:config')).toBe('CONFIG');
    expect(tenantViewWriteClass('followup:scan:config')).toBe('CONFIG');
    expect(tenantViewWriteClass('followup:field:edit')).toBe('CONFIG');
    expect(tenantViewWriteClass('followup:patient:edit')).toBe('BUSINESS');
    expect(tenantViewWriteClass('followup:scan:upload')).toBe('BUSINESS');
    expect(tenantViewWriteClass('followup:task:assign')).toBe('BUSINESS');
  });

  it('明文 / 导出 / 医院侧重置 / 审批动作永不放行；后续模块按业务数据', () => {
    expect(tenantViewWriteClass('followup:patient:plain')).toBeNull();
    expect(tenantViewWriteClass('followup:patient:export')).toBeNull();
    expect(tenantViewWriteClass('followup:patient:export:approve')).toBeNull();
    expect(tenantViewWriteClass('tenant:user:reset')).toBeNull();
    expect(tenantViewWriteClass('followup:scan:download')).toBeNull();
    expect(tenantViewWriteClass('rct:crf:edit')).toBe('BUSINESS');
  });
});

describe('tenantViewWritePermission（视角模式写按钮显隐，PLT-VIEW-013、018）', () => {
  const admin: TenantViewProxyCapabilities = {
    businessEditable: true,
    canConfigure: true,
    canEditBusiness: true,
    canResetPassword: true,
    configResources: null,
  };

  it('默认关闭：任一能力缺失即不显示写按钮', () => {
    const none: TenantViewProxyCapabilities = {
      businessEditable: false,
      canConfigure: false,
      canEditBusiness: false,
      canResetPassword: false,
      configResources: null,
    };
    expect(tenantViewWritePermission(none, 'tenant:user:edit')).toBe(false);
    expect(tenantViewWritePermission(null, 'tenant:user:edit')).toBe(false);
  });

  it('可代为配置：范围未配置 = 全部配置类资源；范围外拒绝', () => {
    expect(tenantViewWritePermission(admin, 'tenant:user:edit')).toBe(true);
    const scoped: TenantViewProxyCapabilities = {
      ...admin,
      configResources: ['user', 'dict'],
    };
    expect(tenantViewWritePermission(scoped, 'tenant:user:edit')).toBe(true);
    expect(tenantViewWritePermission(scoped, 'tenant:role:edit')).toBe(false);
  });

  it('编辑业务数据：角色授权 ∧ 可编辑规则缺一即只读（PLT-VIEW-018）', () => {
    expect(tenantViewWritePermission(admin, 'followup:patient:edit')).toBe(
      true,
    );
    expect(
      tenantViewWritePermission(
        { ...admin, businessEditable: false },
        'followup:patient:edit',
      ),
    ).toBe(false);
    expect(
      tenantViewWritePermission(
        { ...admin, canEditBusiness: false },
        'followup:patient:edit',
      ),
    ).toBe(false);
  });

  it('读动作 / 明文 / 二次认证接口不合成（视角菜单驱动与 reauth 收口）', () => {
    expect(tenantViewWritePermission(admin, 'followup:patient:view')).toBe(
      false,
    );
    expect(tenantViewWritePermission(admin, 'followup:patient:plain')).toBe(
      false,
    );
    expect(tenantViewWritePermission(admin, 'followup:patient:delete')).toBe(
      false,
    );
  });
});

describe('代为操作原因（PLT-VIEW-013 二次确认并填写原因）', () => {
  it('校验：必填 2–500 字（与后端 requireReason 同口径）', () => {
    expect(validateTenantViewProxyReason('配置核对（示例）')).toEqual([]);
    expect(validateTenantViewProxyReason('')).toHaveLength(1);
    expect(validateTenantViewProxyReason('改')).toHaveLength(1);
    expect(validateTenantViewProxyReason('长'.repeat(501))).toHaveLength(1);
  });

  it('头值按 encodeURIComponent 百分号编码（HTTP 头不承载非 ASCII）', () => {
    expect(encodeTenantViewReason('配置核对（示例）')).toBe(
      encodeURIComponent('配置核对（示例）'),
    );
    expect(encodeTenantViewReason('fix config')).toBe('fix%20config');
  });
});
