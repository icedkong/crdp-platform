import type {
  ModuleOpenLog,
  TenantModuleCloseImpact,
  TenantModuleConfig,
  TenantModuleItem,
} from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  canClose,
  canConfigure,
  canOpen,
  dependsOnText,
  impactLines,
  logActionTone,
  logLines,
  moduleStatusText,
  timeText,
  toConfigFormModel,
  toConfigUpdateRequest,
  validateCloseReason,
  validateConfigForm,
  validateOpenReason,
  versionText,
} from './tenant-modules';

function moduleItem(
  overrides: Partial<TenantModuleItem> = {},
): TenantModuleItem {
  return {
    baseModule: false,
    closedAt: null,
    code: 'followup',
    currentMigrationVersion: '2',
    dependsOn: ['base'],
    description: '随访（示例）',
    featureVersion: '0.1.0',
    migrationVersion: '2',
    name: '随访',
    openedAt: '2026-11-01T09:00:00+08:00',
    reason: null,
    shelfStatus: 'ON',
    status: 'OPEN',
    ...overrides,
  };
}

describe('e05-T05 开通 / 关闭操作可用性', () => {
  it('开通：基础模块与下架模块不可开通；未开通 / 已关闭 / 开通失败可（重）开通', () => {
    expect(canOpen(moduleItem({ baseModule: true }))).toBe(false);
    expect(canOpen(moduleItem({ shelfStatus: 'OFF' }))).toBe(false);
    expect(canOpen(moduleItem({ status: 'OPEN' }))).toBe(false);
    expect(canOpen(moduleItem({ status: 'OPENING' }))).toBe(false);
    expect(canOpen(moduleItem({ status: null }))).toBe(true);
    expect(canOpen(moduleItem({ status: 'CLOSED' }))).toBe(true);
    expect(canOpen(moduleItem({ status: 'OPEN_FAILED' }))).toBe(true);
  });

  it('关闭与配置：只有已开能关、能配；基础模块必选', () => {
    expect(canClose(moduleItem({ baseModule: true }))).toBe(false);
    expect(canClose(moduleItem({ status: 'OPEN' }))).toBe(true);
    expect(canClose(moduleItem({ status: 'CLOSED' }))).toBe(false);
    expect(canClose(moduleItem({ status: null }))).toBe(false);
    expect(canConfigure(moduleItem({ status: 'OPEN' }))).toBe(true);
    expect(canConfigure(moduleItem({ status: 'CLOSED' }))).toBe(false);
  });

  it('状态、依赖与版本列文案', () => {
    expect(moduleStatusText(moduleItem({ status: 'OPEN' }))).toBe('已开通');
    expect(moduleStatusText(moduleItem({ status: null }))).toBe('未开通');
    expect(moduleStatusText(moduleItem({ status: 'OPEN_FAILED' }))).toBe(
      '开通失败',
    );
    expect(dependsOnText(moduleItem())).toBe('base');
    expect(dependsOnText(moduleItem({ dependsOn: [] }))).toBe('—');
    // 已开通显示租户当前迁移版本，未开通回落目录版本
    expect(versionText(moduleItem())).toBe('0.1.0 / 2');
    expect(
      versionText(moduleItem({ status: null, currentMigrationVersion: '' })),
    ).toBe('0.1.0 / 2');
    expect(timeText(null)).toBe('—');
    expect(timeText('2026-11-01T09:05:07+08:00')).toBe('2026-11-01 09:05');
  });
});

describe('e05-T05 原因与影响面', () => {
  it('关闭原因必填 ≤200；开通原因可空 ≤200', () => {
    expect(validateCloseReason(' ')).toBe('必须填写关闭原因');
    expect(validateCloseReason('长'.repeat(201))).toContain('200');
    expect(validateCloseReason('医院要求暂停（示例）')).toBeUndefined();
    expect(validateOpenReason('')).toBeUndefined();
    expect(validateOpenReason('长'.repeat(201))).toContain('200');
  });

  it('影响面文案：无依赖说明收回语义，有依赖列出依赖方', () => {
    const impact: TenantModuleCloseImpact = {
      dependentModules: [],
      moduleCode: 'followup',
      moduleName: '随访',
      onlineUserCount: 3,
      pendingTaskCount: null,
      tenantCode: 'demoa',
      tenantId: 1,
    };
    expect(impactLines(impact)).toEqual([
      '在线用户：3 个',
      '进行中随访任务：暂无数据（任务表未建）',
      '关闭后菜单与接口立即收回（模块接口返回 40301），数据与角色授权保留',
    ]);
    expect(
      impactLines({
        ...impact,
        dependentModules: ['rct'],
        pendingTaskCount: 12,
      }),
    ).toEqual([
      '在线用户：3 个',
      '进行中随访任务：12 个',
      '仍被已开通模块依赖：rct（须先关闭它们）',
    ]);
  });
});

describe('e05-T05 模块级配置表单（PLT-MOD-008）', () => {
  const config: TenantModuleConfig = {
    items: [
      {
        code: 'defaultNotifyChannel',
        defaultValue: '站内信',
        description: '任务通知的默认渠道',
        effectiveValue: '站内信',
        name: '随访默认渠道',
        value: null,
      },
    ],
    moduleCode: 'followup',
    tenantCode: 'demoa',
    tenantId: 1,
  };

  it('未覆盖项预填默认值；提交只送覆盖值', () => {
    const model = toConfigFormModel(config);
    expect(model.values.defaultNotifyChannel).toBe('站内信');
    expect(toConfigUpdateRequest(model)).toEqual({
      values: { defaultNotifyChannel: '站内信' },
    });
  });

  it('校验：必填与长度', () => {
    const model = toConfigFormModel(config);
    model.values.defaultNotifyChannel = ' ';
    expect(validateConfigForm(model, config)).toEqual({
      defaultNotifyChannel: '随访默认渠道必填',
    });
    model.values.defaultNotifyChannel = '长'.repeat(256);
    expect(validateConfigForm(model, config)).toEqual({
      defaultNotifyChannel: '随访默认渠道过长（最多 255 个字符）',
    });
    model.values.defaultNotifyChannel = ' 短信（示例） ';
    expect(validateConfigForm(model, config)).toEqual({});
    expect(toConfigUpdateRequest(model)).toEqual({
      values: { defaultNotifyChannel: '短信（示例）' },
    });
  });
});

describe('e05-T05 开通记录（PLT-MOD-009）', () => {
  it('记录行文案与动作颜色', () => {
    const log: ModuleOpenLog = {
      action: 'CLOSE',
      createdAt: '2026-11-01T09:30:00+08:00',
      detail: '收回菜单与接口（40301）；数据保留',
      id: 3,
      moduleCode: 'followup',
      operatorId: 1,
      reason: '合同暂停（示例）',
      tenantCode: 'demoa',
      tenantId: 1,
      tenantName: '虚构示例医院甲',
    };
    expect(logLines(log)).toEqual([
      '关闭 · 虚构示例医院甲（demoa）',
      '原因：合同暂停（示例）',
      '收回菜单与接口（40301）；数据保留',
      '2026-11-01 09:30',
    ]);
    expect(logActionTone('OPEN')).toBe('success');
    expect(logActionTone('UPGRADE')).toBe('processing');
    expect(logActionTone('CLOSE')).toBe('default');
  });
});
