import type { ApiResourceItem } from '@crdp/api';

import { describe, expect, it } from 'vitest';

import {
  filterApiItems,
  isUnbound,
  moduleOptions,
  toMarkModel,
  toMarkRequest,
  validateMark,
} from './logic';

function item(partial: Partial<ApiResourceItem>): ApiResourceItem {
  return {
    actionType: 'QUERY',
    auditLevel: 'NONE',
    httpMethod: 'GET',
    id: partial.id ?? 1,
    moduleCode: partial.moduleCode ?? 'base',
    pathPattern: '/api/demo',
    permCode: null,
    publicAccess: false,
    sensitiveLevel: 'NORMAL',
    source: 'SCAN',
    ...partial,
  };
}

describe('e05-T06 接口资源页逻辑', () => {
  it('未绑定判定：非公共且无权限码 → 高亮（PLT-PERM-004）', () => {
    expect(isUnbound(item({ permCode: null, publicAccess: false }))).toBe(true);
    expect(
      isUnbound(item({ permCode: 'base:demo:view', publicAccess: false })),
    ).toBe(false);
    expect(isUnbound(item({ permCode: null, publicAccess: true }))).toBe(false);
  });

  it('筛选：按模块 / 权限码 / 只看未绑定', () => {
    const items = [
      item({ id: 1, moduleCode: 'base', permCode: 'base:demo:view' }),
      item({ id: 2, moduleCode: 'followup', permCode: 'followup:task:view' }),
      item({
        id: 3,
        moduleCode: 'followup',
        permCode: null,
        publicAccess: true,
      }),
      item({ id: 4, moduleCode: 'base', permCode: null, publicAccess: false }),
    ];
    expect(
      filterApiItems(items, {
        moduleCode: 'followup',
        permCode: '',
        unbound: false,
      }),
    ).toHaveLength(2);
    expect(
      filterApiItems(items, {
        moduleCode: '',
        permCode: 'followup:task:view',
        unbound: false,
      }),
    ).toEqual([items[1]]);
    expect(
      filterApiItems(items, { moduleCode: '', permCode: '', unbound: true }),
    ).toEqual([items[3]]);
    expect(moduleOptions(items)).toEqual([
      { label: 'base', value: 'base' },
      { label: 'followup', value: 'followup' },
    ]);
  });

  it('标记校验：未绑定权限码又非公共不允许保存（默认拒绝口径）', () => {
    const model = toMarkModel(item({ permCode: null, publicAccess: false }));
    expect(validateMark(model)).toContain(
      '未绑定权限码的接口必须标记为公共接口（否则一律不可访问）',
    );
    model.publicAccess = true;
    expect(validateMark(model)).toEqual([]);
    model.publicAccess = false;
    model.permCode = 'Bad_Code';
    expect(validateMark(model)).toContain(
      '权限码格式 {模块}:{资源}:{动作}，如 followup:task:view',
    );
    model.permCode = 'base:demo:view';
    expect(validateMark(model)).toEqual([]);
  });

  it('标记请求：空权限码归一为 null', () => {
    const model = toMarkModel(
      item({ permCode: 'base:demo:view', publicAccess: false }),
    );
    const request = toMarkRequest(model);
    expect(request.permCode).toBe('base:demo:view');
    expect(request.publicAccess).toBe(false);
    model.permCode = '  ';
    expect(toMarkRequest(model).permCode).toBeNull();
  });
});
