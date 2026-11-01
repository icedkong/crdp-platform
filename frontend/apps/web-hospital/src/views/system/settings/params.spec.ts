import type { TenantParamLogView, TenantParamView } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  effectiveValueText,
  overrideStateText,
  paramLogLine,
  rangeHint,
  toOverrideModel,
  validateOverrideModel,
} from './params';

const pool: TenantParamView = {
  description: null,
  effectiveValue: '20',
  name: '每租户连接池上限',
  overridden: false,
  paramKey: 'tenant.db.pool.max',
  platformValue: '20',
  stale: false,
  tenantOverridable: true,
  tenantValue: null,
  updatedAt: null,
  valueRange: '{"min":5,"max":50}',
  valueType: 'NUMBER',
};

describe('e06-t10 租户参数覆盖逻辑', () => {
  it('覆盖校验：范围内通过、超范围与类型错误拒绝（与后端同口径）', () => {
    const model = toOverrideModel(pool);
    model.value = '30';
    expect(validateOverrideModel(model, pool)).toEqual([]);

    model.value = '500';
    expect(validateOverrideModel(model, pool)).toContain('取值不得大于 50');
    model.value = 'big';
    expect(validateOverrideModel(model, pool)).toContain('取值必须是数字');
    model.value = ' ';
    expect(validateOverrideModel(model, pool)).toContain('取值不能为空');
  });

  it('平台锁定参数不给覆盖（前端预校验兜底，后端 40001 为准）', () => {
    const locked = { ...pool, tenantOverridable: false };
    const model = toOverrideModel(locked);
    model.value = '30';
    expect(validateOverrideModel(model, locked)).toContain(
      '该参数为平台锁定，不允许租户覆盖',
    );
  });

  it('变更说明上限 200 字', () => {
    const model = toOverrideModel(pool);
    model.value = '30';
    model.reason = '长'.repeat(201);
    expect(validateOverrideModel(model, pool)).toContain(
      '变更说明不能超过 200 个字符',
    );
  });

  it('生效值与覆盖状态文案：本院覆盖标记、锁定失效提示（PLT-CFG-011）', () => {
    const overridden = {
      ...pool,
      overridden: true,
      tenantValue: '30',
      effectiveValue: '30',
    };
    expect(effectiveValueText(overridden)).toBe('30（本院覆盖）');
    expect(overrideStateText(overridden)).toBe('已覆盖');

    expect(effectiveValueText(pool)).toBe('20');
    expect(overrideStateText(pool)).toBe('平台默认');

    const stale = {
      ...overridden,
      stale: true,
      effectiveValue: '20',
    };
    expect(overrideStateText(stale)).toBe(
      '覆盖已失效（参数被平台锁定，恢复默认值生效）',
    );
    expect(effectiveValueText(stale)).toBe('20');
  });

  it('变更记录行文案与范围提示', () => {
    const log: TenantParamLogView = {
      action: 'OVERRIDE',
      createdAt: '2026-11-01T10:00:00+08:00',
      newValue: '40',
      oldValue: '30',
      operatorId: 2,
      paramKey: 'tenant.db.pool.max',
      reason: '试点科室较多',
    };
    expect(paramLogLine(log)).toBe(
      'tenant.db.pool.max 30 → 40，原因：试点科室较多',
    );
    const reset: TenantParamLogView = {
      ...log,
      action: 'RESET',
      newValue: null,
      oldValue: '40',
      reason: null,
    };
    expect(paramLogLine(reset)).toBe('tenant.db.pool.max 40 → （恢复默认）');

    expect(rangeHint(pool)).toBe('允许范围：5–50');
    expect(rangeHint({ ...pool, valueRange: null, valueType: 'BOOLEAN' })).toBe(
      '',
    );
  });
});
